package com.prototypejust.weworkshop.steam

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

import com.prototypejust.weworkshop.DownloadService
import com.prototypejust.weworkshop.DownloadStore
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Wallpaper Engine 创意工坊源文件下载器。
 *
 * 流程（参考 Workshop-Native / DepotDownloader）：
 *  1. getDepotDecryptionKey(431960, 431960) 拿 depot 密钥
 *  2. getServersForSteamPipe 拿 CDN 节点，按 Tier 0/1 分组 + Round-Robin 轮询
 *  3. getManifestRequestCode(431960, 431960, manifestId, "public")
 *  4. downloadManifestFuture -> manifest
 *  5. 28 路异步预取 downloadDepotChunk + DepotChunk.process 解密解压，
 *     用 FileChannel 按 offset 写入持久 staging，并逐块实时刷盘记录
 *  6. 校验通过后复制到 Download/Wallpaper WE/
 *
 * 线程模型：网络阶段是 28 路纯异步协程（挂起在 OkHttp，不占线程、不碰 VZip），
 * 解压写盘阶段固定 8 线程（VZipUtil 的解压缓冲挂在 ThreadLocal 上，8MB/线程，
 * 8 线程 = 64MB 封顶）。两阶段之间用容量 20 的 Channel 做有界背压。
 * 缓冲按线程原地复用，内存占用与分块数量无关。
 */
object WorkshopDownloader {
    private const val TAG = "WEDownload"
    const val APP_ID = 431960
    const val WORKSHOP_DEPOT_ID = 431960
    private const val CDN_CANDIDATE_LIMIT = 20

    /** 同时真正运行的任务数上限（多任务排队，避免内存与带宽互相挤占）。 */
    private const val MAX_CONCURRENT_TASKS = 2

    /** 解密解压写盘专用线程数：对齐手机大核（8 线程 x 8MB VZip ThreadLocal = 64MB 封顶），留余核给网络收包。 */
    private const val WORKER_THREADS = 8

    /** 纯异步网络预取并发数（OkHttp executeAsync 挂起，不碰 VZip ThreadLocal，零额外常驻内存）。 */
    private const val NET_PREFETCH_CONCURRENCY = 28

    /** 已下载待解压的有界背压队列容量（最多缓存 20 个 ~1MB 压缩块 = 20MB，满了自动背压挂起网络拉取）。 */
    private const val PREFETCH_QUEUE_CAPACITY = 20

    private const val MANIFEST_TIMEOUT_SEC = 12L
    private const val CHUNK_TIMEOUT_MS = 14_000L

    /** 单个 chunk 最多尝试的节点数（Tier 0 轮询首发 + Tier 1 兜底）。 */
    private const val MAX_NODE_ATTEMPTS = 4

    /** 节点失败达到该次数即视为坏节点，优先从健康池里选。 */
    private const val NODE_BAD_THRESHOLD = 3

    /** 界面刷新间隔 / 进度落盘间隔。 */
    private const val EMIT_INTERVAL_MS = 300L
    private const val PERSIST_INTERVAL_MS = 1500L

    /** 真实网络错误的外层轻量重试：最多 1 次，且保留已下载分块。 */
    private const val OUTER_RETRY_MAX = 1
    private const val OUTER_RETRY_DELAY_MS = 2000L

    private const val CANCEL_MSG = "已取消"
    private const val META_FILE = ".meta"
    private const val DONE_CHUNKS = ".done_chunks"
    private const val STAGING_ROOT = "workshop_staging"

