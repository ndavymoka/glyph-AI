package com.example.glyph

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.nothing.ketchum.GlyphToy

/**
 * Nothing Phone (3) Glyph Toy Service registered in AndroidManifest.xml with
 * `<action android:name="com.nothing.glyph.TOY"/>`, `com.nothing.glyph.toy.longpress = 1`,
 * and `android:foregroundServiceType="microphone"`.
 *
 * Crucial Android 14/15/16 (`targetSdk = 36`) Microphone Architecture:
 * - Android 14+ only allows starting a `FOREGROUND_SERVICE_TYPE_MICROPHONE` while the app has a visible
 *   Activity in the foreground. If a service drops its foreground state in `onUnbind()` while the app is
 *   in the background, Android blocks subsequent `startForeground(MICROPHONE)` calls, causing only the
 *   1st request after exiting the app to work.
 * - Therefore, `MainActivity` starts this service via `ensurePersistentForegroundMicService(context)`
 *   while visible, and `onUnbind()` keeps the foreground microphone state alive (`return true` + `onRebind`)
 *   so the 2nd, 3rd, and Nth requests work reliably even hours after exiting the app or locking the screen.
 */
class GlyphOracleToyService : Service() {

    private lateinit var coordinator: GlyphOracleCoordinator

    private val serviceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val bundle = msg.data
                    val event = bundle?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    coordinator.ensureToyActiveFromEvent()
                    when (event) {
                        GlyphToy.EVENT_CHANGE -> {
                            promoteToForegroundMicrophoneService()
                            coordinator.onGlyphButtonLongPress()
                        }
                        GlyphToy.EVENT_AOD -> {
                            coordinator.onAodTick()
                        }
                        GlyphToy.EVENT_ACTION_DOWN -> {
                            // Touch-down on Glyph Button
                        }
                        GlyphToy.EVENT_ACTION_UP -> {
                            // Touch-up on Glyph Button
                        }
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val serviceMessenger = Messenger(serviceHandler)

    override fun onCreate() {
        super.onCreate()
        coordinator = GlyphOracleCoordinator.getInstance(applicationContext)
        ensureNotificationChannel()
        promoteToForegroundMicrophoneService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForegroundMicrophoneService()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        promoteToForegroundMicrophoneService()
        coordinator.onToyServiceBind()
        return serviceMessenger.binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        promoteToForegroundMicrophoneService()
        coordinator.onToyServiceBind()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        coordinator.onToyServiceUnbind()
        // IMPORTANT: Do NOT call stopForeground() here!
        // Keeping FOREGROUND_SERVICE_TYPE_MICROPHONE active while the app is in the background
        // ensures Android 14/15/16 permits microphone capture on the 2nd, 3rd, and all subsequent
        // Glyph Toy activations without reopening MainActivity.
        // Returning `true` ensures `onRebind()` is invoked when Nothing OS binds again.
        return true
    }

    override fun onDestroy() {
        stopForegroundSafely()
        super.onDestroy()
    }

    private fun promoteToForegroundMicrophoneService() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return

        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            // Ignored if OS restricts foreground promotion in specific states
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glyph AI Oracle Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянный доступ к микрофону для Glyph Matrix 25x25"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Glyph Oracle • 25×25")
            .setContentText("Готов к голосовым вопросам по кнопке Glyph Button")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "glyph_oracle_mic_channel"
        private const val NOTIFICATION_ID = 2525

        /**
         * Starts `GlyphOracleToyService` as a persistent `FOREGROUND_SERVICE_TYPE_MICROPHONE`
         * while `MainActivity` is visible in the foreground. This grants the process continuous
         * background & lock-screen microphone access on Android 14/15/16 for unlimited requests.
         */
        fun ensurePersistentForegroundMicService(context: Context) {
            val appCtx = context.applicationContext
            val hasMicPerm = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPerm) return

            try {
                val serviceIntent = Intent(appCtx, GlyphOracleToyService::class.java)
                ContextCompat.startForegroundService(appCtx, serviceIntent)
            } catch (_: Throwable) {
            }
        }
    }
}
