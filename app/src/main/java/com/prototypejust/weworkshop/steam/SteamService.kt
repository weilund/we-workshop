package com.prototypejust.weworkshop.steam

import android.content.Context
import android.content.SharedPreferences
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
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
    private const val CONNECT_TIMEOUT_MS = 40_000L
    private const val LOGON_TIMEOUT_MS = 90_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var client: SteamClient? = null
    @Volatile private var callbackManager: CallbackManager? = null
    @Volatile private var user: SteamUser? = null
    @Volatile private var apps: SteamApps? = null
    @Volatile private var content: SteamContent? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var refreshToken: String = ""
    @Volatile private var reconnectJob: Job? = null

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
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        val sp = app.getSharedPreferences("steam_session", Context.MODE_PRIVATE)
        prefs = sp
        val token = sp.getString("refresh_token", "").orEmpty()
        val account = sp.getString("account_name", "").orEmpty()
        refreshToken = token
        if (token.isNotBlank() && account.isNotBlank()) {
            accountName = account
            status = "正在恢复 Steam 会话…"
            scope.launch {
                try {
                    restoreSession(account, token)
                } catch (t: Throwable) {
                    Log.w(TAG, "restore session failed", t)
                    isLoggedIn = false
                    status = "未登录"
                    notifyState()
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
            fileLog("login failed: ${t.javaClass.simpleName}: ${t.message}")
            isLoggedIn = false
            error = friendlyError(t)
            status = error
            notifyState()
            return false
        } finally {
            busy = false
        }
    }

    private suspend fun restoreSession(account: String, token: String) {
        withContext(Dispatchers.IO) {
            ensureClient()
            connectAndWait()
            status = "正在恢复 Steam 会话…"
            notifyState()
            logOnWithToken(account, token)
            isLoggedIn = true
            status = "已登录：$account"
            notifyState()
            Log.i(TAG, "session restored: $account")
        }
    }

    /** 网络切换/断线后自动用保存的 refreshToken 重连，避免要求用户重新输密码。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (refreshToken.isBlank() || accountName.isBlank()) return
        reconnectJob = scope.launch {
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
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "reconnect attempt $attempt failed: ${t.message}")
                    runCatching { client?.disconnect() }
                    delay(500)
                }
            }
            isLoggedIn = false
            status = "Steam 连接已断开，请检查网络后重试"
            notifyState()
        }
    }

    @JvmStatic
    fun submitGuardCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val f = guardFuture ?: return
        guardFuture = null
        guardNeeded = false
        fileLog("submit guard code: $trimmed")
        status = "正在验证 Steam Guard…"
        notifyState()
        f.complete(trimmed)
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
        append("\"guard\":").append(guardNeeded).append(',')
        append("\"guardType\":\"").append(guardType.replace("\"", "")).append("\",")
        append("\"guardEmail\":\"").append(guardEmail.replace("\"", "")).append("\",")
        append("\"account\":\"").append(accountName.replace("\"", "\\\"")).append("\",")
        append("\"status\":\"").append(status.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
        append("\"error\":\"").append(error.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        append('}')
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
        notifyGuard()
        notifyState()
        return f
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
            .setMatchAllTags(false)
        for (i in 0 until tagsJson.length()) {
            val t = tagsJson.optString(i, "")
            if (t.isNotBlank()) builder.addRequiredtags(t)
        }
        if (search.isNotBlank()) builder.setSearchText(search)

        val unified = c.getHandler(SteamUnifiedMessages::class.java)
            ?: throw IllegalStateException("Steam 客户端不支持统一消息")
        val service = unified.createService(PublishedFile::class.java)
        val resp = service.queryFiles(builder.build()).toFuture().get(90, TimeUnit.SECONDS)
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
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .apply { if (proxy != null) proxy(proxy) }
            .addInterceptor { chain ->
                val req = chain.request()
                val resp = chain.proceed(req)
                if (req.url.encodedPath.contains("/depot/")) {
                    fileLog("CDN ${resp.code} proxy=${proxy ?: "direct"} ${req.url}")
                }
                resp
            }
            .build()
        Log.i(TAG, "steam okhttp proxy: ${proxy ?: "direct"}")
        fileLog("steam client init, proxy=${proxy ?: "direct"}")
        val configuration = SteamConfiguration.create { builder ->
            builder.withConnectionTimeout(15_000L)
            builder.withHttpClient(http)
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
            Log.i(TAG, "steam disconnected (loggedIn=$isLoggedIn, hasToken=${refreshToken.isNotBlank()})")
            if (isLoggedIn) {
                isLoggedIn = false
                status = "网络断开，正在重连 Steam…"
                notifyState()
                scheduleReconnect()
            }
        }
        cm.subscribe(LoggedOnCallback::class.java) { cb -> logonCallback = cb }
        scope.launch {
            while (true) {
                runCatching { cm.runWaitCallbacks(1000L) }
                    .onFailure { Log.e(TAG, "callback loop error", it) }
                delay(10)
            }
        }
        return c
    }

    private suspend fun connectAndWait() {
        val c = ensureClient()
        if (c.isConnected) return
        c.connect()
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!c.isConnected) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("连接 Steam 网络超时，请确认手机已开启 VPN/TUN 代理")
            }
            delay(200)
        }
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