    private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "mkv", "avi", "m4v", "wmv")

    /** 官方及国内一线大厂高速 CDN 域名（Tier 0）；其余边缘/限速镜像归入 Tier 1 仅作兜底。 */
    private val TIER0_DOMAINS = listOf(
        "clngaa.com",
        "steamcontent.com",
        "akamaihd.net",
        "akamaized.net",
        "steamstatic.com",
        "llnwd.net",
        "qtlglb.com",
        "mileweb.com"
    )

    private fun isTier0(server: Server): Boolean {
        val h = server.host?.lowercase(Locale.US) ?: return false
        return TIER0_DOMAINS.any { d -> h == d || h.endsWith(".$d") }
    }

    /** 单主机实时健康度与吞吐画像：隔离 5xx/超时节点与慢速镜像。 */
    private class HostStats {
        val penalty = AtomicInteger(0)
        val hardErrors = AtomicInteger(0)
        val ewmaBps = AtomicLong(-1L)
        val cooldownUntilMs = AtomicLong(0L)
    }

    /** 预取的单个分块：网络阶段只负责把密文拉进内存，解压写盘交给消费者阶段。 */
    private class FetchedChunk(val chunk: ChunkData, val encrypted: ByteArray)

    /** CDN 健康降级开关：一旦代理/节点异常自动关闭，本次进程内后续请求全部直连。 */
    @Volatile
    private var cdnProxyEnabled = false
    data class Progress(
        val itemId: Long,
        val received: Long,
        val total: Long,
        val phase: String,
        val done: Boolean = false,
        val paused: Boolean = false,
        val error: String? = null
    )

    fun interface ProgressListener {
        fun onProgress(
            itemId: Long,
            received: Long,
            total: Long,
            phase: String,
            done: Boolean,
            paused: Boolean,
            error: String?
        )
    }

    @Volatile private var listener: ProgressListener? = null

    @JvmStatic
    fun setProgressListener(l: ProgressListener?) { listener = l }

    // ---------------- 固定大小 worker 线程池 ----------------

    private val workerPool = Executors.newFixedThreadPool(WORKER_THREADS) { r ->
        Thread(r, "we-dl-worker").apply { isDaemon = true }
    }
    private val workerDispatcher = workerPool.asCoroutineDispatcher()

    // ---------------- 内存状态 ----------------

    private val titles = ConcurrentHashMap<Long, String>()
    private val images = ConcurrentHashMap<Long, String>()
    private val activeItems = ConcurrentHashMap.newKeySet<Long>()
    private val cancelledItems = ConcurrentHashMap.newKeySet<Long>()
    /** 用户主动暂停的条目：暂停必须保留磁盘分块，绝不清理。 */
    private val pausedItems = ConcurrentHashMap.newKeySet<Long>()
    /** 本次启动时被判定为「上次被系统杀掉」的条目，用于启动后弹窗询问用户。 */
    private val interruptedAtLaunch = ConcurrentHashMap.newKeySet<Long>()

    private val manifests = ConcurrentHashMap<Long, Long>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lastProgress = ConcurrentHashMap<Long, Progress>()
    private val lastPersistAt = ConcurrentHashMap<Long, Long>()
    @Volatile private var appCtx: Context? = null

    private val downloadSlots = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_TASKS)

    @JvmStatic
    fun isActive(itemId: Long): Boolean = activeItems.contains(itemId)

    fun isCancelled(itemId: Long): Boolean = cancelledItems.contains(itemId)

    fun isPaused(itemId: Long): Boolean = pausedItems.contains(itemId)

    // ---------------- 进度 JSON ----------------

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    private fun progressJsonOf(p: Progress): String {
        val err = p.error
        return buildString {
            append("{\"itemId\":").append(p.itemId)
            append(",\"received\":").append(p.received)
            append(",\"total\":").append(p.total)
            append(",\"phase\":\"").append(jsonEscape(p.phase)).append('"')
            append(",\"done\":").append(p.done)
            append(",\"paused\":").append(p.paused)
            append(",\"error\":").append(if (err == null) "null" else "\"" + jsonEscape(err) + "\"")
            append('}')
        }
    }

    /** 返回某条目最近一次进度（JSON），用于界面重建/重进详情页时恢复进度条。 */
    @JvmStatic
    fun progressJson(itemId: Long): String? {
        val p = lastProgress[itemId] ?: return null
        return progressJsonOf(p)
    }

    /** 所有活跃下载的最新进度（JSON map），供 App 回到前台/打开列表时同步。 */
    @JvmStatic
    fun activeProgressJson(): String {
        val sb = StringBuilder("{")
        var first = true
        for (id in activeItems) {
            val p = lastProgress[id] ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(id).append("\":").append(progressJsonOf(p))
        }
        return sb.append('}').toString()
    }

    // ---------------- 进度上报 ----------------

    private fun emit(p: Progress, persist: Boolean = true) {
        lastProgress[p.itemId] = p
        val terminal = p.done || p.paused || p.error != null
        val now = System.currentTimeMillis()
        val shouldPersist = persist && (terminal || now - (lastPersistAt[p.itemId] ?: 0L) >= PERSIST_INTERVAL_MS)
        if (shouldPersist) {
            lastPersistAt[p.itemId] = now
            val status = when {
                p.paused -> "paused"
                p.error == CANCEL_MSG -> "canceled"
                p.error != null -> "failed"
                p.done -> "done"
                else -> "downloading"
            }
            val rec = if (status == "canceled") 0L else p.received
            runCatching {
                DownloadStore.upsert(
                    p.itemId,
                    titles[p.itemId] ?: "",
                    images[p.itemId] ?: "",
                    p.total, rec, status, manifests[p.itemId] ?: 0L
                )
            }
        }
        listener?.onProgress(p.itemId, p.received, p.total, p.phase, p.done, p.paused, p.error)
        appCtx?.let { ctx ->
            DownloadService.onProgress(
                ctx,
                p.itemId,
                titles[p.itemId]?.takeIf { it.isNotBlank() } ?: "壁纸 #${p.itemId}",
                p.received,
                p.total,
                p.phase,
                p.done,
                p.paused,
                p.error,
                activeItems.size
            )
        }
    }

    /** 读取已知进度：内存最新值优先，其次 DownloadStore 记录（进程重启后靠它接上）。 */
    private fun knownProgress(itemId: Long): Progress {
        lastProgress[itemId]?.let { p ->
            if (!p.done && p.error != CANCEL_MSG) return p
        }
        val rec = runCatching { DownloadStore.get(itemId) }.getOrNull()
        val st = rec?.optString("status", "") ?: ""
        val tot = rec?.optLong("size", 0L) ?: 0L
        if (st == "done" || st == "canceled") {
            return Progress(itemId, 0L, tot, "准备中…")
        }
        val recv = rec?.optLong("received", 0L) ?: 0L
        return Progress(itemId, recv, tot, "准备中…")
    }
    // ---------------- 暂停 / 继续 / 取消 ----------------

    /** 暂停：保留全部磁盘分块，取消运行中的协程，立即把 paused 状态推给界面。 */
    @JvmStatic
    fun pause(context: Context, itemId: Long) {
        DownloadStore.init(context)
        pausedItems.add(itemId)
        cancelledItems.remove(itemId)
        val p = knownProgress(itemId)
        emit(Progress(itemId, p.received, p.total, "已暂停", paused = true))
        jobs[itemId]?.cancel()
        logFile(context, "pause item=$itemId received=${p.received}")
    }

    @JvmStatic
    fun pauseAll(context: Context) {
        DownloadStore.init(context)
        activeItems.toList().forEach { pause(context, it) }
    }

    /** 取消单条：删除该条目的未完成分块，记录置为 canceled 且进度归零。 */
    @JvmStatic
    fun cancel(context: Context?, itemId: Long) {
        pausedItems.remove(itemId)
        cancelledItems.add(itemId)
        jobs[itemId]?.cancel()
        if (context != null) {
            DownloadStore.init(context)
            deleteStaging(context, itemId)
            logFile(context, "cancel item=$itemId (staging purged)")
        }
        val p = knownProgress(itemId)
        emit(Progress(itemId, 0L, p.total, "已取消", error = CANCEL_MSG))
    }

    @JvmStatic
    fun cancel(context: Context?) {
        activeItems.toList().forEach { cancel(context, it) }
    }

    /** 无 context 兼容重载：只停任务，不清磁盘。 */
    @JvmStatic
    fun cancel(itemId: Long) = cancel(null, itemId)

    @JvmStatic
    fun cancel() = cancel(null as Context?)

    /** 彻底移除：停任务、删 staging（含旧版 cache 残留）、清内存 map、删下载记录。 */
    @JvmStatic
    fun removeAndClean(context: Context, itemId: Long) {
        cancelledItems.add(itemId)
        pausedItems.remove(itemId)
        jobs[itemId]?.cancel()
        runCatching { deleteStaging(context, itemId) }
        runCatching { File(context.cacheDir, "workshop/$itemId").deleteRecursively() }
        titles.remove(itemId)
        images.remove(itemId)
        manifests.remove(itemId)
        lastProgress.remove(itemId)
        lastPersistAt.remove(itemId)
        interruptedAtLaunch.remove(itemId)
        jobs.remove(itemId)
        DownloadStore.init(context)
        DownloadStore.remove(itemId)
        logFile(context, "remove item=$itemId (all files purged)")
    }

    // ---------------- 启动中断归档（绝不静默偷跑） ----------------

    /**
     * App 启动时调用：把「记录说还在下载、但进程里并没有任务」的条目改成 paused。
     * 不自动续传——是否继续由用户在弹窗里决定。
     */
    @JvmStatic
    fun markInterruptedAsPaused(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        try {
            DownloadStore.init(ctx)
            val arr = DownloadStore.list()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("status") != "downloading") continue
                val id = o.optLong("id", 0L)
                if (id <= 0L || isActive(id)) continue
                val stored = o.optLong("received", 0L)
                val size = o.optLong("size", 0L)
                // 以磁盘上真实存在的分块为准：journal 里累计的字节数可能比记录更准
                val real = maxOf(stored, journalBytes(ctx, id))
                val manifest = o.optString("manifest", "").toLongOrNull() ?: 0L
                DownloadStore.upsert(
                    id,
                    o.optString("title", ""),
                    o.optString("img", ""),
                    size,
                    real,
                    "paused",
                    manifest
                )
                interruptedAtLaunch.add(id)
                logFile(ctx, "mark interrupted item=$id as paused, received=$real")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "markInterruptedAsPaused failed", t)
        }
    }

    /** 取出「启动时被中断」的条目 JSON 供前端弹窗；只取一次，取完即清空。 */
    @JvmStatic
    fun consumeInterruptedPromptJson(context: Context): String? {
        if (interruptedAtLaunch.isEmpty()) return null
        val ids = interruptedAtLaunch.toList()
        interruptedAtLaunch.clear()
        val ctx = context.applicationContext
        DownloadStore.init(ctx)
        val arr = DownloadStore.list()
        val out = StringBuilder("[")
        var first = true
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id <= 0L || !ids.contains(id)) continue
            if (o.optString("status") != "paused") continue
            if (!first) out.append(',')
            first = false
            out.append('{')
            out.append("\"id\":").append('"').append(id).append('"').append(',')
            out.append("\"title\":\"").append(jsonEscape(o.optString("title", ""))).append("\",")
            out.append("\"received\":").append(o.optLong("received", 0L)).append(',')
            out.append("\"size\":").append(o.optLong("size", 0L)).append(',')
            out.append("\"manifest\":\"").append(jsonEscape(o.optString("manifest", ""))).append("\"")
            out.append('}')
        }
        out.append(']')
        return if (first) null else out.toString()
    }

    /** 用户确认继续：按 idsCsv 恢复；为空则恢复全部 paused 条目。 */
    @JvmStatic
    fun resumePaused(context: Context, idsCsv: String?) {
        val ctx = context.applicationContext
        DownloadStore.init(ctx)
        val wanted: List<Long> = if (!idsCsv.isNullOrBlank()) {
            idsCsv.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }
        } else {
            val arr = DownloadStore.list()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", 0L)
                if (id > 0L && o.optString("status") == "paused") id else null
            }
        }
        for (id in wanted) {
            val rec = DownloadStore.get(id) ?: continue
            val manifest = rec.optString("manifest", "").toLongOrNull() ?: 0L
            if (manifest <= 0L) {
                logFile(ctx, "resume skipped item=$id: manifest missing")
                continue
            }
            logFile(ctx, "resume paused item=$id manifest=$manifest")
            downloadAsync(ctx, id, manifest, rec.optString("title", ""), rec.optString("img", ""))
        }
    }
    // ---------------- staging / 缓存 ----------------

    private fun stagingRoot(context: Context) = File(context.filesDir, STAGING_ROOT)

    /** 持久 staging（filesDir，不会被系统当缓存静默清掉）。 */
    private fun stagingDir(context: Context, itemId: Long) = File(stagingRoot(context), itemId.toString())

    private fun legacyStagingDir(context: Context, itemId: Long) = File(context.cacheDir, "workshop/$itemId")

    private fun deleteStaging(context: Context, itemId: Long) {
        runCatching { stagingDir(context, itemId).deleteRecursively() }
        runCatching { legacyStagingDir(context, itemId).deleteRecursively() }
    }

    /** 旧版把分块放在 cacheDir，首次接触时把它挪到持久目录，尽量不丢已有进度。 */
    private fun migrateLegacyStaging(context: Context, itemId: Long) {
        val legacy = legacyStagingDir(context, itemId)
        if (!legacy.exists()) return
        val target = stagingDir(context, itemId)
        if (target.exists()) {
            runCatching { legacy.deleteRecursively() }
            return
        }
        target.parentFile?.mkdirs()
        if (!runCatching { legacy.renameTo(target) }.getOrDefault(false)) {
            runCatching { legacy.deleteRecursively() }
        }
    }

    /** 暂停/下载中的条目必须保护，不能被「清除缓存」误删。 */
    private fun isProtected(context: Context, itemId: Long): Boolean {
        if (isActive(itemId)) return true
        val rec = runCatching { DownloadStore.get(itemId) }.getOrNull() ?: return false
        val st = rec.optString("status", "")
        return st == "paused" || st == "downloading"
    }

    private fun dirSize(f: File): Long = try {
        f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (t: Throwable) {
        0L
    }

    /** staging 里可以安全清理的残留目录（非活跃、且不是 paused/downloading）。 */
    private fun clearableStaging(context: Context): List<File> {
        val root = stagingRoot(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.filter { d ->
            val id = d.name.toLongOrNull()
            id == null || !isProtected(context, id)
        }
    }

    /** 统计占用：cacheDir + 可清理的 staging 残留。 */
    @JvmStatic
    fun cacheBytes(context: Context): Long {
        // isProtected() 依赖 DownloadStore 记录判断条目是否暂停/下载中，
        // 未初始化时 get() 恒为 null，会让受保护的分块被误判为可清理。
        DownloadStore.init(context)
        var total = 0L
        context.cacheDir?.let { total += dirSize(it) }
        total += clearableStaging(context).sumOf { dirSize(it) }
        return total
    }

    /** 清理缓存：保护正在下载与已暂停未下完的分块。 */
    @JvmStatic
    fun clearCache(context: Context): Long {
        DownloadStore.init(context)
        val before = cacheBytes(context)
        context.cacheDir?.listFiles()?.forEach { f ->
            when {
                f.isDirectory && f.name == "workshop" -> {
                    // 旧版 staging：仍然保护活跃/暂停/下载中的条目
                    f.listFiles()?.forEach { sub ->
                        val sid = sub.name.toLongOrNull()
                        if (sid == null || !isProtected(context, sid)) {
                            runCatching { sub.deleteRecursively() }
                        }
                    }
                }
                else -> runCatching { f.deleteRecursively() }
            }
        }
        clearableStaging(context).forEach { runCatching { it.deleteRecursively() } }
        return (before - cacheBytes(context)).coerceAtLeast(0L)
    }

    /** 读取 staging 的 journal 累计字节数（第 3 列 len 求和）。 */
    private fun journalBytes(context: Context, itemId: Long): Long {
        val f = File(stagingDir(context, itemId), DONE_CHUNKS)
        if (!f.exists()) return 0L
        var sum = 0L
        runCatching {
            f.forEachLine { line ->
                val parts = line.split('|')
                if (parts.size >= 3) {
                    val len = parts[2].toLongOrNull() ?: 0L
                    if (len > 0L) sum += len
                }
            }
        }
        return sum
    }

    // ---------------- 启动下载 ----------------

    @JvmStatic
    fun downloadAsync(context: Context, itemId: Long, manifestId: Long, title: String?, img: String?) {
        DownloadStore.init(context)
        appCtx = context.applicationContext
        if (!title.isNullOrEmpty()) titles[itemId] = title
        if (!img.isNullOrEmpty()) images[itemId] = img

        // 启动即视为「用户要求继续」：清掉暂停/取消标记
        pausedItems.remove(itemId)
        cancelledItems.remove(itemId)

        if (!activeItems.add(itemId)) {
            // 同一任务重复点击不重复启动，避免并发写同一文件
            lastProgress[itemId]?.let { emit(it, persist = false) }
            return
        }
        manifests[itemId] = manifestId
        DownloadService.start(context.applicationContext)

        // 排队/准备阶段沿用已知进度，严禁用 0/0 把界面与存储进度清零
        val prevStatus = runCatching { DownloadStore.get(itemId)?.optString("status", "") }.getOrNull().orEmpty()
        val resetCompleted = lastProgress[itemId]?.done == true || prevStatus == "done"
        if (resetCompleted) {
            lastProgress.remove(itemId)
        }
        val known = knownProgress(itemId)
        // 立刻把 manifest 落库：进程若在下个阶段被杀，重启后 markInterruptedAsPaused
        // 变成 paused 的条目仍能靠它拿到 manifest 继续下载（否则 resumePaused 会跳过）。
        runCatching {
            DownloadStore.upsert(
                itemId,
                titles[itemId] ?: "",
                images[itemId] ?: "",
                known.total,
                if (resetCompleted) -1L else known.received,
                "downloading",
                manifestId
            )
        }

        val job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var attempt = 0
            try {
                emit(Progress(itemId, known.received, known.total, "排队中…"))
                downloadSlots.acquire()
                try {
                    while (true) {
                        try {
                            download(context.applicationContext, itemId, manifestId)
                            return@launch
                        } catch (t: Throwable) {
                            // 1) 用户暂停：保留分块，退出
                            if (pausedItems.contains(itemId)) {
                                val cur = lastProgress[itemId] ?: known
                                emit(Progress(itemId, cur.received, cur.total, "已暂停", paused = true))
                                logFile(context, "paused item=$itemId at received=${cur.received}")
                                return@launch
                            }
                            // 2) 取消：清理分块并置 canceled
                            if (cancelledItems.contains(itemId) || t is kotlinx.coroutines.CancellationException) {
                                handleCancelled(context, itemId)
                                return@launch
                            }
                            // 3) 真实网络/校验错误：外层最多轻量重试 1 次，保留已下载分块
                            attempt++
                            val cur = lastProgress[itemId] ?: known
                            Log.e(TAG, "download failed for $itemId (attempt $attempt)", t)
                            logFile(
                                context,
                                "FAIL item=$itemId manifest=$manifestId attempt=$attempt " +
                                    "received=${cur.received}: ${t.javaClass.simpleName}: ${t.message} " +
                                    "cause=${t.cause?.javaClass?.simpleName}:${t.cause?.message}"
                            )
                            if (attempt > OUTER_RETRY_MAX) {
                                emit(
                                    Progress(
                                        itemId, cur.received, cur.total, "连接中断",
                                        error = t.message ?: "下载中断，点击继续可接着下"
                                    )
                                )
                                return@launch
                            }
                            emit(Progress(itemId, cur.received, cur.total, "自动重试 1/1（2 秒后）"))
                            delay(OUTER_RETRY_DELAY_MS)
                            if (pausedItems.contains(itemId)) {
                                val c2 = lastProgress[itemId] ?: known
                                emit(Progress(itemId, c2.received, c2.total, "已暂停", paused = true))
                                return@launch
                            }
                            if (cancelledItems.contains(itemId)) {
                                handleCancelled(context, itemId)
                                return@launch
                            }
                        }
                    }
                } finally {
                    downloadSlots.release()
                }
            } catch (t: Throwable) {
                // 还在排队就被暂停/取消时，内层 catch 走不到，这里兜底
                if (pausedItems.contains(itemId)) {
                    val cur = lastProgress[itemId] ?: known
                    emit(Progress(itemId, cur.received, cur.total, "已暂停", paused = true))
                } else if (cancelledItems.contains(itemId) || t is kotlinx.coroutines.CancellationException) {
                    handleCancelled(context, itemId)
                }
            } finally {
                activeItems.remove(itemId)
                jobs.remove(itemId)
                if (activeItems.isEmpty()) {
                    DownloadService.stop(context.applicationContext)
                }
            }
        }
        jobs[itemId] = job
        if (job.isCompleted) jobs.remove(itemId)
    }

    private fun handleCancelled(context: Context, itemId: Long) {
        val p = lastProgress[itemId]
        runCatching { deleteStaging(context, itemId) }
        emit(Progress(itemId, 0L, p?.total ?: 0L, "已取消", error = CANCEL_MSG))
    }
    // ---------------- 核心下载 ----------------

    private suspend fun download(context: Context, itemId: Long, manifestId: Long) {
        val client = SteamService.steamClient()
            ?: throw IllegalStateException("请先登录 Steam")
        val apps = SteamService.appsOrThrow()
        val content = SteamService.contentOrThrow()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val known = knownProgress(itemId)

        emit(Progress(itemId, known.received, known.total, "获取 depot 密钥…"))
        val depotKeyCb = apps.getDepotDecryptionKey(WORKSHOP_DEPOT_ID, APP_ID)
            .toFuture()
            .get(60, TimeUnit.SECONDS)
        if (depotKeyCb.result != EResult.OK) {
            throw IllegalStateException("获取 depot 密钥失败：${depotKeyCb.result}")
        }
        val depotKey = depotKeyCb.depotKey
            ?: throw IllegalStateException("depot 密钥为空")

        emit(Progress(itemId, known.received, known.total, "获取 CDN 节点…"))
        // cellID 在 javasteam 1.8.0 无公开 getter（SteamClient / SteamUser 均无），
        // 保持传 null：服务端会按当前客户端 IP 归属地分配最近的同城节点。
        val servers = content.getServersForSteamPipe(null, CDN_CANDIDATE_LIMIT, scope).await()
        Log.i(TAG, "cdn servers raw: " + servers.joinToString { "${it.protocol}://${it.host}:${it.port}" })
        logFile(context, "cdn raw: " + servers.joinToString { "${it.protocol}://${it.host}:${it.port}" })

        // 官方 useAsProxy 缓存代理节点（与真实下载节点分离，仅作中转）
        val rawProxy: Server? = servers.firstOrNull {
            it.useAsProxy && !it.proxyRequestPathTemplate.isNullOrBlank()
        }
        cdnProxyEnabled = rawProxy != null
        fun currentProxy(): Server? = if (cdnProxyEnabled) rawProxy else null

        // 只在允许本 App、类型为缓存/CDN、且非纯代理的节点上取内容
        val filtered = servers.filter { s ->
            val ids = s.allowedAppIds
            val appOk = ids == null || ids.isEmpty() || ids.contains(APP_ID)
            val type = s.type
            val typeOk = type.isNullOrEmpty() || type == "SteamCache" || type == "CDN"
            !s.useAsProxy && appOk && typeOk
        }
        // 官方 weightedLoad 是实时负载评分（越低越好），替代旧的静态 load 排序
        val httpsServers = filtered
            .filter { it.protocol == Server.ConnectionProtocol.HTTPS }
            .sortedBy { it.weightedLoad }
        // 走代理/VPN 时 Steam 可能只下发明文 HTTP 节点（HTTPS 节点为空），
        // App 已允许 cleartext，此时回退到 HTTP 节点。
        val candidateServers = if (httpsServers.isNotEmpty()) httpsServers else filtered
            .filter { it.protocol == Server.ConnectionProtocol.HTTP }
            .sortedBy { it.weightedLoad }
        if (candidateServers.isEmpty()) {
            throw IllegalStateException("没有可用的 CDN 节点，请检查手机代理/VPN")
        }
        val useHttp = httpsServers.isEmpty()
        val tier0Servers = candidateServers.filter { isTier0(it) }.sortedBy { it.weightedLoad }
        val tier1Servers = candidateServers.filterNot { isTier0(it) }.sortedBy { it.weightedLoad }
        val orderedServers = tier0Servers + tier1Servers

        fun buildWeightedRing(list: List<Server>): List<Server> {
            if (list.isEmpty()) return emptyList()
            val buckets = list.map { s ->
                val weight = s.numEntries.coerceIn(1, 6)
                MutableList(weight) { s }
            }
            val out = ArrayList<Server>()
            val maxBucket = buckets.maxOfOrNull { it.size } ?: 0
            for (round in 0 until maxBucket) {
                for (b in buckets) {
                    if (round < b.size) out.add(b[round])
                }
            }
            return out
        }

        val tier0Ring = buildWeightedRing(tier0Servers)
        val tier1Ring = buildWeightedRing(tier1Servers)
        val summary = orderedServers.take(6).joinToString {
            val tag = if (isTier0(it)) "T0" else "T1"
            "${it.host}[$tag,w=${"%.1f".format(Locale.US, it.weightedLoad)},e=${it.numEntries}]"
        }
        val cryptoName = FastChunkProcessor.providerName()
        Log.i(TAG, "cdn pool (t0=${tier0Servers.size}, t1=${tier1Servers.size}, proxy=${rawProxy?.host ?: "none"}, http=$useHttp, crypto=$cryptoName): $summary")
        logFile(context, "cdn pool(t0=${tier0Servers.size},t1=${tier1Servers.size},proxy=${rawProxy?.host ?: "none"},http=$useHttp,crypto=$cryptoName): $summary")

        emit(Progress(itemId, known.received, known.total, "申请下载授权…"))
        val requestCode = content.getManifestRequestCode(
            WORKSHOP_DEPOT_ID, APP_ID, manifestId, "public", null, scope
        ).await()
        Log.i(TAG, "manifest request code: $requestCode (manifestId=$manifestId)")
        if (requestCode == 0L) {
            throw IllegalStateException("申请下载授权失败（manifest 请求码为空）")
        }

        val cdn = Client(client)
        val diag = StringBuilder()
        diag.append("节点=").append(orderedServers.size).append("(http=").append(useHttp).append(");")
        diag.append("授权码=").append(requestCode).append(';')

        // CDN 请求需要 per-host 的 auth token，缺了会 403。
        // 关键：空 token 绝不入缓存——否则该 host 之后所有请求都带着空 token 吃 403，
        // 直到整个任务结束都无法恢复。
        val tokenCache = ConcurrentHashMap<String, String>()
        val tokenLocks = ConcurrentHashMap<String, Mutex>()
        suspend fun tokenFor(server: Server, forceRefresh: Boolean = false): String {
            val key = server.host ?: ""
            if (key.isEmpty()) return ""
            if (!forceRefresh) {
                tokenCache[key]?.let { if (it.isNotEmpty()) return it }
            }
            // per-host Mutex：24 路预取启动瞬间对同一 host 只发 1 次 getCDNAuthToken，
            // 避免并发雪崩把 WebAPI 打限流。
            val lock = tokenLocks.getOrPut(key) { Mutex() }
            return lock.withLock {
                if (!forceRefresh) {
                    tokenCache[key]?.let { if (it.isNotEmpty()) return@withLock it }
                }
                val tok = try {
                    content.getCDNAuthToken(APP_ID, WORKSHOP_DEPOT_ID, key, scope)
                        .await()
                        .takeIf { it.result == EResult.OK }
                        ?.token
                } catch (t: Throwable) {
                    Log.w(TAG, "cdn auth token for $key failed: ${t.message}")
                    null
                }.orEmpty()
                if (tok.isNotEmpty()) tokenCache[key] = tok else tokenCache.remove(key)
                diag.append("token($key)=").append(if (tok.isEmpty()) "EMPTY" else "ok").append(';')
                tok
            }
        }

        emit(Progress(itemId, known.received, known.total, "下载 manifest…"))
        var manifest: DepotManifest? = null
        var lastErr: Throwable? = null
        for (server in orderedServers.take(6)) {
            checkCancelled(itemId)
            try {
                val token = tokenFor(server)
                manifest = cdn.downloadManifestFuture(
                    WORKSHOP_DEPOT_ID, manifestId, requestCode, server, depotKey, currentProxy(),
                    token.trimStart('?').ifEmpty { null }
                ).get(MANIFEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                break
            } catch (t: Throwable) {
                if (cdnProxyEnabled) {
                    // 代理节点异常：立即降级为直连重试一次
                    cdnProxyEnabled = false
                    try {
                        val token = tokenFor(server)
                        manifest = cdn.downloadManifestFuture(
                            WORKSHOP_DEPOT_ID, manifestId, requestCode, server, depotKey, null,
                            token.trimStart('?').ifEmpty { null }
                        ).get(MANIFEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                        break
                    } catch (t2: Throwable) {
                        lastErr = t2
                    }
                } else {
                    lastErr = t
                }
                Log.w(TAG, "manifest from ${server.host} failed: ${lastErr?.message}")
                diag.append("manifest(").append(server.host).append(")=").append(lastErr?.message?.take(120)).append(';')
            }
        }
        val m = manifest ?: throw IllegalStateException("下载 manifest 失败：${lastErr?.message}\n诊断：" + diag)
        checkCancelled(itemId)
        if (m.filenamesEncrypted) {
            if (!m.decryptFilenames(depotKey)) throw IllegalStateException("manifest 文件名解密失败")
        }

        val allFiles = m.files.filter {
            !it.flags.contains(EDepotFileFlag.Directory) && !it.flags.contains(EDepotFileFlag.Symlink)
        }
        // 只保留视频本体：manifest 里若存在视频文件，就只下载这些，忽略 pkg/预览图/json 等
        val videoFiles = allFiles.filter { f ->
            val n = f.fileName.lowercase()
            VIDEO_EXTS.any { ext -> n.endsWith(".$ext") }
        }
        val filesToDownload = if (videoFiles.isNotEmpty()) videoFiles else allFiles
        val totalBytes = filesToDownload.sumOf { it.totalSize }
        val namesPreview = filesToDownload.take(12).joinToString(",") { it.fileName }
        logFile(
            context,
            "manifest ok: all=${allFiles.size} videos=${videoFiles.size} " +
                "pick=${filesToDownload.size} total=$totalBytes names=[$namesPreview]"
        )
        // ---------- 持久 staging + 断点续传 ----------
        migrateLegacyStaging(context, itemId)
        val staging = stagingDir(context, itemId)
        staging.mkdirs()
        val metaFile = File(staging, META_FILE)
        val chunksLog = File(staging, DONE_CHUNKS)
        val fingerprint = manifestId.toString() + "|" +
            filesToDownload.joinToString("|") { it.fileName + ":" + it.totalSize }
        val resumeExisting = metaFile.exists() &&
            runCatching { metaFile.readText() }.getOrNull() == fingerprint
        if (!resumeExisting) {
            // manifest 变了：旧分块全部作废。这里必须显式把持久进度清零（received = -1），
            // 否则 DownloadStore 会保留旧的 received，而磁盘上其实一个分块都没有——
            // 下次启动 markInterruptedAsPaused 会把它当真，界面显示 50% 实际却从 0 重下。
            runCatching {
                staging.listFiles()?.forEach { it.deleteRecursively() }
            }
            runCatching {
                DownloadStore.upsert(
                    itemId, titles[itemId] ?: "", images[itemId] ?: "",
                    totalBytes, -1L, "downloading", manifestId
                )
            }
        }
        staging.mkdirs()
        metaFile.writeText(fingerprint)

        // 不能只看文件长度判断续传：多路并行时文件尾部可能先写满，长度变大但中间是空洞，
        // 只按长度跳过会把空洞当成已完成，播出来就是花屏。
        // 逐 chunk 记录完成情况，且回读时必须校验文件真实长度足以覆盖该块。
        val done: MutableSet<String> = loadDoneChunks(staging, chunksLog)
        val journal = ChunkJournal(chunksLog)
        try {
            val existingBytes = filesToDownload.sumOf { f ->
                f.chunks.filter { done.contains(f.fileName + "|" + it.offset) }
                    .sumOf { it.uncompressedLength.toLong() }
            }
            if (existingBytes > 0L) {
                logFile(context, "resume: existing=$existingBytes/$totalBytes for $itemId")
            }
            emit(
                Progress(
                    itemId, existingBytes, totalBytes,
                    if (existingBytes > 0L) "继续下载" else "开始下载"
                )
            )

            val rr = AtomicInteger(0)
            val hostStats = ConcurrentHashMap<String, HostStats>()
            val bestEwmaBps = AtomicLong(0L)
            fun statsOf(host: String): HostStats = hostStats.computeIfAbsent(host) { HostStats() }
            fun statsOf(s: Server): HostStats = statsOf(s.host ?: "")
            fun isHealthy(s: Server, now: Long): Boolean {
                val st = statsOf(s)
                val limit = if (isTier0(s) && tier0Servers.size == 1) NODE_BAD_THRESHOLD * 3 else NODE_BAD_THRESHOLD
                return now >= st.cooldownUntilMs.get() && st.penalty.get() < limit
            }

            fun pickNodes(): List<Server> {
                val now = System.currentTimeMillis()
                val out = ArrayList<Server>(MAX_NODE_ATTEMPTS)
                val seen = HashSet<String>()
                fun addUnique(s: Server): Boolean {
                    val id = (s.host ?: "") + ":" + s.port
                    if (seen.add(id)) {
                        out.add(s)
                        return out.size >= MAX_NODE_ATTEMPTS
                    }
                    return false
                }
                fun scanRing(ring: List<Server>) {
                    if (ring.isEmpty() || out.size >= MAX_NODE_ATTEMPTS) return
                    val start = (rr.getAndIncrement() and 0x7fffffff) % ring.size
                    for (k in ring.indices) {
                        val s = ring[(start + k) % ring.size]
                        if (!isHealthy(s, now)) continue
                        if (addUnique(s)) return
                    }
                }

                scanRing(tier0Ring)
                if (out.isEmpty()) {
                    scanRing(tier1Ring)
                }
                if (out.size < MAX_NODE_ATTEMPTS) {
                    for (s in tier0Servers) {
                        if (isHealthy(s, now) && addUnique(s)) break
                    }
                }
                if (out.size < MAX_NODE_ATTEMPTS) {
                    for (s in tier1Servers) {
                        if (isHealthy(s, now) && addUnique(s)) break
                    }
                }
                if (out.size < MAX_NODE_ATTEMPTS) {
                    val fallback = orderedServers.sortedBy { statsOf(it).hardErrors.get() }
                    for (s in fallback) {
                        if (addUnique(s)) break
                    }
                }
                return out
            }

            val meter = SpeedMeter()
            val lastEmitAt = AtomicLong(0L)
            val receivedCounter = AtomicLong(existingBytes)
            val totalForTask = totalBytes

            for (file in filesToDownload) {
                checkCancelled(itemId)
                val outFile = File(staging, sanitizePath(file.fileName))
                outFile.parentFile?.mkdirs()

                val pending = file.chunks.sortedBy { it.offset }.filterNot { chunk ->
                    done.contains(file.fileName + "|" + chunk.offset)
                }
                if (pending.isEmpty()) {
                    verifyOrRepair(file, outFile, journal)
                    continue
                }

                // 每个文件一个共享 FileChannel：多 worker 按不同 offset 位置写入互不干扰
                FileChannel.open(
                    outFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                ).use { channel ->
                    coroutineScope {
                        val queue = Channel<FetchedChunk>(PREFETCH_QUEUE_CAPACITY)
                        val nextChunkIdx = AtomicInteger(0)
                        val fetchWorkers = minOf(NET_PREFETCH_CONCURRENCY, pending.size)
                        val decompressWorkers = minOf(WORKER_THREADS, pending.size)

                        // 阶段 1（生产者）：纯异步网络预取协程池，按 offset 升序动态抢单，不占解压线程
                        val producers = (0 until fetchWorkers).map {
                            launch(Dispatchers.IO) {
                                while (true) {
                                    checkCancelled(itemId)
                                    val i = nextChunkIdx.getAndIncrement()
                                    if (i >= pending.size) break
                                    val chunk = pending[i]
                                    val encrypted = ByteArray(chunk.compressedLength)
                                    var ok = false
                                    var err: Throwable? = null
                                    val nodes = pickNodes()
                                    var idx = 0
                                    while (idx < nodes.size) {
                                        checkCancelled(itemId)
                                        val server = nodes[idx]
                                        val host = server.host ?: ""
                                        try {
                                            val startMs = System.currentTimeMillis()
                                            var token = tokenFor(server)
                                            try {
                                                withTimeout(CHUNK_TIMEOUT_MS) {
                                                    cdn.downloadDepotChunk(
                                                        WORKSHOP_DEPOT_ID, chunk, server, encrypted, null,
                                                        currentProxy(),
                                                        token.trimStart('?').ifEmpty { null }
                                                    )
                                                }
                                            } catch (firstErr: Throwable) {
                                                // withTimeout 的 TimeoutCancellationException 也是
                                                // CancellationException，必须排除，否则超时会被当成用户取消
                                                if (firstErr is TimeoutCancellationException) throw firstErr
                                                if (firstErr is kotlinx.coroutines.CancellationException) {
                                                    checkCancelled(itemId)
                                                    throw firstErr
                                                }
                                                val msg = firstErr.message.orEmpty()
                                                if (cdnProxyEnabled) {
                                                    // 代理节点异常时立即自动降级为直连重试一次
                                                    cdnProxyEnabled = false
                                                    withTimeout(CHUNK_TIMEOUT_MS) {
                                                        cdn.downloadDepotChunk(
                                                            WORKSHOP_DEPOT_ID, chunk, server, encrypted, null,
                                                            null,
                                                            token.trimStart('?').ifEmpty { null }
                                                        )
                                                    }
                                                } else if (msg.contains("403")) {
                                                    // 403 = token 过期/失效：强制刷新后重试一次
                                                    token = tokenFor(server, forceRefresh = true)
                                                    withTimeout(CHUNK_TIMEOUT_MS) {
                                                        cdn.downloadDepotChunk(
                                                            WORKSHOP_DEPOT_ID, chunk, server, encrypted, null,
                                                            null,
                                                            token.trimStart('?').ifEmpty { null }
                                                        )
                                                    }
                                                } else {
                                                    throw firstErr
                                                }
                                            }
                                            ok = true
                                            val nowOk = System.currentTimeMillis()
                                            val elapsedMs = (nowOk - startMs).coerceAtLeast(1L)
                                            val bps = chunk.compressedLength.toLong() * 1000L / elapsedMs
                                            val st = statsOf(host)
                                            val ewma = st.ewmaBps.updateAndGet { prev ->
                                                if (prev < 0L) bps else (prev * 3L + bps) / 4L
                                            }
                                            if (chunk.compressedLength >= 262_144 && elapsedMs >= 40L) {
                                                while (true) {
                                                    val curBest = bestEwmaBps.get()
                                                    if (ewma <= curBest || bestEwmaBps.compareAndSet(curBest, ewma)) break
                                                }
                                                val best = bestEwmaBps.get()
                                                if (orderedServers.size > 1 && elapsedMs >= 2_500L && best >= 2_000_000L && ewma < best / 4L) {
                                                    st.cooldownUntilMs.set(nowOk + 30_000L)
                                                    Log.w(TAG, "quarantine slow host $host for 30s (ewma=${ewma / 1024}KB/s, best=${best / 1024}KB/s)")
                                                }
                                            }
                                            if (bps >= 1_000_000L) {
                                                st.penalty.updateAndGet { if (it > 0) it - 1 else 0 }
                                            }
                                            break
                                        } catch (t: Throwable) {
                                            if (t !is TimeoutCancellationException && t is kotlinx.coroutines.CancellationException) {
                                                checkCancelled(itemId)
                                                throw t
                                            }
                                            err = t
                                            val nowErr = System.currentTimeMillis()
                                            val st = statsOf(host)
                                            val msg = t.message.orEmpty()
                                            val isHard = t is TimeoutCancellationException ||
                                                msg.contains("504") || msg.contains("502") || msg.contains("503") ||
                                                msg.contains("500") || msg.contains("timeout", ignoreCase = true)
                                            if (isHard) {
                                                val hard = st.hardErrors.incrementAndGet()
                                                val singlePrimaryTier0 = isTier0(server) && tier0Servers.size == 1
                                                if (singlePrimaryTier0 && hard < 4) {
                                                    st.penalty.incrementAndGet()
                                                    Log.w(
                                                        TAG,
                                                        "tier0 transient hard error ($hard/4) on $host, keep tier0 active: " +
                                                            if (t is TimeoutCancellationException) "timeout" else msg
                                                    )
                                                } else {
                                                    st.penalty.addAndGet(2)
                                                    val cdMs = minOf(60_000L, 12_000L * hard)
                                                    st.cooldownUntilMs.set(nowErr + cdMs)
                                                    Log.w(
                                                        TAG,
                                                        "quarantine 5xx/timeout host $host for ${cdMs / 1000}s (hard=$hard): " +
                                                            if (t is TimeoutCancellationException) "timeout" else msg
                                                    )
                                                }
                                            } else {
                                                val pen = st.penalty.incrementAndGet()
                                                if (pen >= NODE_BAD_THRESHOLD) {
                                                    st.cooldownUntilMs.set(nowErr + 15_000L)
                                                }
                                                Log.w(TAG, "chunk from $host failed (node ${idx + 1}/${nodes.size}, pen=$pen): $msg")
                                            }
                                            if (idx + 1 < nodes.size) {
                                                delay(80L)
                                            }
                                        }
                                        idx++
                                    }
                                    if (!ok) throw IllegalStateException("下载分块失败：${err?.message}")
                                    queue.send(FetchedChunk(chunk, encrypted))
                                }
                            }
                        }

                        // 所有生产者完成后关闭队列，通知消费者收尾退出
                        launch {
                            producers.forEach { it.join() }
                            queue.close()
                        }

                        // 阶段 2（消费者）：固定 8 线程专用池做硬件 AES 解密 + 解压 + FileChannel 定位写，原地复用缓冲
                        val consumers = (0 until decompressWorkers).map {
                            launch(workerDispatcher) {
                                val processor = FastChunkProcessor(depotKey)
                                var plain = ByteArray(0)
                                for (fetched in queue) {
                                    checkCancelled(itemId)
                                    val chunk = fetched.chunk
                                    if (plain.size != chunk.uncompressedLength) {
                                        plain = ByteArray(chunk.uncompressedLength)
                                    }
                                    val written = processor.process(chunk, fetched.encrypted, plain)
                                    val buf = ByteBuffer.wrap(plain, 0, written)
                                    var pos = chunk.offset
                                    while (buf.hasRemaining()) {
                                        pos += channel.write(buf, pos)
                                    }
                                    journal.record(file.fileName, chunk.offset, written)
                                    val total = receivedCounter.addAndGet(written.toLong())
                                    val now = System.currentTimeMillis()
                                    val lastAt = lastEmitAt.get()
                                    if (now - lastAt >= EMIT_INTERVAL_MS &&
                                        lastEmitAt.compareAndSet(lastAt, now)
                                    ) {
                                        val speed = meter.sample(total, now)
                                        val phase = if (speed > 0.0) {
                                            String.format(Locale.US, "下载中 %.1f MB/s", speed)
                                        } else {
                                            "下载中"
                                        }
                                        emit(Progress(itemId, total, totalForTask, phase))
                                    }
                                }
                            }
                        }
                        consumers.forEach { it.join() }
                    }
                }
                verifyOrRepair(file, outFile, journal)
            }

            journal.flush()
            val received = receivedCounter.get()
            emit(Progress(itemId, received, totalBytes, "保存到 Download…"))
            val target = copyToDownloads(context, itemId, staging)
            staging.deleteRecursively()
            Log.i(TAG, "saved to $target")
            logFile(context, "done item=$itemId bytes=$received target=$target")
            emit(Progress(itemId, received, totalBytes, "已保存：$target", done = true))
        } finally {
            runCatching { journal.close() }
        }
    }
    /** 回读 .done_chunks：只有磁盘上确实存在且长度覆盖该块的记录才算已完成。 */
    private fun loadDoneChunks(staging: File, log: File): MutableSet<String> {
        val out = HashSet<String>()
        if (!log.exists()) return out
        runCatching {
            log.forEachLine { line ->
                if (line.isNotBlank()) {
                    val parts = line.split('|')
                    if (parts.size >= 3) {
                        val name = parts[0]
                        val offset = parts[1].toLongOrNull()
                        val len = parts[2].toLongOrNull()
                        if (offset != null && len != null && len > 0L) {
                            val outFile = File(staging, sanitizePath(name))
                            if (outFile.exists() && outFile.length() >= offset + len) {
                                out.add(name + "|" + offset)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * 校验单个文件：超出长度的尾巴截断；长度或 SHA-1 不符时只作废该文件的完成记录
     * （不删其他正常文件的分块），抛出明确提示交给外层重试。
     */
    private fun verifyOrRepair(file: FileData, outFile: File, journal: ChunkJournal) {
        if (!outFile.exists()) {
            journal.removeFile(file.fileName)
            throw IllegalStateException("文件缺失，将重新下载：${file.fileName}")
        }
        runCatching {
            FileChannel.open(outFile.toPath(), StandardOpenOption.WRITE).use { ch ->
                if (ch.size() > file.totalSize) {
                    ch.truncate(file.totalSize)
                    ch.force(false)
                }
            }
        }
        if (outFile.length() != file.totalSize) {
            journal.removeFile(file.fileName)
            throw IllegalStateException(
                "文件大小不符（${outFile.length()}/${file.totalSize}），将重新下载：${file.fileName}"
            )
        }
        val expected = file.fileHash ?: return
        if (expected.isEmpty()) return
        journal.flush()
        if (!sha1Of(outFile).contentEquals(expected)) {
            // 只删该文件对应的记录，其他正常文件的进度保留
            journal.removeFile(file.fileName)
            throw IllegalStateException("文件校验失败（数据损坏），将重新下载：${file.fileName}")
        }
    }

    private fun sha1Of(file: File): ByteArray {
        val md = FastChunkProcessor.newSha1Digest()
        java.io.FileInputStream(file).use { ins ->
            val buf = ByteArray(1 shl 18)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    private class SpeedMeter {
        private val times = ArrayList<Long>(24)
        private val bytes = ArrayList<Long>(24)
        private var smoothed = -1.0

        @Synchronized
        fun sample(totalBytes: Long, now: Long): Double {
            times.add(now)
            bytes.add(totalBytes)
            while (times.size > 2 && now - times[0] > 3000L) {
                times.removeAt(0)
                bytes.removeAt(0)
            }
            val dtMs = now - times[0]
            if (dtMs < 500L) return smoothed
            val delta = totalBytes - bytes[0]
            if (delta <= 0L) return if (smoothed > 0.0) smoothed * 0.7 else -1.0
            val instant = delta / (dtMs / 1000.0) / 1048576.0
            smoothed = if (smoothed <= 0.0) instant else smoothed * 0.6 + instant * 0.4
            return smoothed
        }
    }

    /**
     * 已完成分块日志：每行「文件名|offset|len」，逐块立即 flush。
     * 原实现攒满 200 块才落盘，进程被系统杀掉时最多丢失 200 个分块的进度
     * （用户看到的就是"下了一晚上，重启后从 0 开始"）。
     */
    private class ChunkJournal(file: File) : Closeable {
        private val logFile: File = file
        private var writer: BufferedWriter = openWriter()

        private fun openWriter(): BufferedWriter =
            BufferedWriter(OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8))

        @Synchronized
        fun record(fileName: String, offset: Long, len: Int) {
            try {
                writer.write(fileName)
                writer.write("|")
                writer.write(offset.toString())
                writer.write("|")
                writer.write(len.toString())
                writer.write("\n")
                writer.flush()
            } catch (_: Throwable) {
            }
        }

        @Synchronized
        fun flush() {
            try {
                writer.flush()
            } catch (_: Throwable) {
            }
        }

        /** 只移除某个文件的完成记录（校验失败时用），其他文件不受影响。 */
        @Synchronized
        fun removeFile(fileName: String) {
            runCatching { writer.flush() }
            runCatching {
                val prefix = "$fileName|"
                val kept = if (logFile.exists()) {
                    logFile.readLines().filterNot { it.startsWith(prefix) }
                } else {
                    emptyList()
                }
                runCatching { writer.close() }
                logFile.writeText(kept.joinToString("") { it + "\n" })
            }
            runCatching { writer = openWriter() }
        }

        @Synchronized
        override fun close() {
            runCatching {
                writer.flush()
                writer.close()
            }
        }
    }

    // ---------------- 杂项 ----------------

    private fun logFile(context: Context, msg: String) {
        runCatching {
            val f = File(context.getExternalFilesDir(null), "weworkshop.log")
            f.appendText(
                "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + msg + "\n"
            )
        }
    }

    /** 记录未捕获异常（App 闪退时写进 weworkshop.log，供下次排查）。 */
    @JvmStatic
    fun logCrash(context: Context?, t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val msg = "CRASH: " + sw.toString().take(6000)
        Log.e(TAG, msg)
        if (context != null) logFile(context, msg)
    }

    /**
     * 分块循环里的中断检查。
     *
 * 必须同时响应「暂停」：生产者的 withTimeout + downloadDepotChunk 与消费者的 FileChannel
     * 写入都是阻塞调用、不是挂起点，仅靠 Job.cancel() 要等到下一个挂起点才生效，
     * 期间整个分组会继续下载。这里主动抛 CancellationException，让暂停立即生效
     * （外层 downloadAsync 会依据 pausedItems 判定为"暂停"而非"取消"，保留磁盘分块）。
     */
    private fun checkCancelled(itemId: Long) {
        if (cancelledItems.contains(itemId)) throw kotlinx.coroutines.CancellationException(CANCEL_MSG)
        if (pausedItems.contains(itemId)) throw kotlinx.coroutines.CancellationException("已暂停")
    }

    private fun sanitizePath(name: String): String {
        val parts = name.replace('\\', '/').split('/')
        return parts.filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { p ->
                p.map { ch ->
                    if (ch in "\\:*?\"<>|" || ch.code < 32) '_' else ch
                }.joinToString("")
            }
    }

    /** 复制到 Download/Wallpaper WE/ 根目录（视频直接平铺，不再建编号子文件夹） */
    private fun copyToDownloads(context: Context, itemId: Long, staging: File): String {
        val baseRel = "Download/Wallpaper WE"
        val files = staging.walkTopDown()
            .filter { it.isFile && it.name != META_FILE && it.name != DONE_CHUNKS }
            .toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            files.forEach { f ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, guessMime(f.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, baseRel)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@forEach
                resolver.openOutputStream(uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Wallpaper WE"
            )
            dir.mkdirs()
            files.forEach { f -> f.copyTo(File(dir, f.name), overwrite = true) }
        }
        return "/Download/Wallpaper WE/${files.firstOrNull()?.name ?: ""}"
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pkg" -> "application/octet-stream"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "tex" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
}