package com.pux.camaratv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Full-screen live view of one camera.
 *
 * Remote: arrows = move the camera (PTZ) · OK = quality · hold OK / Menu = options ·
 * CH+/CH- or 1-9 = switch camera. In zoom mode: OK = zoom in, arrows = pan, Back = leave zoom mode.
 */
class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var videoView: TextureView
    private lateinit var statusView: TextView

    @Volatile private var running = false
    @Volatile private var switchingStream = false
    @Volatile private var mainStream = true
    @Volatile private var client: DvripClient? = null
    private var worker: Thread? = null
    private var surface: Surface? = null
    private val ptzExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var zoomMode = false
    private var zoomIndex = 0
    private var panX = 0f
    private var panY = 0f

    private val hideStatus = Runnable { statusView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = TextureView(this)
        videoView.surfaceTextureListener = this

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(0x99000000.toInt())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        root.addView(videoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val margin = (32 * resources.displayMetrics.density).toInt()
        root.addView(
            statusView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(margin, margin, margin, margin) },
        )
        setContentView(root)

        camera = pickCamera(intent.getStringExtra(EXTRA_CAMERA_ID))
        if (AlertSettings.mode(this) != AlertSettings.Mode.OFF) MotionService.start(this)
        if (camera == null) {
            startActivity(Intent(this, CamerasActivity::class.java))
        } else {
            askOverlayPermissionOnce()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CAMERA_ID)?.let { id -> CameraStore.get(this, id)?.let { switchTo(it) } }
    }

    override fun onResume() {
        super.onResume()
        // The camera may have been edited, deleted or added in the settings screens
        val fresh = pickCamera(camera?.id)
        if (fresh != camera) switchTo(fresh)
        visibleCameraId = camera?.id
        if (camera == null) showStatus("No hay cámaras configuradas. Mantén OK para abrir el menú.")
    }

    override fun onPause() {
        visibleCameraId = null
        super.onPause()
    }

    private fun pickCamera(preferredId: String?): Camera? {
        val all = CameraStore.all(this)
        return all.firstOrNull { it.id == preferredId }
            ?: all.firstOrNull { it.id == CameraStore.lastViewed(this) }
            ?: all.firstOrNull()
    }

    private fun switchTo(target: Camera?) {
        if (target == camera && running) return
        stopStreaming()
        camera = target
        visibleCameraId = target?.id
        resetZoom()
        target?.let { CameraStore.setLastViewed(this, it.id) }
        if (target != null) surface?.let { startStreaming(it) }
    }

    // --- Video surface ---

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(texture)
        surface = s
        if (camera != null) startStreaming(s)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyZoom()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopStreaming()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    // --- Remote control ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (zoomMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> pan(0f, 1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> pan(0f, -1f)
                KeyEvent.KEYCODE_DPAD_LEFT -> pan(1f, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> pan(-1f, 0f)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) nextZoom()
                KeyEvent.KEYCODE_BACK -> leaveZoomMode()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        PTZ_KEYS[keyCode]?.let { command ->
            // Holding the key auto-repeats; only the first press starts the move
            if (event.repeatCount == 0) sendPtz(command, stop = false)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Short press = quality (handled on key up), long press = menu
                if (event.repeatCount == 0) event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showMenu()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                cycleCamera(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                cycleCamera(-1)
                return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                CameraStore.all(this).getOrNull(keyCode - KeyEvent.KEYCODE_1)?.let { switchTo(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (zoomMode) return super.onKeyUp(keyCode, event)
        PTZ_KEYS[keyCode]?.let { command ->
            sendPtz(command, stop = true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isTracking && !event.isCanceled) toggleQuality()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun sendPtz(command: String, stop: Boolean) {
        val c = client ?: return
        ptzExecutor.execute {
            try { c.ptz(command, stop) } catch (_: IOException) {}
        }
    }

    private fun toggleQuality() {
        if (camera == null) return
        mainStream = !mainStream
        showStatus(if (mainStream) "Calidad alta" else "Calidad baja (más fluida)", autoHide = true)
        switchingStream = true
        client?.close()
    }

    private fun cycleCamera(step: Int) {
        val all = CameraStore.all(this)
        if (all.size < 2) {
            showStatus("Solo hay una cámara configurada", autoHide = true)
            return
        }
        val i = all.indexOfFirst { it.id == camera?.id }
        switchTo(all[Math.floorMod(i + step, all.size)])
    }

    // --- Digital zoom ---

    private fun enterZoomMode() {
        zoomMode = true
        if (zoomIndex == 0) nextZoom() else showZoomStatus()
    }

    private fun leaveZoomMode() {
        zoomMode = false
        showStatus(
            if (zoomIndex == 0) "Zoom desactivado" else "Zoom ${ZOOM_LEVELS[zoomIndex]}x fijado · flechas: mover la cámara",
            autoHide = true,
        )
    }

    private fun nextZoom() {
        zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.size
        applyZoom()
        showZoomStatus()
    }

    private fun pan(dx: Float, dy: Float) {
        if (zoomIndex == 0) return
        panX += dx * videoView.width * PAN_STEP
        panY += dy * videoView.height * PAN_STEP
        applyZoom()
    }

    private fun resetZoom() {
        zoomMode = false
        zoomIndex = 0
        applyZoom()
    }

    private fun applyZoom() {
        val z = ZOOM_LEVELS[zoomIndex]
        // Keep the zoomed picture covering the whole screen
        val maxX = (z - 1) * videoView.width / 2f
        val maxY = (z - 1) * videoView.height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        videoView.scaleX = z
        videoView.scaleY = z
        videoView.translationX = panX
        videoView.translationY = panY
    }

    private fun showZoomStatus() {
        val z = ZOOM_LEVELS[zoomIndex]
        showStatus("Zoom ${if (z % 1f == 0f) z.toInt() else z}x · OK: más zoom · Flechas: recorrer · Atrás: salir del zoom")
    }

    // --- Menu ---

    private fun showMenu() {
        val all = CameraStore.all(this)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (all.size > 1) items += "Cambiar de cámara" to { chooseCamera(all) }
        if (camera != null) items += "Zoom" to { enterZoomMode() }
        items += "Avisos de movimiento (${AlertSettings.describe(this)})" to { showAlertOptions() }
        items += "Configurar cámaras" to { startActivity(Intent(this, CamerasActivity::class.java)) }

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(camera?.name ?: "Cámara")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun chooseCamera(all: List<Camera>) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Cambiar de cámara")
            .setSingleChoiceItems(
                all.mapIndexed { i, c -> "${i + 1}. ${c.name}" }.toTypedArray(),
                all.indexOfFirst { it.id == camera?.id },
            ) { dialog, which ->
                dialog.dismiss()
                switchTo(all[which])
            }
            .show()
    }

    private fun showAlertOptions() {
        val options = arrayOf(
            "Siempre activados",
            "Solo de noche (20:00 a 8:00)",
            "Pausar por 1 hora",
            "Pausar por 4 horas",
            "Desactivados",
        )
        val current = when (AlertSettings.mode(this)) {
            AlertSettings.Mode.ALWAYS -> 0
            AlertSettings.Mode.NIGHT -> 1
            AlertSettings.Mode.OFF -> 4
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Avisos de movimiento — ahora: " + AlertSettings.describe(this))
            .setSingleChoiceItems(options, current) { dialog, which ->
                when (which) {
                    0 -> AlertSettings.setMode(this, AlertSettings.Mode.ALWAYS)
                    1 -> AlertSettings.setMode(this, AlertSettings.Mode.NIGHT)
                    2 -> AlertSettings.pause(this, 60 * 60 * 1000L)
                    3 -> AlertSettings.pause(this, 4 * 60 * 60 * 1000L)
                    4 -> AlertSettings.setMode(this, AlertSettings.Mode.OFF)
                }
                dialog.dismiss()
                showStatus("Avisos: " + AlertSettings.describe(this), autoHide = true)
            }
            .show()
    }

    /** The motion banner is drawn over other apps, which needs "Display over other apps". */
    private fun askOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_asked", false)) return
        prefs.edit().putBoolean("overlay_asked", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            // Some TV boxes hide this screen; alerts then fall back to notifications
        }
    }

    // --- Streaming ---

    private fun startStreaming(surface: Surface) {
        val cam = camera ?: return
        running = true
        worker = Thread({ streamLoop(cam, surface) }, "camera").apply { start() }
    }

    private fun stopStreaming() {
        running = false
        client?.close()
        worker?.interrupt()
        worker?.join(2000)
        worker = null
    }

    private fun streamLoop(cam: Camera, surface: Surface) {
        var quiet = false
        while (running) {
            if (!quiet) showStatus("Conectando a ${cam.name}…")
            quiet = false
            val c = DvripClient(cam)
            client = c
            val decoder = VideoDecoder(surface)
            val audio = AudioPlayer()
            try {
                c.connect()
                var firstFrame = true
                c.stream(
                    mainStream,
                    onFrame = { frame ->
                        decoder.submit(frame)
                        if (firstFrame && frame.isKey) {
                            firstFrame = false
                            if (!zoomMode) showStatus("${cam.name} · Flechas: mover · OK: calidad · Mantener OK: menú", autoHide = true)
                        }
                    },
                    onAudio = { codec, data -> if (!firstFrame) audio.play(codec, data) },
                )
            } catch (e: Exception) {
                if (!running) break
                if (switchingStream) {
                    // Closed on purpose to change quality: reconnect right away
                    switchingStream = false
                    quiet = true
                    continue
                }
                showStatus("${cam.name}: sin conexión (${e.message ?: e.javaClass.simpleName}). Reintentando…")
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            } finally {
                c.close()
                decoder.release()
                audio.release()
            }
        }
    }

    private fun showStatus(text: String, autoHide: Boolean = false) = runOnUiThread {
        statusView.removeCallbacks(hideStatus)
        statusView.text = text
        statusView.visibility = View.VISIBLE
        if (autoHide) statusView.postDelayed(hideStatus, 3000)
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"

        /** Camera currently on screen, so motion alerts for it are not shown on top of it. */
        @Volatile var visibleCameraId: String? = null

        private val ZOOM_LEVELS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)
        private const val PAN_STEP = 0.1f

        private val PTZ_KEYS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "DirectionUp",
            KeyEvent.KEYCODE_DPAD_DOWN to "DirectionDown",
            KeyEvent.KEYCODE_DPAD_LEFT to "DirectionLeft",
            KeyEvent.KEYCODE_DPAD_RIGHT to "DirectionRight",
        )
    }
}
