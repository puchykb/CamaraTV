package com.pux.camaratv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Keeps one connection per camera in the background and, when one reports motion,
 * shows a banner over whatever is on the TV. OK on the banner opens that camera.
 */
class MotionService : Service() {

    private val watchers = mutableListOf<Watcher>()
    private val lastAlert = HashMap<String, Long>()
    private var banner: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val removeBanner = Runnable { hideBanner() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, ongoingNotification())
    }

    /** Called on every start(), so it also reloads the camera list after it changes. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopWatchers()
        // Motion events come over the XMEye protocol; ONVIF/RTSP cameras are view-only for now
        CameraStore.all(this).filter { it.type == CameraType.XMEYE }
            .forEach { camera -> watchers += Watcher(camera).also { it.start() } }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatchers()
        main.post { hideBanner() }
        super.onDestroy()
    }

    private fun stopWatchers() {
        watchers.forEach { it.stop() }
        watchers.clear()
    }

    /** Listens to one camera's alarms, reconnecting quietly if it or the Wi-Fi drops. */
    private inner class Watcher(private val camera: Camera) {
        @Volatile private var running = true
        @Volatile private var client: DvripClient? = null
        private val thread = Thread({ loop() }, "motion-${camera.host}")

        fun start() = thread.start()

        fun stop() {
            running = false
            client?.close()
            thread.interrupt()
        }

        private fun loop() {
            while (running) {
                val c = DvripClient(camera)
                client = c
                try {
                    c.connect()
                    c.watchAlarms { alarm -> onAlarm(camera, alarm) }
                } catch (_: Exception) {
                    // Retry below
                } finally {
                    c.close()
                }
                if (!running) break
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun onAlarm(camera: Camera, alarm: JSONObject) {
        if (!alarm.optString("Status").equals("Start", ignoreCase = true)) return
        if (MainActivity.visibleCameraId == camera.id) return // already watching it
        if (!AlertSettings.shouldAlert(this)) return
        val now = System.currentTimeMillis()
        synchronized(lastAlert) {
            if (now - (lastAlert[camera.id] ?: 0L) < COOLDOWN_MS) return
            lastAlert[camera.id] = now
        }

        val event = alarm.optString("Event")
        val title = if (event.contains("Human", ignoreCase = true)) "Persona detectada" else "Movimiento detectado"
        main.post { alert(camera, title) }
    }

    private fun alert(camera: Camera, title: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            showBanner(camera, title)
        } else {
            // Without the overlay permission fall back to a regular notification
            notificationManager().notify(camera.id.hashCode(), alertNotification(camera, title))
        }
    }

    private fun openCamera(camera: Camera) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA_ID, camera.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun showBanner(camera: Camera, title: String) {
        hideBanner()
        val dp = resources.displayMetrics.density
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (14 * dp).toInt(), pad, (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xE61E2A38.toInt())
                cornerRadius = 14 * dp
                setStroke((2 * dp).toInt(), 0xFF4FC3F7.toInt())
            }
            addView(TextView(context).apply {
                text = "● $title · ${camera.name}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
            addView(TextView(context).apply {
                text = "OK: ver la cámara   ·   Abajo: pausar 1 hora   ·   Atrás: cerrar"
                setTextColor(0xFFB0BEC5.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        hideBanner()
                        openCamera(camera)
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        AlertSettings.pause(this@MotionService, 60 * 60 * 1000L)
                        hideBanner()
                        Toast.makeText(this@MotionService, "Avisos pausados por 1 hora", Toast.LENGTH_LONG).show()
                    }
                    else -> hideBanner()
                }
                true
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (32 * dp).toInt()
            y = (32 * dp).toInt()
        }
        try {
            windowManager().addView(view, params)
            view.requestFocus()
            banner = view
            main.postDelayed(removeBanner, BANNER_MS)
        } catch (_: RuntimeException) {
            notificationManager().notify(camera.id.hashCode(), alertNotification(camera, title))
        }
    }

    private fun hideBanner() {
        main.removeCallbacks(removeBanner)
        banner?.let { try { windowManager().removeView(it) } catch (_: RuntimeException) {} }
        banner = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Vigilancia en segundo plano", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alertas de movimiento", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun ongoingNotification(): Notification =
        builder(CHANNEL_ONGOING)
            .setContentTitle("Cámara")
            .setContentText("Avisando si hay movimiento")
            .setOngoing(true)
            .build()

    private fun alertNotification(camera: Camera, title: String): Notification =
        builder(CHANNEL_ALERTS)
            .setContentTitle("$title · ${camera.name}")
            .setContentText("Toca para ver la cámara")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    camera.id.hashCode(),
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CAMERA_ID, camera.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun builder(channel: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(
                if (channel == CHANNEL_ALERTS) Notification.PRIORITY_HIGH else Notification.PRIORITY_MIN,
            )
        }
        return b.setSmallIcon(R.drawable.icon)
    }

    private fun windowManager() = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ONGOING = "ongoing"
        private const val CHANNEL_ALERTS = "alerts"
        private const val ONGOING_ID = 1
        private const val COOLDOWN_MS = 60_000L
        private const val BANNER_MS = 15_000L

        /** Starts the service, or makes it reload the camera list if it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, MotionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionService::class.java))
        }
    }
}
