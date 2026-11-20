package com.prototypejust.weworkshop.steam

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 国内 Steam CDN 同城骨干机房多边缘 IP 分流器（反 PCDN + DoH 同城直连 + RTT 门控 + 流速率隔离）。
 *
 * 解决四大痛点：
 *  1. 网宿 `dl.steam.clngaa.com` 默认 GSLB (`.z.ngaagslb.net`) 会按比例混入 `gdtstream.com` 等
 *     家宽 PCDN 盒子节点导致严重掉速；直查 `.i.ngaagslb.net` 骨干池并过滤 PCDN CNAME，100% 锁定 IDC 机房；
 *  2. 只使用 `dns.alidns.com`（命中国内直连规则，服务端直接识别本机真实宽带出口 IP 返回同城节点），
 *     杜绝海外代理劫持无域名 DoH IP（如 1.12.12.12）返回跨省异地冷缓存节点；
 *  3. 入池前并发实测 80 端口 TCP 握手 RTT，仅保留同城低延迟节点（<= minRtt * 2 + 12ms 且 <= 45ms）；
 *  4. 包装 ResponseBody 追踪单边缘 IP 实际流读取耗时与吞吐：若读取中途超时卡死（>=5s 未读完）或
 *     吞吐远低于同池最快边缘 IP，仅精准拉黑该边缘 IP，避免外层把整个 clngaa.com 误判下线切去慢速 Tier 1。
 */
internal object DomesticCdnStriper {
    private const val TAG = "WEDownload"
    private const val CACHE_TTL_MS = 180_000L
    private const val BAD_IP_COOLDOWN_MS = 60_000L

    private val STRIPED_DOMAINS = listOf(
        "clngaa.com",
        "steamcontent.com"
    )

    /** 已知低速家宽 PCDN / 二级拼凑边缘域关键字：一旦 CNAME 链命中立即丢弃该组结果。 */
    private val PCDN_KEYWORDS = listOf(
        "gdtstream",
        "pcdn",
        "mcdn",
        "szbdyd",
        "xycdn",
        "onethingpcs",
        "uhabo",
        "kuiniuca"
    )

    private const val ALIDNS_DOH = "https://dns.alidns.com/resolve"

    private data class Entry(val ips: List<String>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val badUntil = ConcurrentHashMap<String, Long>()
    private val ipEwmaBps = ConcurrentHashMap<String, AtomicLong>()
    private val bestIpBps = AtomicLong(0L)
    private val rr = AtomicInteger(0)

    private val probePool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "we-doh-probe").apply { isDaemon = true }
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    private fun shouldStripe(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        return STRIPED_DOMAINS.any { d -> h == d || h.endsWith(".$d") }
    }

    fun proceedDepot(chain: Interceptor.Chain, req: Request, proxy: Proxy?): Response {
        val originalHost = req.url.host
        if (proxy == null && req.url.scheme.equals("http", ignoreCase = true) && shouldStripe(originalHost)) {
            val edgeIp = pickEdgeIp(originalHost)
            if (edgeIp != null) {
                val stripedUrl = req.url.newBuilder().host(edgeIp).build()
                val stripedReq = req.newBuilder()
                    .url(stripedUrl)
                    .header("Host", originalHost)
                    .build()
                try {
                    val resp = chain.withConnectTimeout(4, TimeUnit.SECONDS)
                        .withReadTimeout(12, TimeUnit.SECONDS)
                        .proceed(stripedReq)
                    if (resp.isSuccessful) {
                        val body = resp.body ?: return resp
                        return resp.newBuilder()
                            .body(MonitoredBody(body, edgeIp, originalHost))
                            .build()
                    }
                    val code = resp.code
                    runCatching { resp.close() }
                    markBad(edgeIp, "HTTP_$code")
                } catch (e: IOException) {
                    markBad(edgeIp, "IO_${e.javaClass.simpleName}")
                }
            }
        }
        return chain.withConnectTimeout(6, TimeUnit.SECONDS)
            .withReadTimeout(15, TimeUnit.SECONDS)
            .proceed(req)
    }

