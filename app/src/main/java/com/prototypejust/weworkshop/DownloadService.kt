package com.prototypejust.weworkshop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.prototypejust.weworkshop.steam.WorkshopDownloader
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 工业级后台下载保活前台服务：
 *  1. 持有 PARTIAL_WAKE_LOCK 防止息屏后 CPU 休眠断流；
 *  2. 持有 WIFI_MODE_FULL_LOW_LATENCY（API 29+）防止切屏/息屏后 Wi-Fi 进入 100ms DTIM 省电休眠导致吞吐暴跌；
 *  3. 实时刷新状态栏下载进度、实时网速与「暂停全部」快捷按钮（节流 900ms），让系统（含 vivo OriginOS PEM）
 *     识别为真实活跃前台传输，免于冻结与后台网络限速；后台完成时发送完成提醒通知。
 */
class DownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            runCatching { WorkshopDownloader.pauseAll(applicationContext) }
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "WE 工坊后台下载中"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在准备下载…"
        val permille = intent?.getIntExtra(EXTRA_PERMILLE, -1) ?: -1
        startAsForeground(title, text, permille)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WEWorkshop:DownloadWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // 最长兜底 4 小时自动释放，防止极端崩溃泄漏电量
                wakeLock?.acquire(4L * 60L * 60L * 1000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquire WakeLock failed", t)
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "WEWorkshop:DownloadWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquire WifiLock failed", t)
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun startAsForeground(title: String, text: String, permille: Int) {
        val notification = buildOngoingNotification(this, title, text, permille)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val progressCh = NotificationChannel(
                CHANNEL_ID,
                "壁纸下载进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台下载壁纸时显示实时速度与进度，防止系统断流"
                setShowBadge(false)
            }
            val doneCh = NotificationChannel(
                DONE_CHANNEL_ID,
                "下载完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "后台壁纸下载完成或异常提醒"
            }
            nm.createNotificationChannel(progressCh)
            nm.createNotificationChannel(doneCh)
        }
    }

    companion object {
        private const val TAG = "WEWorkshop"
        private const val CHANNEL_ID = "weworkshop_download"
        private const val DONE_CHANNEL_ID = "weworkshop_download_done"
        private const val NOTIF_ID = 101
        private const val DONE_NOTIF_BASE_ID = 2000
        private const val ACTION_PAUSE_ALL = "com.prototypejust.weworkshop.ACTION_PAUSE_ALL"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERMILLE = "permille"
        private const val NOTIF_UPDATE_INTERVAL_MS = 900L

        private val lastNotifUpdateAt = AtomicLong(0L)

        private fun contentPendingIntent(context: Context): PendingIntent {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, 0, openIntent, piFlags)
        }

        private fun buildOngoingNotification(
            context: Context,
            title: String,
            text: String,
            permille: Int
        ): Notification {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val pauseIntent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_ALL
            }
            val pausePi = PendingIntent.getService(
                context,
                1,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val action = Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                "暂停全部",
                pausePi
            ).build()

            builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentPendingIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(action)

            if (permille in 0..1000) {
                builder.setProgress(1000, permille, false)
            } else {
                builder.setProgress(1000, 0, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            return builder.build()
        }

        @JvmStatic
        fun start(context: Context) {
            try {
                val app = context.applicationContext
                val i = Intent(app, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(i)
                } else {
                    app.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start DownloadService failed", t)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, DownloadService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stop DownloadService failed", t)
            }
        }

        private fun fmtSize(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val mb = bytes / 1048576.0
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }

        @JvmStatic
        fun onProgress(
            context: Context,
            itemId: Long,
            itemTitle: String,
            received: Long,
            total: Long,
            phase: String,
            done: Boolean,
            paused: Boolean,
            error: String?,
            activeCount: Int
        ) {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return

            if (done) {
                if (!MainActivity.isForeground) {
                    runCatching {
                        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Notification.Builder(app, DONE_CHANNEL_ID)
                        } else {
                            @Suppress("DEPRECATION")
                            Notification.Builder(app)
                        }
                        val notif = builder
                            .setContentTitle("壁纸下载完成")
                            .setContentText("$itemTitle (${fmtSize(total.coerceAtLeast(received))})")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentIntent(contentPendingIntent(app))
                            .setAutoCancel(true)
                            .build()
                        nm.notify(DONE_NOTIF_BASE_ID + ((itemId and 0x7fffL).toInt()), notif)
                    }
                }
                return
            }

            if (paused || error != null || activeCount <= 0) {
                return
            }

            val now = System.currentTimeMillis()
            val prev = lastNotifUpdateAt.get()
            if (now - prev < NOTIF_UPDATE_INTERVAL_MS || !lastNotifUpdateAt.compareAndSet(prev, now)) {
                return
            }

            val permille = if (total > 0L) ((received * 1000L) / total).toInt().coerceIn(0, 1000) else -1
            val pctStr = if (total > 0L) "${(permille / 10)}%" else ""
            val countPrefix = if (activeCount > 1) "($activeCount 个任务) " else ""
            val notifTitle = "$countPrefix$phase"
            val notifText = if (total > 0L) {
                "$itemTitle · ${fmtSize(received)} / ${fmtSize(total)} ($pctStr)"
            } else {
                "$itemTitle · 正在准备…"
            }

            runCatching {
                nm.notify(NOTIF_ID, buildOngoingNotification(app, notifTitle, notifText, permille))
            }
        }
    }
}
