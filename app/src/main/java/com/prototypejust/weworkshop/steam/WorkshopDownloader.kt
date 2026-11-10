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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Wallpaper Engine 创意工坊源文件下载器。
 *
 * 流程（参考 Workshop-Native）：
 *  1. getDepotDecryptionKey(431960, 431960) 拿 depot 密钥
 *  2. getServersForSteamPipe 拿 HTTPS CDN 节点
 *  3. getManifestRequestCode(431960, 431960, manifestId, "public")
 *  4. downloadManifestFuture → manifest（按文件名写入磁盘）
 *  5. 逐 chunk downloadDepotChunkFuture + DepotChunk.process 解密解压，按 offset 写文件
 *  6. 复制到 Download/Wallpaper WE/<itemId>/
 */
object WorkshopDownloader {
    private const val TAG = "WEDownload"
    const val APP_ID = 431960
    const val WORKSHOP_DEPOT_ID = 431960
    private const val CDN_CANDIDATE_LIMIT = 20

    /** 下载失败自动重试：最多 3 次，间隔 3s / 6s / 12s（断点续传会从已下载处继续） */
    private const val AUTO_RETRY_MAX = 3
    private const val AUTO_RETRY_BASE_DELAY_MS = 3000L
    private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "mkv", "avi", "m4v", "wmv")

    data class Progress(
        val itemId: Long,
        val received: Long,
        val total: Long,
        val phase: String,
        val done: Boolean = false,
        val error: String? = null
    )

    fun interface ProgressListener {
        fun onProgress(itemId: Long, received: Long, total: Long, phase: String, done: Boolean, error: String?)
    }

    @Volatile private var listener: ProgressListener? = null

    @JvmStatic
    fun setProgressListener(l: ProgressListener?) { listener = l }

    // 并发下载：按 itemId 分别记录标题/缩略图/取消状态，避免多任务互相覆盖
    private val titles = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val images = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val activeItems = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val manifests = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cancelledItems = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lastProgress = java.util.concurrent.ConcurrentHashMap<Long, Progress>()
    private val lastPersistAt = ConcurrentHashMap<Long, Long>()

    /** 当前是否有该条目的下载在跑（供界面判断，避免重复启动） */
    @JvmStatic
    fun isActive(itemId: Long): Boolean = activeItems.contains(itemId)

    /** 返回某条目最近一次进度（JSON），用于界面重建/重进详情页时恢复进度条 */
    @JvmStatic
    fun progressJson(itemId: Long): String? {
        val p = lastProgress[itemId] ?: return null
        val err = p.error
        return buildString {
            append("{\"itemId\":").append(p.itemId)
            append(",\"received\":").append(p.received)
            append(",\"total\":").append(p.total)
            append(",\"phase\":\"").append(jsonEscape(p.phase)).append('"')
            append(",\"done\":").append(p.done)
            append(",\"error\":").append(if (err == null) "null" else "\"" + jsonEscape(err) + "\"")
            append('}')
        }
    }

    /** 所有活跃下载的最新进度（JSON map），供 App 回到前台/打开列表时同步 */
    @JvmStatic
    fun activeProgressJson(): String {
        val sb = StringBuilder("{")
        var first = true
        for (id in activeItems) {
            val p = lastProgress[id] ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(id).append("\":")
            sb.append("{\"itemId\":").append(p.itemId)
            sb.append(",\"received\":").append(p.received)
            sb.append(",\"total\":").append(p.total)
            sb.append(",\"phase\":\"").append(jsonEscape(p.phase)).append('"')
            sb.append(",\"done\":").append(p.done)
            val err = p.error
            sb.append(",\"error\":").append(if (err == null) "null" else "\"" + jsonEscape(err) + "\"")
            sb.append('}')
        }
        return sb.append('}').toString()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    @JvmStatic
    fun cancel(itemId: Long) {
        cancelledItems.add(itemId)
        // 立即把「已取消」推到界面和下载记录，不必等阻塞中的网络请求返回
        val lp = lastProgress[itemId]
        emit(Progress(itemId, lp?.received ?: 0L, lp?.total ?: 0L, "已取消", error = "已取消"))
        jobs[itemId]?.cancel()
    }

    @JvmStatic
    fun cancel() {
        cancelledItems.addAll(activeItems)
        activeItems.toList().forEach { cancel(it) }
    }

    fun isCancelled(itemId: Long): Boolean = cancelledItems.contains(itemId)

    private fun emit(p: Progress, persist: Boolean = true) {
        lastProgress[p.itemId] = p
        val terminal = p.done || p.error != null
        val now = System.currentTimeMillis()
        val shouldPersist = persist && (terminal || now - (lastPersistAt[p.itemId] ?: 0L) >= 1500L)
        if (shouldPersist) {
            lastPersistAt[p.itemId] = now
            val status = when {
                p.error == "已取消" -> "canceled"
                p.error != null -> "failed"
                p.done -> "done"
                else -> "downloading"
            }
            runCatching {
                DownloadStore.upsert(
                    p.itemId,
                    titles[p.itemId] ?: "",
                    images[p.itemId] ?: "",
                    p.total, p.received, status, manifests[p.itemId] ?: 0L
                )
            }
        }
        listener?.onProgress(p.itemId, p.received, p.total, p.phase, p.done, p.error)
    }

    private fun logFile(context: Context, msg: String) {
        runCatching {
            val f = File(context.getExternalFilesDir(null), "weworkshop.log")
            f.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg\n")
        }
    }

    @JvmStatic
    fun downloadAsync(context: Context, itemId: Long, manifestId: Long, title: String?, img: String?) {
        DownloadStore.init(context)
        if (!title.isNullOrEmpty()) titles[itemId] = title
        if (!img.isNullOrEmpty()) images[itemId] = img
        // 同一任务重复点击时不重复启动，避免并发写同一文件
        if (!activeItems.add(itemId)) {
            lastProgress[itemId]?.let { emit(it, persist = false) }
            return
        }
        cancelledItems.remove(itemId)
        manifests[itemId] = manifestId
        // 下载期间启用前台服务，避免切后台后进程被系统回收
        DownloadService.start(context.applicationContext)
        val job = CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            try {
                while (true) {
                    try {
                        download(context.applicationContext, itemId, manifestId)
                        return@launch
                    } catch (t: Throwable) {
                        val cancelled = t is kotlinx.coroutines.CancellationException || isCancelled(itemId)
                        Log.e(TAG, "download failed for $itemId (attempt ${attempt + 1})", t)
                        logFile(context, "FAIL item=$itemId manifest=$manifestId attempt=${attempt + 1}: ${t.message}")
                        if (cancelled) {
                            val cur = lastProgress[itemId]
                            emit(Progress(itemId, cur?.received ?: 0L, cur?.total ?: 0L, "已取消", error = "已取消"))
                            return@launch
                        }
                        attempt++
                        val lp = lastProgress[itemId]
                        if (attempt > AUTO_RETRY_MAX) {
                            emit(
                                Progress(
                                    itemId, lp?.received ?: 0L, lp?.total ?: 0L, "失败",
                                    error = (t.message ?: "下载失败") + "（已自动重试 $AUTO_RETRY_MAX 次）"
                                )
                            )
                            return@launch
                        }
                        val delayMs = AUTO_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                        val sec = (delayMs / 1000L).coerceAtLeast(1L)
                        logFile(context, "auto retry $attempt/$AUTO_RETRY_MAX for item=$itemId in ${delayMs}ms")
                        emit(
                            Progress(
                                itemId, lp?.received ?: 0L, lp?.total ?: 0L,
                                "自动重试 $attempt/$AUTO_RETRY_MAX（${sec} 秒后）"
                            )
                        )
                        delay(delayMs)
                        if (isCancelled(itemId)) {
                            val cur = lastProgress[itemId]
                            emit(Progress(itemId, cur?.received ?: 0L, cur?.total ?: 0L, "已取消", error = "已取消"))
                            return@launch
                        }
                    }
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

    /**
     * 进程被杀（后台回收）后重新进入 App 时调用：
     * 找 DownloadStore 里仍标记为「下载中」、但当前没有协程在跑的任务，重新起下载（走断点续传）。
     */
    @JvmStatic
    fun resumeInterrupted(context: Context) {
        val ctx = context.applicationContext
        try {
            DownloadStore.init(ctx)
            val arr = DownloadStore.list()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("status") != "downloading") continue
                val id = o.optLong("id", 0L)
                if (id <= 0L || isActive(id)) continue
                val manifest = o.optString("manifest", "").toLongOrNull() ?: 0L
                if (manifest <= 0L) continue
                logFile(ctx, "resume interrupted item=$id manifest=$manifest")
                downloadAsync(ctx, id, manifest, o.optString("title"), o.optString("img"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resumeInterrupted failed", t)
        }
    }

    private suspend fun download(context: Context, itemId: Long, manifestId: Long) {
        val client = SteamService.steamClient()
            ?: throw IllegalStateException("请先登录 Steam")
        val apps = SteamService.appsOrThrow()
        val content = SteamService.contentOrThrow()
        val scope = CoroutineScope(Dispatchers.IO)

        emit(Progress(itemId, 0, 0, "获取 depot 密钥…"))
        val depotKeyCb = apps.getDepotDecryptionKey(WORKSHOP_DEPOT_ID, APP_ID)
            .toFuture()
            .get(60, TimeUnit.SECONDS)
        if (depotKeyCb.result != EResult.OK) {
            throw IllegalStateException("获取 depot 密钥失败：${depotKeyCb.result}")
        }
        val depotKey = depotKeyCb.depotKey
            ?: throw IllegalStateException("depot 密钥为空")

        emit(Progress(itemId, 0, 0, "获取 CDN 节点…"))
        val servers = content.getServersForSteamPipe(null, CDN_CANDIDATE_LIMIT, scope).await()
        Log.i(TAG, "cdn servers raw: " + servers.joinToString { "${it.protocol}://${it.host}:${it.port}" })
        logFile(context, "cdn raw: " + servers.joinToString { "${it.protocol}://${it.host}:${it.port}" })
        val httpsServers = servers
            .filter { it.protocol == Server.ConnectionProtocol.HTTPS }
            .sortedBy { it.load }
        // 走代理/VPN 时 Steam 可能只下发明文 HTTP 节点（HTTPS 节点为空），
        // App 已允许 cleartext，此时回退到 HTTP 节点。
        val candidateServers = if (httpsServers.isNotEmpty()) httpsServers else servers
            .filter { it.protocol == Server.ConnectionProtocol.HTTP }
            .sortedBy { it.load }
        if (candidateServers.isEmpty()) {
            throw IllegalStateException("没有可用的 CDN 节点，请检查手机代理/VPN")
        }
        val useHttp = httpsServers.isEmpty()
        Log.i(TAG, "cdn servers: ${candidateServers.take(3)} (httpFallback=$useHttp)")

        emit(Progress(itemId, 0, 0, "申请下载授权…"))
        val requestCode = content.getManifestRequestCode(
            WORKSHOP_DEPOT_ID, APP_ID, manifestId, "public", null, scope
        ).await()
        Log.i(TAG, "manifest request code: $requestCode (manifestId=$manifestId)")
        if (requestCode == 0L) {
            throw IllegalStateException("申请下载授权失败（manifest 请求码为空）")
        }

        val cdn = Client(client)
        val diag = StringBuilder()
        diag.append("节点=").append(candidateServers.size).append("(http=").append(useHttp).append(");")
        diag.append("授权码=").append(requestCode).append(';')

        // CDN 的 manifest / chunk 请求需要 per-host 的 auth token，缺了会 403。
        val tokenCache = ConcurrentHashMap<String, String>()
        suspend fun tokenFor(server: Server): String {
            val key = server.host ?: ""
            if (tokenCache.containsKey(key)) return tokenCache[key] ?: ""
            val tok = try {
                content.getCDNAuthToken(APP_ID, WORKSHOP_DEPOT_ID, server.host ?: "", scope)
                    .await()
                    .takeIf { it.result == EResult.OK }
                    ?.token
            } catch (t: Throwable) {
                Log.w(TAG, "cdn auth token for ${server.host} failed: ${t.message}")
                null
            }.orEmpty()
            Log.i(TAG, "cdn auth token for $key: ${if (tok.isEmpty()) "EMPTY" else "ok"}")
            diag.append("token($key)=").append(if (tok.isEmpty()) "EMPTY" else "ok").append(';')
            tokenCache[key] = tok
            return tok
        }

        emit(Progress(itemId, 0, 0, "下载 manifest…"))
        var manifest: DepotManifest? = null
        var lastErr: Throwable? = null
        for (server in candidateServers.take(6)) {
            try {
                val token = tokenFor(server)
                manifest = cdn.downloadManifestFuture(
                    WORKSHOP_DEPOT_ID, manifestId, requestCode, server, depotKey, null,
                    token.trimStart('?').ifEmpty { null }
                ).get(30, TimeUnit.SECONDS)
                break
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "manifest from ${server.host} failed: ${t.message}")
                diag.append("manifest(").append(server.host).append(")=").append(t.message?.take(120)).append(';')
            }
        }
        val m = manifest ?: throw IllegalStateException("下载 manifest 失败：${lastErr?.message}\n诊断：" + diag)
        if (m.filenamesEncrypted) {
            if (!m.decryptFilenames(depotKey)) throw IllegalStateException("manifest 文件名解密失败")
        }

        val allFiles = m.files.filter { !it.flags.contains(EDepotFileFlag.Directory) && !it.flags.contains(EDepotFileFlag.Symlink) }
        // 只保留视频本体：manifest 里若存在视频文件，就只下载这些，忽略 pkg/预览图/json 等
        val videoFiles = allFiles.filter { f ->
            val n = f.fileName.lowercase()
            VIDEO_EXTS.any { ext -> n.endsWith(".$ext") }
        }
        val filesToDownload = if (videoFiles.isNotEmpty()) videoFiles else allFiles
        val totalBytes = filesToDownload.sumOf { it.totalSize }
        val namesPreview = filesToDownload.take(12).joinToString(",") { it.fileName }
        val logLine = "manifest ok: all=${allFiles.size} videos=${videoFiles.size} pick=${filesToDownload.size} total=$totalBytes names=[$namesPreview]"
        Log.i(TAG, logLine)
        logFile(context, logLine)

        // 断点续传：保留上次未完成的 staging（同一 manifest 指纹才复用）
        val staging = File(context.cacheDir, "workshop/$itemId")
        val metaFile = File(staging, ".meta")
        val chunksLog = File(staging, ".chunks")
        val fingerprint = manifestId.toString() + "|" + filesToDownload.joinToString("|") { it.fileName + ":" + it.totalSize }
        val resumeExisting = staging.exists() && metaFile.exists() &&
            runCatching { metaFile.readText() }.getOrNull() == fingerprint
        if (!resumeExisting) staging.deleteRecursively()
        staging.mkdirs()
        metaFile.writeText(fingerprint)
        // 不能只看文件长度判断续传：多路并行时文件尾部可能先写满，长度变大但中间是空洞，
        // 只按长度跳过会把空洞当成已完成，播出来就是花屏。所以逐 chunk 记录完成情况。
        val done = java.util.Collections.synchronizedSet(HashSet<String>())
        if (resumeExisting && chunksLog.exists()) {
            runCatching {
                chunksLog.forEachLine { line -> if (line.isNotBlank()) done.add(line.trim()) }
            }
        }
        val journal = ChunkJournal(chunksLog)
        val existingBytes = filesToDownload.sumOf { f ->
            f.chunks.filter { done.contains(f.fileName + "|" + it.offset) }
                .sumOf { it.uncompressedLength.toLong() }
        }
        if (existingBytes > 0L) {
            val rl = "resume: existing=$existingBytes/$totalBytes for $itemId"
            Log.i(TAG, rl); logFile(context, rl)
        }

        var received = existingBytes
        emit(Progress(itemId, received, totalBytes, if (existingBytes > 0) "继续下载" else "开始下载"))

        // 每个文件内的 chunk 分 4 路并行下载（Steam CDN 支持多连接，单连接容易被限速/等 RTT）
        val parallelism = 4
        val lastEmitAt = AtomicLong(0L)
        var downloadedNow = received
        for (file in filesToDownload) {
            checkCancelled(itemId)
            val outFile = File(staging, sanitizePath(file.fileName))
            outFile.parentFile?.mkdirs()

            val fileExists = outFile.exists() && outFile.length() > 0L
            val pending = file.chunks.sortedBy { it.offset }.filterNot { chunk ->
                // 只跳过明确记录为已完成的 chunk（续传）；文件不存在时全部重下
                fileExists && done.contains(file.fileName + "|" + chunk.offset)
            }
            if (pending.isEmpty()) {
                verifyOrThrow(file, outFile, staging, journal)
                continue
            }

            val counter = AtomicLong(downloadedNow)
            val speedStartMs = System.currentTimeMillis()
            val speedStartBytes = downloadedNow
            coroutineScope {
                val groups = List(parallelism) { ArrayList<ChunkData>() }
                pending.forEachIndexed { i, c -> groups[i % parallelism].add(c) }
                groups.filter { it.isNotEmpty() }.map { group ->
                    launch(Dispatchers.IO) {
                        // 每组用自己的 RandomAccessFile，写不同 offset 区域，互不干扰
                        RandomAccessFile(outFile, "rw").use { raf ->
                            for (chunk in group) {
                                checkCancelled(itemId)
                                val encrypted = ByteArray(chunk.compressedLength)
                                val plain = ByteArray(chunk.uncompressedLength)
                                var ok = false
                                var err: Throwable? = null
                                for (server in candidateServers.take(4)) {
                                    try {
                                        val token = tokenFor(server)
                                        cdn.downloadDepotChunkFuture(
                                            WORKSHOP_DEPOT_ID, chunk, server, encrypted, null, null,
                                            token.trimStart('?').ifEmpty { null }
                                        ).get(30, TimeUnit.SECONDS)
                                        ok = true
                                        break
                                    } catch (t: Throwable) {
                                        err = t
                                        Log.w(TAG, "chunk ${chunk.chunkID?.size ?: 0} from ${server.host} failed: ${t.message}")
                                    }
                                }
                                if (!ok) throw IllegalStateException("下载分块失败：${err?.message}")
                                val written = DepotChunk.process(chunk, encrypted, plain, depotKey)
                                raf.seek(chunk.offset)
                                raf.write(plain, 0, written)
                                journal.add(file.fileName + "|" + chunk.offset)
                                val total = counter.addAndGet(written.toLong())
                                val now = System.currentTimeMillis()
                                // 节流：最多每 250ms 刷新一次界面 & 落盘，避免频繁回调造成卡顿
                                if (now - lastEmitAt.get() >= 250L) {
                                    lastEmitAt.set(now)
                                    val elapsed = (now - speedStartMs) / 1000.0
                                    val speed = if (elapsed > 0.8) (total - speedStartBytes) / elapsed / 1048576.0 else -1.0
                                    val phase = if (speed > 0) String.format(Locale.US, "下载中 %.1f MB/s", speed) else "下载中"
                                    emit(Progress(itemId, total, totalBytes, phase), persist = false)
                                }
                            }
                        }
                    }
                }.forEach { it.join() }
            }
            verifyOrThrow(file, outFile, staging, journal)
            downloadedNow = counter.get()
        }
        received = downloadedNow

        journal.flush()
        emit(Progress(itemId, received, totalBytes, "保存到 Download…"))
        val target = copyToDownloads(context, itemId, staging)
        staging.deleteRecursively()
        Log.i(TAG, "saved to $target")
        emit(Progress(itemId, received, totalBytes, "已保存：$target", done = true))
    }

    /** manifest 自带每个文件的 SHA-1；不匹配说明数据坏了，清掉 staging 重新下载。 */
    private fun verifyOrThrow(file: FileData, outFile: File, staging: File, journal: ChunkJournal) {
        if (outFile.length() != file.totalSize) {
            staging.deleteRecursively()
            throw IllegalStateException(
                "文件大小不符（${outFile.length()}/${file.totalSize}），将重新下载：${file.fileName}"
            )
        }
        val expected = file.fileHash ?: return
        if (expected.isEmpty()) return
        journal.flush()
        if (!sha1Of(outFile).contentEquals(expected)) {
            staging.deleteRecursively()
            throw IllegalStateException("文件校验失败（数据损坏），将重新下载：${file.fileName}")
        }
    }

    private fun sha1Of(file: File): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        java.io.FileInputStream(file).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    /** 已完成 chunk 的追加日志：每行「文件名|offset」，攒批落盘，丢失最多重下少量分块。 */
    private class ChunkJournal(private val file: File) {
        private val pending = ArrayList<String>()

        @Synchronized
        fun add(key: String) {
            pending.add(key)
            if (pending.size >= 200) flush()
        }

        @Synchronized
        fun flush() {
            if (pending.isEmpty()) return
            try {
                file.appendText(pending.joinToString("") { "$it\n" })
                pending.clear()
            } catch (_: Throwable) {
                pending.clear()
            }
        }
    }

    private fun checkCancelled(itemId: Long) {
        if (cancelledItems.contains(itemId)) throw kotlinx.coroutines.CancellationException("已取消")
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
        val files = staging.walkTopDown().filter { it.isFile && it.name != ".meta" && it.name != ".chunks" }.toList()
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
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
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

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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
