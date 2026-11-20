package com.prototypejust.weworkshop.steam

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.SteamAuthentication
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import `in`.dragonbra.javasteam.steam.webapi.SteamDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Steam 会话管理（javaSteam）。
 *
 * 登录流程：
 *  1. [loginAsync] 用户名 + 密码 → beginAuthSessionViaCredentials
 *  2. 如 Steam 要求 Guard 验证码，[onGuardRequired] 回调 UI，[submitGuardCode] 提交
 *  3. 得到 refreshToken 后用 [logOnWithToken] 登录，成功后 [isLoggedIn] = true
 *  4. 下次启动 [init] 用本地保存的 refreshToken 自动恢复
 */
object SteamService {
    private const val TAG = "WESteam"
    private const val LOGON_TIMEOUT_MS = 90_000L
    /** 单个候选 CM 节点的最长等待：12 秒连不上就换下一个候选，避免在死节点上傻等。 */
    private const val CONNECT_PER_ATTEMPT_MS = 12_000L
    /** 连接候选节点的最大尝试次数（每次由 SmartCMServerList 选一个不同节点）。 */
    private const val CONNECT_ATTEMPTS = 3

    /** 目录服务不可用时的保底 CM 节点（TCP 27017），域名可直接解析连接。 */
    private val FALLBACK_TCP_SERVERS = listOf(
        "ext1-sea1.steamserver.net:27017",
        "cm01-tyo1.cm.steampowered.com:27017",
        "cm01-sgp1.cm.steampowered.com:27017",
        "cm01-hkg1.cm.steampowered.com:27017",
        "ext1-ord1.steamserver.net:27017"
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var guardKeepAliveJob: Job? = null
    /** 会话恢复互斥：init() 可能被多次调用（Activity 重建、进程内重启），
     *  并发执行 restoreSession 会同时操作同一个 client，互相覆盖 logonCallback，
     *  导致"时好时坏"的随机登录失败。 */
    @Volatile private var restoring = false

    @Volatile private var client: SteamClient? = null
    @Volatile private var callbackManager: CallbackManager? = null
    @Volatile private var user: SteamUser? = null
    @Volatile private var apps: SteamApps? = null
    @Volatile private var content: SteamContent? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var refreshToken: String = ""
    @Volatile private var reconnectJob: Job? = null
    /** 会话恢复任务句柄：Activity 重建会重复调用 init()，用它保证同一时刻只有一个 restoreSession。 */
    @Volatile private var restoreJob: Job? = null
    /** CM 连接断开计数：connectAndWait() 用它判断当前候选节点是否已失败，不必傻等满 12 秒。 */
    private val disconnectSeq = AtomicInteger(0)
    /** 已为其准备过候选节点的 SteamClient 实例。
     *  不能用 servers.getAllEndPoints() 当"是否已准备"的判据：该方法字节码第一步就是
     *  startFetchingServers() -> resolveServerList(false)，会隐式读盘并返回非空列表。
     *  scheduleReconnect() 重建 client 时必须同步清空本字段。 */
    @Volatile private var preparedClient: SteamClient? = null

    @Volatile var isLoggedIn = false
        private set
    @Volatile var accountName = ""
        private set
    @Volatile var steamId64 = 0L
        private set
    @Volatile var busy = false
        private set
    @Volatile var status = "未登录"
        private set
    @Volatile var error = ""
        private set
    @Volatile var guardNeeded = false
        private set
    @Volatile var guardType = ""
        private set
    @Volatile var guardEmail = ""
        private set

    @Volatile private var stateListener: Runnable? = null
    @Volatile private var guardListener: Runnable? = null

    @Volatile private var logonCallback: LoggedOnCallback? = null
    @Volatile private var guardFuture: CompletableFuture<String>? = null

    // ---------------- 生命周期 ----------------

    /** 应用启动时调用；若本地有 refreshToken 会后台自动恢复会话。 */
    @JvmStatic
    fun setStateListener(listener: Runnable?) { stateListener = listener }

    @JvmStatic
    fun setGuardListener(listener: Runnable?) { guardListener = listener }

    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) {
            // 已经是第二次 init（Activity 重建 / 进程内重启）。原逻辑直接 return，
            // 导致此时若 client 已断开、isLoggedIn 已为 false，界面会一直显示未登录、
            // 且不会触发任何恢复动作，用户看到的就是"连不上 Steam"。
            // 这里补一次恢复：有 token 但当前未登录/未连接时重新建立会话。
            if (refreshToken.isNotBlank() && accountName.isNotBlank() &&
                (!isLoggedIn || client?.isConnected != true) &&
                restoreJob?.isActive != true && !busy
            ) {
                // 关键：在调用线程上同步置 restoring 并先推一次状态，前端启动瞬间
                // 就能看到"正在恢复会话"，而不是空白或"未登录"。协程内部不再重复
                // 判断 restoring（否则会把自己挡掉）。
                restoring = true
                error = ""
                status = "正在恢复 Steam 会话…"
                notifyState()
                restoreJob = scope.launch {
                    try {
                        restoreSession(accountName, refreshToken)
                    } catch (t: Throwable) {
                        Log.w(TAG, "re-init restore failed", t)
                        fileLog("re-init restore failed: ${t.javaClass.simpleName}: ${t.message}")
                        isLoggedIn = false
                        status = "正在重连 Steam…"
                    } finally {
                        restoring = false
                        notifyState()
                        if (!isLoggedIn && refreshToken.isNotBlank() && accountName.isNotBlank()) {
                            scheduleReconnect()
                        }
                    }
                }
            }
            return
        }
        val app = context.applicationContext
        appContext = app
        val sp = app.getSharedPreferences("steam_session", Context.MODE_PRIVATE)
        prefs = sp
        val token = sp.getString("refresh_token", "").orEmpty()
        val account = sp.getString("account_name", "").orEmpty()
        refreshToken = token
        if (token.isNotBlank() && account.isNotBlank()) {
            accountName = account
            // 关键：在 scope.launch 之前同步置位并推送状态，否则前端在协程被调度前
            // 看不到 restoring，会短暂显示"未登录"。
            restoring = true
            error = ""
            status = "正在恢复 Steam 会话…"
            notifyState()
            restoreJob = scope.launch {
                try {
                    restoreSession(account, token)
                } catch (t: Throwable) {
                    Log.w(TAG, "restore session failed", t)
                    fileLog("init restore failed: ${t.javaClass.simpleName}: ${t.message}")
                    isLoggedIn = false
                    status = "正在重连 Steam…"
                } finally {
                    restoring = false
                    notifyState()
                    if (!isLoggedIn && refreshToken.isNotBlank() && accountName.isNotBlank()) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    fun isAvailable(): Boolean = isLoggedIn && client?.isConnected == true

    internal fun steamClient(): SteamClient? = client
    internal fun appsOrThrow(): SteamApps = apps ?: throw IllegalStateException("Steam 正在连接，请稍后重试")
    internal fun contentOrThrow(): SteamContent = content ?: throw IllegalStateException("Steam 正在连接，请稍后重试")

    // ---------------- 登录 ----------------

    /** 供 Java/Bridge 调用的异步登录入口。 */
    @JvmStatic
    fun loginAsync(username: String, password: String) {
        scope.launch { login(username, password) }
    }

    /** 用户名密码登录（含 Guard 等待）。返回是否成功。 */
    suspend fun login(username: String, password: String): Boolean {
        if (busy) return false
        // 手动登录优先：先停掉后台的会话恢复/自动重连任务，
        // 避免它们与本流程同时操作同一个 client（互相覆盖 logonCallback、置空连接）。
        restoreJob?.cancel()
        restoreJob = null
        reconnectJob?.cancel()
        restoring = false
        busy = true
        error = ""
        guardNeeded = false
        try {
            withContext(Dispatchers.IO) {
                status = "正在连接 Steam…"
                notifyState()
                ensureClient()
                client?.disconnect()
                delay(400)
                connectAndWait()

                status = "正在验证账号…"
                notifyState()
                val details = AuthSessionDetails().apply {
                    this.username = username.trim()
                    this.password = password
                    this.persistentSession = true
                    this.deviceFriendlyName = "WE Workshop Android"
                    this.clientOSType = EOSType.AndroidUnknown
                    this.authenticator = GuardAuthenticator()
                }
                val authSession = client!!.authentication
                    .beginAuthSessionViaCredentials(details)
                    .get(60, TimeUnit.SECONDS)
                status = "等待 Steam 验证…"
                notifyState()
                val poll = authSession.pollingWaitForResult().get(600, TimeUnit.SECONDS)
                val token = poll.refreshToken.orEmpty()
                val account = poll.accountName?.takeIf { it.isNotBlank() } ?: username.trim()
                if (token.isBlank()) throw IllegalStateException("Steam 未返回登录令牌")

                status = "正在登录 Steam…"
                notifyState()
                logOnWithToken(account, token)
                accountName = account
                refreshToken = token
                prefs?.edit()?.putString("refresh_token", token)?.putString("account_name", account)?.apply()
                isLoggedIn = true
                status = "已登录：$account"
                notifyState()
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "login failed", t)
            fileLog("login failed: ${t.javaClass.simpleName}: ${t.message} | connected=${client?.isConnected} | guardNeeded=$guardNeeded | cause=${t.cause?.javaClass?.simpleName}:${t.cause?.message}")
            isLoggedIn = false
            error = friendlyError(t)
            status = error
            notifyState()
            return false
        } finally {
            busy = false
            // 登录流程结束（成功或失败）后才隐藏验证码输入框；
            // 提交验证码时不能提前把 guardNeeded 置 false，否则输入框会在
            // 用户提交后立刻消失，导致无法重试输入。
            guardNeeded = false
            guardFuture = null
            stopGuardKeepAlive()
            notifyState()
        }
    }

    private suspend fun restoreSession(account: String, token: String) {
        withContext(Dispatchers.IO) {
            ensureClient()
            connectAndWait()
            status = "正在恢复 Steam 会话…"
            notifyState()
            try {
                logOnWithToken(account, token)
            } catch (t: Throwable) {
                fileLog("restoreSession logon failed: ${t.javaClass.simpleName}: ${t.message} connected=${client?.isConnected}")
                throw t
            }
            isLoggedIn = true
            status = "已登录：$account"
            notifyState()
            fileLog("session restored: $account")
            Log.i(TAG, "session restored: $account")
        }
    }

    /** 网络切换/断线后自动用保存的 refreshToken 重连，避免要求用户重新输密码。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (refreshToken.isBlank() || accountName.isBlank()) return
        val job = scope.launch {
            try {
                var attempt = 0
                while (isActive && attempt < 10) {
                    attempt++
                    if (busy) { delay(1_000L); continue }  // 用户正在手动登录时让路
                    status = "网络断开，正在重连 Steam…（第 $attempt 次）"
                    notifyState()
                    delay(minOf(20_000L, 1_000L * attempt))
                    try {
                        if (client?.isConnected != true) {
                            ensureClient()
                            connectAndWait()
                        }
                        logOnWithToken(accountName, refreshToken)
                        isLoggedIn = true
                        error = ""
                        status = "已登录：$accountName"
                        notifyState()
                        Log.i(TAG, "reconnected on attempt $attempt")
                        fileLog("reconnected on attempt $attempt")
                        return@launch
                    } catch (t: Throwable) {
                        Log.w(TAG, "reconnect attempt $attempt failed: ${t.message}")
                        // 关键：重建 client。ensureClient() 遇到已有实例会直接返回，
                        // 若该实例连接已死，后续 connectAndWait() 永远等不到 isConnected，
                        // 于是重连 10 次全部失败，登录态被判为丢失，列表回退到很慢的
                        // steamcommunity.com 网页抓取。
                        runCatching { client?.disconnect() }
                        client = null
                        preparedClient = null
                        callbackManager = null
                        user = null
                        apps = null
                        content = null
                        delay(500)
                    }
                }
                isLoggedIn = false
                status = "Steam 连接已断开，请检查网络后重试"
                notifyState()
            } finally {
                // 只在"当前重连任务仍然是自己"时置空，避免把后来新建的任务清掉；
                // 无论成功/失败/被取消都要刷新一次前端，让 reconnecting 及时变 false。
                if (reconnectJob === coroutineContext[Job]) reconnectJob = null
                notifyState()
            }
        }
        reconnectJob = job
    }

    @JvmStatic
    fun submitGuardCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val f = guardFuture ?: run {
            fileLog("submit guard code ignored: no pending guard future (input dropped)")
            return
        }
        guardFuture = null
        // 保持 guardNeeded = true 直到登录真正结束，否则前端会把验证码输入框隐藏，
        // 用户重试时无处输入。真正的清理放在 login() 的 finally 里。
        guardNeeded = true
        stopGuardKeepAlive()
        fileLog("submit guard code: $trimmed (connected=${client?.isConnected}, guardType=$guardType)")
        status = "正在验证 Steam Guard…"
        // 注意：不要在这里把 guard=false 推给前端，否则验证码输入框会在
        // 用户提交后立刻消失；等登录结果（成功或失败）再隐藏。
        notifyState()
        try {
            f.complete(trimmed)
            fileLog("submit guard code: future completed, waiting for auth coroutine")
        } catch (t: Throwable) {
            fileLog("submit guard code: complete() threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @JvmStatic
    fun logout() {
        scope.launch {
            runCatching { user?.logOff() }
            delay(300)
            runCatching { client?.disconnect() }
            isLoggedIn = false
            steamId64 = 0L
            accountName = ""
            refreshToken = ""
            restoreJob?.cancel()
            restoreJob = null
            restoring = false
            reconnectJob?.cancel()
            prefs?.edit()?.remove("refresh_token")?.remove("account_name")?.apply()
            status = "未登录"
            notifyState()
        }
    }

    /** 登录态摘要（给 JS 用 JSON 文本） */
    @JvmStatic
    fun stateJson(): String = buildString {
        append('{')
        append("\"loggedIn\":").append(isLoggedIn).append(',')
        append("\"busy\":").append(busy).append(',')
        // 前端启动瞬间就能知道"正在恢复本地会话"，不必等首帧状态推送。
        append("\"restoring\":").append(restoring).append(',')
        append("\"reconnecting\":").append(reconnectJob?.isActive == true).append(',')
        append("\"hasToken\":").append(refreshToken.isNotBlank() && accountName.isNotBlank()).append(',')
        append("\"guard\":").append(guardNeeded).append(',')
        append("\"guardType\":\"").append(guardType.replace("\"", "")).append("\",")
        append("\"guardEmail\":\"").append(guardEmail.replace("\"", "")).append("\",")
        append("\"account\":\"").append(accountName.replace("\"", "\\\"")).append("\",")
        append("\"status\":\"").append(status.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
        append("\"error\":\"").append(error.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        append('}')
    }

    /** 登录/网络诊断：代理设置、系统代理、VPN 状态、连接与登录状态。 */
    @JvmStatic
    fun diagnosticsJson(): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")

        val proxySetting = appContext?.getSharedPreferences("weworkshop", Context.MODE_PRIVATE)
            ?.getString("proxy", "")?.trim().orEmpty()

        var sysProxy = "读取失败"
        try {
            val ps = java.net.ProxySelector.getDefault()
            val p = ps?.select(java.net.URI("https://steamcommunity.com"))?.firstOrNull()
            sysProxy = when {
                p == null -> "无（直连）"
                p.type() == java.net.Proxy.Type.DIRECT -> "无（直连）"
                else -> p.type().name + " " + p.address().toString()
            }
        } catch (t: Throwable) {
            sysProxy = "读取失败"
        }

        var vpn = "未知"
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm?.getNetworkCapabilities(net) else null
            vpn = when {
                caps == null -> "无法读取网络状态"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN 已连接（可接管全部流量）"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi（未检测到系统 VPN）"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据（未检测到系统 VPN）"
                else -> "其他网络（未检测到 VPN）"
            }
        } catch (t: Throwable) {
            vpn = "读取失败"
        }

        return buildString {
            append('{')
            append("\"proxy\":\"").append(esc(if (proxySetting.isEmpty()) "直连（未设置）" else proxySetting)).append("\",")
            append("\"systemProxy\":\"").append(esc(sysProxy)).append("\",")
            append("\"vpn\":\"").append(esc(vpn)).append("\",")
            append("\"connected\":").append(client?.isConnected == true).append(',')
            append("\"loggedIn\":").append(isLoggedIn).append(',')
            append("\"account\":\"").append(esc(accountName)).append("\",")
            append("\"status\":\"").append(esc(status)).append("\",")
            append("\"error\":\"").append(esc(error)).append('"')
            append('}')
        }
    }

    // ---------------- 内部 ----------------

    private fun notifyState() {
        stateListener?.run()
    }

    private fun notifyGuard() {
        guardListener?.run()
    }

    internal fun requestGuard(kind: String, email: String): CompletableFuture<String> {
        guardType = kind
        guardEmail = email
        guardNeeded = true
        val f = CompletableFuture<String>()
        guardFuture = f
        // 等待用户输入验证码期间保活：万一连接落在空闲超时较短的通道上，
        // 定期重连/探测可避免提交验证码时作业已被 cancel。
        startGuardKeepAlive()
        notifyGuard()
        notifyState()
        return f
    }

    /**
     * 等待 Steam Guard 验证码期间保持 CM 连接存活。
     *
     * 背景：javaSteam 只在 logon 成功后才启动心跳（CMClient 的 heartBeatFunc
     * 仅在 EResult.OK 分支 start），未登录期间完全靠服务端策略。实测 Steam 的
     * WebSocket(443) 通道约 30 秒即断开匿名空闲连接，而用户去邮箱取验证码通常
     * 超过 30 秒，于是提交验证码时连接已断，抛出 CancellationException。
     *
     * 这里不依赖私有 API（SteamClient.send 为包私有），改为：
     *  - 已强制 TCP，空闲 240s 不中断，正常情况下无需干预；
     *  - 仍然监控连接状态，若发现断开则记录，便于诊断。
     */
    private fun startGuardKeepAlive() {
        guardKeepAliveJob?.cancel()
        guardKeepAliveJob = scope.launch {
            var elapsed = 0
            while (isActive && guardNeeded) {
                delay(5_000L)
                elapsed += 5
                val c = client
                if (c == null || !c.isConnected) {
                    fileLog("guard keepalive: connection lost after ${elapsed}s while waiting for code")
                    notifyState()
                    break
                }
                if (elapsed % 30 == 0) {
                    fileLog("guard keepalive: still connected after ${elapsed}s (waiting for code)")
                }
            }
        }
    }

    private fun stopGuardKeepAlive() {
        guardKeepAliveJob?.cancel()
        guardKeepAliveJob = null
    }

    // ---------------- 创意工坊查询（与 Steam 客户端一致的数据源） ----------------

    /**
     * 通过 Steam 统一消息调用 PublishedFileService.QueryFiles。
     *
     * Wallpaper Engine 客户端浏览创意工坊时走的就是这套 Steam 客户端 API；
     * 网页匿名抓取（WorkshopParser.parseBrowse）会被 Steam 过滤掉部分条目，
     * 因此登录后优先使用本方法。
     */
    @JvmStatic
    fun queryPublishedFiles(queryJson: String): String {
        try {
            return queryPublishedFilesInner(queryJson)
        } catch (t: Throwable) {
            Log.e(TAG, "queryPublishedFiles failed", t)
            throw t
        }
    }

    /**
     * 单次 QueryFiles 调用。
     *
     * PublishedFile.queryFiles 返回 AsyncJobSingle，
     * 其父类 AsyncJob 默认 timeout = 10_000L（10 秒）。原实现只把 .get() 写成 90 秒，
     * 并未调高 job.timeout，多标签并发或网络抖动时 AsyncJobManager 会在 10 秒时
     * cancel 掉作业，抛 CancellationException（表现为"工坊查询失败"）。
     */
    private fun queryFilesOnce(
        unified: SteamUnifiedMessages,
        req: CPublishedFile_QueryFiles_Request
    ): ServiceMethodResponse<CPublishedFile_QueryFiles_Response.Builder> {
        val service = unified.createService(PublishedFile::class.java)
        val job = service.queryFiles(req)
        job.timeout = 25_000L
        return job.toFuture().get(28, TimeUnit.SECONDS)
    }

    private fun queryPublishedFilesInner(queryJson: String): String {
        val c = client ?: throw IllegalStateException("Steam 尚未连接")
        if (!isLoggedIn) throw IllegalStateException("请先在设置中登录 Steam")
        if (!c.isConnected) throw IllegalStateException("Steam 连接已断开，请稍后重试")

        val q = JSONObject(queryJson)
        val queryType = q.optInt("queryType", EPublishedFileQueryType.RankedByTrend.code())
        val page = q.optInt("page", 1).coerceAtLeast(1)
        val numPerPage = q.optInt("numPerPage", 30).coerceIn(1, 50)
        val search = q.optString("search", "")
        val tagsJson = q.optJSONArray("tags") ?: JSONArray()

        val builder = CPublishedFile_QueryFiles_Request.newBuilder()
            .setQueryType(queryType)
            .setPage(page)
            .setNumperpage(numPerPage)
            .setAppid(431960)
            .setFiletype(0)
            .setReturnTags(true)
            .setReturnPreviews(true)
            .setReturnShortDescription(true)
            .setReturnVoteData(true)
            .setReturnMetadata(true)
            // 多选标签取并集（OR）：命中任意一个标签即可，符合用户期望的『并集』语义
            .setMatchAllTags(true)
        for (i in 0 until tagsJson.length()) {
            val t = tagsJson.optString(i, "")
            if (t.isNotBlank()) builder.addRequiredtags(t)
        }
        if (search.isNotBlank()) builder.setSearchText(search)

        val unified = c.getHandler(SteamUnifiedMessages::class.java)
            ?: throw IllegalStateException("Steam 客户端不支持统一消息")
        val req = builder.build()
        // 轻量重试 1 次：仅对"作业被取消/超时"这类瞬态失败重试，
        // 且必须仍是登录且连接存活，避免在断线状态下白等 28 秒。
        val resp = try {
            queryFilesOnce(unified, req)
        } catch (t: Throwable) {
            val retryable = t is CancellationException ||
                t is TimeoutException ||
                (t is ExecutionException &&
                    (t.cause is CancellationException || t.cause is TimeoutException))
            if (!retryable || !isLoggedIn || client?.isConnected != true) throw t
            fileLog("queryFiles attempt 1 failed (${t.javaClass.simpleName}: ${t.message}), retrying once")
            Thread.sleep(300L)
            queryFilesOnce(unified, req)
        }
        if (resp.result != EResult.OK) {
            throw IllegalStateException("工坊查询失败：${resp.result}")
        }
        val body = resp.body.build()
        val items = JSONArray()
        val details = body.publishedfiledetailsList
        var di = 0
        while (di < details.size) {
            val d = details[di]
            di++
            if (d.result != EResult.OK.code()) continue
            val previewUrl: String = d.previewUrl
            val imageUrl: String = d.imageUrl
            val img: String = if (previewUrl.length > 0) previewUrl else imageUrl
            val shortDesc: String = d.shortDescription
            val fullDesc: String = d.fileDescription
            val desc: String = if (shortDesc.length > 0) shortDesc else fullDesc

            // 字段名与网页详情接口（GetPublishedFileDetails）保持一致，前端可通用渲染
            val o = JSONObject()
            o.put("id", d.publishedfileid.toString())
            o.put("img", img)
            o.put("preview_url", img)
            o.put("title", d.title)
            o.put("author", "")
            o.put("creator", d.creator.toString())
            o.put("description", desc)
            o.put("subscriptions", d.subscriptions)
            o.put("favorited", d.favorited)
            o.put("views", d.views)
            o.put("time_created", d.timeCreated)
            o.put("time_updated", d.timeUpdated)
            o.put("file_size", d.fileSize)
            o.put("hcontent_file", d.hcontentFile.toString())
            val tags = JSONArray()
            val tagList = d.tagsList
            var ti = 0
            while (ti < tagList.size) {
                val tagName: String = tagList[ti].tag
                val tagObj = JSONObject()
                tagObj.put("name", tagName)
                tagObj.put("display", tagName)
                tags.put(tagObj)
                ti++
            }
            o.put("tags", tags)
            items.put(o)
        }
        val out = JSONObject()
        out.put("ok", true)
        out.put("items", items)
        out.put("total", body.total)
        return out.toString()
    }

    /** 把诊断写进 App 外部私有目录日志（logcat 在部分 ROM 不可读）。 */
    internal fun fileLog(msg: String) {
        runCatching {
            val dir = appContext?.getExternalFilesDir(null) ?: return
            java.io.File(dir, "weworkshop.log").appendText(
                "[" + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "] " + msg + "\n"
            )
        }
    }

    /** 读取用户在设置里保存的 HTTP 代理（host:port），没有则返回 null。 */
    private fun savedProxy(): java.net.Proxy? {
        val raw = appContext?.getSharedPreferences("weworkshop", Context.MODE_PRIVATE)
            ?.getString("proxy", "")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val idx = raw.lastIndexOf(':')
        if (idx <= 0 || idx == raw.length - 1) return null
        val host = raw.substring(0, idx)
        val port = raw.substring(idx + 1).toIntOrNull() ?: return null
        return java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
    }

    @Synchronized
    private fun ensureClient(): SteamClient {
        client?.let { return it }
        // javaSteam 用 OkHttp 下载 CDN 内容，不会读系统代理，必须显式配置，
        // 否则手机在代理/VPN 环境下 CDN 请求直连会 403。
        val proxy = savedProxy()
        // 高并发传输配置：OkHttp 默认 maxRequestsPerHost = 5，
        // 会把单个 CDN 域名的并发锁死在 5 条连接上（多分块并行下载时其余全部排队，
        // 表现为"节点很快但下载很慢"）。此处放宽到单 host 32、全局 96，
        // 以配合 WorkshopDownloader 的 24 路异步网络预取 + 6 线程解压写盘流水线。
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }
        val connectionPool = okhttp3.ConnectionPool(48, 2, TimeUnit.MINUTES)
        // 超时按路径拆分：CDN 大文件仍需 90 秒长读超时；而 WebAPI
        // （ISteamDirectory / GetCMListForConnect 等）走短超时——国内直连环境下
        // api.steampowered.com 可能 TCP 握手通、TLS/HTTP 响应却黑洞，
        // 沿用 90 秒会把 c.connect() 同步卡死整整 90 秒后才抛 IOException。
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .apply { if (proxy != null) proxy(proxy) }
            .addInterceptor { chain ->
                val req = chain.request()
                val isDepot = req.url.encodedPath.contains("/depot/")
                if (isDepot) {
                    // 国内 CDN 走 DoH 多边缘 IP 分流直连（保留 Host 头，异常自动回退原域名），
                    // 同时收紧分块 socket 连接/读取超时，避免单边缘节点 100Mbps 限速与死连接。
                    val resp = DomesticCdnStriper.proceedDepot(chain, req, proxy)
                    // 只记录失败（403/5xx 等）：成功时每个 1MB 分块都会走一次本分支，
                    // 在 OkHttp 网络线程上同步 open/append/close 日志文件，
                    // 既拖慢吞吐又造成大量无意义 IO。失败诊断价值高，仍需保留。
                    if (!resp.isSuccessful) {
                        fileLog("CDN ${resp.code} proxy=${proxy ?: "direct"} ${req.url}")
                    }
                    resp
                } else {
                    // WebAPI：连接 4 秒、读取 5 秒，杜绝目录服务黑洞时卡满 90 秒。
                    chain.withConnectTimeout(4, TimeUnit.SECONDS)
                        .withReadTimeout(5, TimeUnit.SECONDS)
                        .proceed(req)
                }
            }
            .build()
        Log.i(TAG, "steam okhttp proxy: ${proxy ?: "direct"}")
        fileLog("steam client init, proxy=${proxy ?: "direct"}")
        val configuration = SteamConfiguration.create { builder ->
            builder.withConnectionTimeout(15_000L)
            builder.withHttpClient(http)
            // 持久化 CM 节点缓存：否则每次新进程启动 lastServerListRefresh 都是
            // Instant.MIN，SmartCMServerList.resolveServerList() 会在 c.connect()
            // 的同步锁内直接请求 api.steampowered.com，一次黑洞就卡满 90 秒。
            val ctx = appContext
            if (ctx != null) {
                builder.withServerListProvider(
                    FileServerListProvider(java.io.File(ctx.filesDir, "cm_servers.bin"))
                )
            }
            // 关闭 SmartCMServerList 的自动目录拉取：它失败时会同步抛 IOException
            // 把 c.connect() 直接炸断。改由 ensureServerCandidates() 受控拉取 + 保底。
            builder.withDirectoryFetch(false)
            // 强制走 TCP（27017）。javaSteam 在未登录状态下不发心跳，
            // 而 Steam 的 WebSocket(443) 通道对匿名空闲连接有约 30 秒硬超时，
            // 实测 30.02s 必被服务端断开；TCP 通道实测 240s 以上不断。
            // 登录时要等用户去邮箱取 Steam Guard 验证码，往往会超过 30 秒，
            // 落在 WebSocket 上就会在提交验证码时抛出 CancellationException
            // （AsyncJobManager.cancelPendingJobs -> CompletableFuture.cancel）。
            builder.withProtocolTypes(ProtocolTypes.TCP)
        }
        val c = SteamClient(configuration)
        val cm = CallbackManager(c)
        client = c
        callbackManager = cm
        user = c.getHandler(SteamUser::class.java)
        apps = c.getHandler(SteamApps::class.java)
        content = c.getHandler(SteamContent::class.java)
        cm.subscribe(ConnectedCallback::class.java) { Log.i(TAG, "steam connected") }
        cm.subscribe(DisconnectedCallback::class.java) {
            // 断开序号自增：connectAndWait() 据此判断"当前候选节点已失败"，立即换节点。
            disconnectSeq.incrementAndGet()
            Log.i(TAG, "steam disconnected (loggedIn=$isLoggedIn, busy=$busy, restoring=$restoring, hasToken=${refreshToken.isNotBlank()})")
            if (isLoggedIn) {
                isLoggedIn = false
            }
            // 关键：只有"没在手动登录、也没在恢复会话"时才自动重连。
            // 否则 login()/restoreSession() 执行途中底层连接失败，本回调会并发启动
            // scheduleReconnect() 抢占甚至把同一个 client 置空（原竞态根因）。
            if (!busy && !restoring && refreshToken.isNotBlank() && accountName.isNotBlank()) {
                status = "网络断开，正在重连 Steam…"
                notifyState()
                scheduleReconnect()
            }
        }
        cm.subscribe(LoggedOnCallback::class.java) { cb -> logonCallback = cb }
        scope.launch {
            // scheduleReconnect() 重建 client 后会把 callbackManager 置 null，
            // 旧协程必须跟着退出，否则会对已废弃的 CallbackManager 永久空转。
            while (isActive && callbackManager === cm) {
                runCatching { cm.runWaitCallbacks(1000L) }
                    .onFailure { Log.e(TAG, "callback loop error", it) }
                delay(10)
            }
        }
        return c
    }

    /**
     * 确保 SmartCMServerList 里有可用的 TCP 候选节点。
     *
     * 顺序：已为当前 client 准备过 → 7 天内的本地缓存 → 目录服务（受 4s/5s 短超时保护）
     * → 过期缓存 → 硬编码保底种子。
     * 任何一步失败都不向调用方抛异常：连不上目录就退化，绝不让 connect() 被炸断。
     */
    private fun ensureServerCandidates(c: SteamClient, forceRefresh: Boolean = false) {
        // 不能用 c.servers.getAllEndPoints() 判断"是否已有节点"：该方法字节码第一步就是
        // startFetchingServers() -> resolveServerList(false)，只要磁盘上存在
        // cm_servers.bin（哪怕已超过 7 天）就会隐式载入并返回非空列表，
        // 使下面的 7 天新鲜度检查被绕过、缓存日志也永远打不出来。
        // 改用 preparedClient 记录"已为哪个 SteamClient 准备过节点"。
        if (!forceRefresh && preparedClient === c) return
        val provider = c.configuration.serverListProvider
        val cached: List<ServerRecord> =
            runCatching { provider.fetchServerList() }.getOrDefault(emptyList())
        val lastRefresh: Instant =
            runCatching { provider.lastServerListRefresh }.getOrDefault(Instant.MIN)
        val hasCachedTcp = cached.any { it.protocolTypes.contains(ProtocolTypes.TCP) }
        val cacheFresh = hasCachedTcp &&
            runCatching {
                Duration.between(lastRefresh, Instant.now()) < Duration.ofDays(7)
            }.getOrDefault(false)
        if (!forceRefresh && cacheFresh) {
            c.servers.replaceList(cached, false, lastRefresh)
            preparedClient = c
            fileLog("cm servers loaded from cache (${cached.size})")
            return
        }
        val fetched = runCatching { SteamDirectory.load(c.configuration) }.getOrNull()
        if (fetched != null && fetched.any { it.protocolTypes.contains(ProtocolTypes.TCP) }) {
            c.servers.replaceList(fetched, true, Instant.now())
            preparedClient = c
            fileLog("cm servers fetched from directory (${fetched.size})")
            return
        }
        if (hasCachedTcp) {
            c.servers.replaceList(cached, false, lastRefresh)
            fileLog("SteamDirectory unavailable, using stale cache (${cached.size})")
        } else {
            val seeds = FALLBACK_TCP_SERVERS.mapNotNull { ServerRecord.tryCreateSocketServer(it) }
            c.servers.replaceList(seeds, false, Instant.MIN)
            fileLog("SteamDirectory unavailable, using fallback TCP seeds (${seeds.size})")
        }
        preparedClient = c
    }

    private suspend fun connectAndWait() {
        val c = ensureClient()
        if (c.isConnected) return
        ensureServerCandidates(c, forceRefresh = false)
        // 最多尝试 CONNECT_ATTEMPTS 个候选节点，每个只等 12 秒：
        // 原实现只 connect() 一次再死等 40 秒，一旦选到被墙/黑洞节点必然整体超时。
        for (attempt in 1..CONNECT_ATTEMPTS) {
            if (attempt == CONNECT_ATTEMPTS) {
                // 最后一次尝试前强制刷新节点列表（缓存可能过期、目录可能已恢复）。
                ensureServerCandidates(c, forceRefresh = true)
            }
            if (c.isConnected) return
            val seqBefore = disconnectSeq.get()
            c.connect()
            val deadline = System.currentTimeMillis() + CONNECT_PER_ATTEMPT_MS
            while (System.currentTimeMillis() < deadline) {
                if (c.isConnected) {
                    fileLog("steam connected on attempt $attempt")
                    return
                }
                if (disconnectSeq.get() != seqBefore) {
                    // 该候选节点已连接失败并断开：不必傻等剩余秒数，立即换下一个候选。
                    fileLog("cm candidate $attempt failed, switching to next server")
                    delay(250L)
                    break
                }
                delay(150L)
            }
        }
        throw IllegalStateException(
                    "连接 Steam 网络超时。" +
                        "Steam 登录走的是长连接（WebSocket/TCP），不受「 App 内 HTTP 代理」设置控制；" +
                        "请开启 VPN 的 TUN/全局模式，或在 VPN 分流规则里把「WE 工坊」加入代理。"
                )
    }

    private suspend fun logOnWithToken(account: String, token: String) {
        logonCallback = null
        val u = user ?: throw IllegalStateException("SteamUser 未就绪")
        u.logOn(LogOnDetails().apply {
            this.username = account
            this.accessToken = token
            this.shouldRememberPassword = true
            this.loginID = (System.nanoTime() and 0x3FFFFFFF).toInt().coerceAtLeast(1)
            this.machineName = "WE Workshop Android"
            this.chatMode = ChatMode.NEW_STEAM_CHAT
        })

        val deadline = System.currentTimeMillis() + LOGON_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val cb = logonCallback
            if (cb != null) {
                logonCallback = null
                when (cb.result) {
                    EResult.OK -> {
                        steamId64 = cb.clientSteamID?.convertToUInt64() ?: 0L
                        return
                    }
                    EResult.InvalidPassword -> throw IllegalStateException("Steam 账号或密码错误")
                    EResult.AccountLogonDenied -> throw IllegalStateException("需要 Steam Guard 验证码，请重新登录")
                    EResult.AccessDenied -> throw IllegalStateException("Steam 拒绝登录（AccessDenied）")
                    EResult.LoggedInElsewhere -> throw IllegalStateException("账号已在其他设备登录，请先退出")
                    EResult.RateLimitExceeded -> throw IllegalStateException("登录尝试过于频繁，请稍后再试")
                    else -> throw IllegalStateException("Steam 登录失败：${cb.result}")
                }
            }
            delay(200)
        }
        throw IllegalStateException("Steam 登录超时")
    }

    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: "登录失败"
        return when {
            msg.contains("InvalidPassword", true) -> "Steam 账号或密码错误"
            msg.contains("TwoFactor", true) || msg.contains("SteamGuard", true) ->
                "Steam Guard 验证失败，请重试"
            msg.contains("Proxy", true) || msg.contains("connect", true) ->
                "连接 Steam 失败：$msg"
            else -> msg
        }
    }
}

/** Steam Guard 验证码输入：把异步 future 交给 UI，等待用户输入。 */
private class GuardAuthenticator : IAuthenticator {
    override fun getDeviceCode(prior: Boolean): CompletableFuture<String> =
        SteamService.requestGuard("device", "")

    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        SteamService.requestGuard("email", email.orEmpty())

    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(true)
}