    private class MonitoredBody(
        private val delegate: ResponseBody,
        private val edgeIp: String,
        private val host: String
    ) : ResponseBody() {
        private val startMs = System.currentTimeMillis()
        private val expectedBytes = delegate.contentLength()
        private var totalRead = 0L
        private var eof = false
        private val closed = AtomicBoolean(false)

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = expectedBytes

        override fun source(): BufferedSource {
            val forwarding = object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    return try {
                        val n = super.read(sink, byteCount)
                        if (n == -1L) {
                            eof = true
                            onCompleted()
                        } else {
                            totalRead += n
                            if (expectedBytes > 0L && totalRead >= expectedBytes) {
                                eof = true
                                onCompleted()
                            }
                        }
                        n
                    } catch (e: IOException) {
                        markBad(edgeIp, "BODY_IO_${e.javaClass.simpleName}")
                        throw e
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (closed.compareAndSet(false, true) && !eof) {
                            val elapsed = System.currentTimeMillis() - startMs
                            // 读取中途被超时取消（持续 >= 4.5s 仍未读完 ~1MB 分块）：精准隔离该慢边缘 IP
                            if (elapsed >= 4_500L) {
                                markBad(edgeIp, "STALL_${elapsed}ms_${totalRead}B")
                            }
                        }
                    }
                }
            }
            return forwarding.buffer()
        }

        private fun onCompleted() {
            if (!closed.compareAndSet(false, true)) return
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (totalRead < 262_144L || elapsed < 30L) return
            val bps = totalRead * 1000L / elapsed
            val slot = ipEwmaBps.computeIfAbsent(edgeIp) { AtomicLong(-1L) }
            val ewma = slot.updateAndGet { prev -> if (prev < 0L) bps else (prev * 3L + bps) / 4L }
            while (true) {
                val cur = bestIpBps.get()
                if (ewma <= cur || bestIpBps.compareAndSet(cur, ewma)) break
            }
            val best = bestIpBps.get()
            if (elapsed >= 2_500L && best >= 2_000_000L && ewma < best / 3L) {
                markBad(edgeIp, "SLOW_${ewma / 1024}KBps_vs_${best / 1024}KBps")
            }
        }
    }

    private fun markBad(ip: String, reason: String) {
        badUntil[ip] = System.currentTimeMillis() + BAD_IP_COOLDOWN_MS
        Log.w(TAG, "quarantine edge ip $ip ($reason) for ${BAD_IP_COOLDOWN_MS / 1000}s")
    }

    private fun pickEdgeIp(host: String): String? {
        val ips = resolveIps(host)
        if (ips.isEmpty()) return null
        val now = System.currentTimeMillis()
        val start = (rr.getAndIncrement() and 0x7fffffff) % ips.size
        for (k in ips.indices) {
            val ip = ips[(start + k) % ips.size]
            val until = badUntil[ip] ?: 0L
            if (now >= until) return ip
        }
        return null
    }

    private fun resolveIps(host: String): List<String> {
        val now = System.currentTimeMillis()
        cache[host]?.let { if (now < it.expiresAtMs) return it.ips }
        val lock = locks.computeIfAbsent(host) { Any() }
        synchronized(lock) {
            val again = System.currentTimeMillis()
            cache[host]?.let { if (again < it.expiresAtMs) return it.ips }

            val h = host.lowercase(Locale.US)
            // 对 dl.steam.clngaa.com 优先直查 .i.ngaagslb.net 纯骨干机房池，跳过 .z.ngaagslb.net 的 PCDN 抽签
            val queryNames = if (h == "dl.steam.clngaa.com") {
                listOf("dl.steam.clngaa.com.i.ngaagslb.net", "dl.steam.clngaa.com", "dl.steam.clngaa.com")
            } else {
                listOf(host, host)
            }

            val candidates = LinkedHashSet<String>()
            val lookupFutures = queryNames.map { qName ->
                CompletableFuture.supplyAsync({ queryAliDohFiltered(qName) }, probePool)
            }
            for (f in lookupFutures) {
                val list = runCatching { f.get(2200, TimeUnit.MILLISECONDS) }.getOrNull()
                if (!list.isNullOrEmpty()) candidates.addAll(list)
            }

            // 并发实测 80 端口 TCP 握手 RTT：剔除跨省高延迟或死节点，只保留同城高速骨干 IP
            val rttFutures = candidates.map { ip ->
                ip to CompletableFuture.supplyAsync({ measureTcpRttMs(ip, 80, 450) }, probePool)
            }
            val measured = ArrayList<Pair<String, Long>>(candidates.size)
            for ((ip, f) in rttFutures) {
                val rtt = runCatching { f.get(550, TimeUnit.MILLISECONDS) }.getOrNull() ?: -1L
                if (rtt >= 0L) measured.add(ip to rtt)
            }

            val result: List<String> = if (measured.isNotEmpty()) {
                val minRtt = measured.minOf { it.second }
                val maxAllowedRtt = maxOf(minRtt + 25L, minRtt * 2L)
                measured.filter { it.second <= maxAllowedRtt }
                    .sortedBy { it.second }
                    .map { it.first }
                    .ifEmpty { listOf(measured.minBy { it.second }.first) }
            } else {
                emptyList()
            }

            val ttl = if (result.isNotEmpty()) CACHE_TTL_MS else 15_000L
            cache[host] = Entry(result, System.currentTimeMillis() + ttl)
            if (result.isNotEmpty()) {
                val detail = measured.filter { it.first in result }
                    .joinToString(",") { "${it.first}(${it.second}ms)" }
                val msg = "doh idc $host -> $detail"
                Log.i(TAG, msg)
                SteamService.fileLog(msg)
            }
            return result
        }
    }

    private fun measureTcpRttMs(ip: String, port: Int, timeoutMs: Int): Long {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun queryAliDohFiltered(qName: String): List<String> {
        val url = "$ALIDNS_DOH?name=$qName&type=A"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        dohClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            if (body.isEmpty()) return emptyList()
            val root = JSONObject(body)
            val arr = root.optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<String>(4)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optInt("type")
                val data = item.optString("data", "").trim().lowercase(Locale.US)
                // 一旦 CNAME 链命中 gdtstream 等 PCDN 域名，整包丢弃，绝不混入池中
                if (type == 5 && PCDN_KEYWORDS.any { kw -> data.contains(kw) }) {
                    Log.i(TAG, "skip PCDN cname for $qName: $data")
                    return emptyList()
                }
                if (type == 1 && isPublicIpv4(data)) {
                    out.add(data)
                }
            }
            return out
        }
    }

    internal fun isPublicIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        val c = parts[2].toIntOrNull() ?: return false
        val d = parts[3].toIntOrNull() ?: return false
        if (a !in 1..223 || b !in 0..255 || c !in 0..255 || d !in 0..255) return false
        if (a == 10 || a == 127) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 198 && (b == 18 || b == 19)) return false
        if (a == 100 && b in 64..127) return false
        return true
    }
}