package com.nuvio.app.bestcine.cast

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground Service para manter o proxy HTTP local DLNA + Wi-Fi ativos em segundo plano
 * no Android (inclusive MIUI/Xiaomi, Samsung OneUI e tela bloqueada), exibindo controles
 * persistentes (Pausar/Reproduzir e Parar) na barra de notificações do celular.
 */
class BestCineCastForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentTitle: String = "Transmitindo para TV"
    private var currentSubtitle: String = "BestCine • Conexão DLNA ativa"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BestCineCastController.stopCasting()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                val nextPlaying = !BestCineCastController.state.value.isPlayingOnTv
                BestCineCastController.togglePlayPause()
                val notification = buildNotification(currentTitle, currentSubtitle, isPlaying = nextPlaying)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
                return START_STICKY
            }
        }

        intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { currentTitle = it }
        intent?.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }?.let { currentSubtitle = it }

        acquireLocks()
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true
        val notification = buildNotification(currentTitle, currentSubtitle, isPlaying = isPlaying)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        releaseLocks()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bestcine:dlna_cast_wake")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // até 6 horas
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "bestcine:dlna_cast_wifi")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transmissão para TV (DLNA)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Exibe controles e mantém a transmissão para sua Smart TV ativa em segundo plano."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val toggleIntent = Intent(this, BestCineCastForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            2,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, BestCineCastForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val smallIcon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_sys_upload_done
        val toggleIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = if (isPlaying) "Pausar" else "Reproduzir"
        val statusPrefix = if (isPlaying) "Transmitindo" else "Pausado"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText("$statusPrefix • $subtitle")
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .apply {
                if (contentPendingIntent != null) setContentIntent(contentPendingIntent)
            }
            .addAction(toggleIcon, toggleLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bestcine_dlna_cast_channel"
        private const val NOTIFICATION_ID = 42910
        private const val ACTION_STOP = "com.bestcine.app.cast.ACTION_STOP"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.bestcine.app.cast.ACTION_TOGGLE_PLAY_PAUSE"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        fun start(
            context: Context,
            title: String? = null,
            subtitle: String? = null,
            isPlaying: Boolean = true,
        ) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, BestCineCastForegroundService::class.java).apply {
                    if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
                    if (!subtitle.isNullOrBlank()) putExtra(EXTRA_SUBTITLE, subtitle)
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                }
                ContextCompat.startForegroundService(appCtx, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                appCtx.stopService(Intent(appCtx, BestCineCastForegroundService::class.java))
            }
        }
    }
}
