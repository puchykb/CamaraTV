package com.pux.camaratv

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * DVR playback for XMEye cameras. A 24-hour timeline shows the recorded clips; the remote scrubs a
 * playhead and plays from it.
 *
 * Browsing: ◄►=mover el cursor · ▲▼=cambiar de día · OK=reproducir · Atrás=salir.
 * Reproduciendo: OK=pausa (vuelve a la línea de tiempo) · ◄►=retroceder/adelantar · Atrás=pausa.
 */
class RecordingsActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var videoView: TextureView
    private lateinit var timeline: TimelineView
    private lateinit var info: TextView
    private lateinit var header: TextView
    private val main = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var surface: Surface? = null

    private val cal = Calendar.getInstance()
    private var recordings: List<Recording> = emptyList()
    private var lastEndSec = 0

    private enum class State { BROWSING, PLAYING }
    private var state = State.BROWSING

    private val generation = AtomicInteger(0)
    private val loadToken = AtomicInteger(0)
    @Volatile private var worker: Thread? = null
    private var playFromSec = 0
    private var playStartWall = 0L

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val prettyFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = CameraStore.get(this, intent.getStringExtra(MainActivity.EXTRA_CAMERA_ID))
        if (camera?.type != CameraType.XMEYE) { finish(); return }

        videoView = TextureView(this).apply { surfaceTextureListener = this@RecordingsActivity }

        header = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0x99000000.toInt())
            val p = dp(14)
            setPadding(p, dp(8), p, dp(8))
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        timeline = TimelineView(this)

        val bottom = FrameLayout(this).apply { setBackgroundColor(0xAA000000.toInt()) }
        bottom.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(10), dp(24), 0) })
        bottom.addView(timeline, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(videoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150), Gravity.BOTTOM))
        setContentView(root)

        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        loadDay()
    }

    override fun onStop() {
        stopWorker()
        state = State.BROWSING
        timeline.setPlaying(false)
        super.onStop()
    }

    // --- Loading a day's recordings ---

    private fun loadDay() {
        stopWorker()
        state = State.BROWSING
        timeline.setPlaying(false)
        timeline.setSpans(emptyList())
        val cam = camera ?: return
        header.text = "${cam.name} · ${prettyFmt.format(cal.time).replaceFirstChar { it.uppercase() }}"
        info.text = "Buscando grabaciones…"
        val dayStr = dayFmt.format(cal.time)
        val token = loadToken.incrementAndGet()
        Thread({
            try {
                val list = DvripClient(cam).use { it.connect(); it.queryRecordings("$dayStr 00:00:00", "$dayStr 23:59:59") }
                val spans = list.map { TimelineView.Span(secOfDay(it.begin, dayStr), secOfDay(it.end, dayStr), it.isMotion) }
                val lastEnd = spans.maxOfOrNull { it.endSec } ?: 0
                main.post {
                    if (token != loadToken.get()) return@post
                    recordings = list
                    lastEndSec = lastEnd
                    timeline.setSpans(spans)
                    timeline.setCursor(if (spans.isEmpty()) nowSecIfToday(dayStr) else spans.last().startSec)
                    info.text = if (list.isEmpty()) "No hay grabaciones este día · ▲▼ cambiar de día · Atrás: salir"
                        else "${list.size} grabaciones · ◄► mover · OK reproducir · ▲▼ día · Atrás salir"
                }
            } catch (e: Exception) {
                main.post { if (token == loadToken.get()) info.text = "No se pudo leer las grabaciones: ${e.message ?: "sin conexión"}" }
            }
        }, "rec-load").start()
    }

    // --- Playback ---

    private fun startPlayback(fromSec: Int) {
        val s = surface ?: run { info.text = "Preparando video…"; return }
        val cam = camera ?: return
        // Snap to the first clip at or after the cursor
        val target = recordings.map { secOfDay(it.begin, dayFmt.format(cal.time)) to secOfDay(it.end, dayFmt.format(cal.time)) }
            .firstOrNull { it.second > fromSec }
        if (target == null) { info.text = "No hay grabación desde aquí · movete a un tramo marcado"; return }
        val start = maxOf(fromSec, target.first)

        stopWorker()
        state = State.PLAYING
        playFromSec = start
        playStartWall = 0L
        timeline.setPlaying(true)
        timeline.setCursor(start)
        info.text = "Reproduciendo…  OK: pausa · ◄► ±30 s · Atrás: volver"

        val myGen = generation.incrementAndGet()
        val endSec = maxOf(lastEndSec + 2, start + 5).coerceAtMost(TimelineView.DAY)
        val t = Thread({
            val client = DvripClient(cam)
            val decoder = VideoDecoder(s)
            val audio = AudioPlayer()
            var first = true
            try {
                client.connect()
                client.playback(
                    stampFor(start), stampFor(endSec),
                    onFrame = { f ->
                        if (generation.get() == myGen) {
                            decoder.submit(f)
                            if (first && f.isKey) { first = false; main.post { onPlaybackStarted(myGen, start) } }
                        }
                    },
                    onAudio = { c, d -> if (!first && generation.get() == myGen) audio.play(c, d) },
                    shouldStop = { generation.get() != myGen },
                )
            } catch (_: Exception) {
                // falls through to the ending below
            } finally {
                client.close(); decoder.release(); audio.release()
                main.post { onPlaybackEnded(myGen) }
            }
        }, "rec-play")
        worker = t
        t.start()
    }

    private fun onPlaybackStarted(gen: Int, start: Int) {
        if (gen != generation.get() || state != State.PLAYING) return
        playFromSec = start
        playStartWall = System.currentTimeMillis()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.PLAYING || playStartWall == 0L) return
            val cur = playFromSec + ((System.currentTimeMillis() - playStartWall) / 1000).toInt()
            if (cur >= lastEndSec) { stopToBrowse("Fin de las grabaciones del día"); return }
            timeline.setCursor(cur)
            main.postDelayed(this, 500)
        }
    }

    private fun onPlaybackEnded(gen: Int) {
        if (gen != generation.get()) return // a newer playback already took over
        if (state == State.PLAYING) stopToBrowse("Fin de la grabación · OK para reproducir de nuevo")
    }

    /** Pause/stop playback and return to scrubbing at the current cursor. */
    private fun stopToBrowse(message: String) {
        stopWorker()
        state = State.BROWSING
        timeline.setPlaying(false)
        info.text = message
    }

    private fun stopWorker() {
        generation.incrementAndGet() // signals the running playback (if any) to stop
        main.removeCallbacks(ticker)
        playStartWall = 0L
        worker?.let { w -> Thread({ try { w.join(1500) } catch (_: InterruptedException) {} }, "rec-join").start() }
        worker = null
    }

    // --- Remote control ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (state) {
            State.BROWSING -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-stepFor(event)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(stepFor(event)); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (event.repeatCount == 0) changeDay(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (event.repeatCount == 0) changeDay(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount == 0) startPlayback(timeline.getCursor()); return true
                }
            }
            State.PLAYING -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.repeatCount == 0) stopToBrowse("Pausa · ◄► mover · OK reproducir"); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.repeatCount == 0) startPlayback((timeline.getCursor() - 30).coerceAtLeast(0)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.repeatCount == 0) startPlayback(timeline.getCursor() + 30); return true }
                KeyEvent.KEYCODE_BACK -> { stopToBrowse("Pausa · ◄► mover · OK reproducir"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun scrub(deltaSec: Int) {
        timeline.setCursor((timeline.getCursor() + deltaSec).coerceIn(0, TimelineView.DAY))
    }

    /** Fine control on a tap, faster while the key is held. */
    private fun stepFor(event: KeyEvent) = when {
        event.repeatCount < 2 -> 60
        event.repeatCount < 10 -> 300
        else -> 900
    }

    private fun changeDay(delta: Int) {
        cal.add(Calendar.DATE, delta)
        loadDay()
    }

    // --- Video surface ---

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { surface = Surface(texture) }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopWorker()
        surface?.release(); surface = null
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    // --- Time helpers ---

    /** Builds a "yyyy-MM-dd HH:mm:ss" stamp for [sec] seconds into the selected day. */
    private fun stampFor(sec: Int): String {
        val c = cal.clone() as Calendar
        c.add(Calendar.SECOND, sec)
        return fullFmt.format(c.time)
    }

    /** Seconds-of-day of [stamp] relative to [dayStr], clamped to the day's bounds. */
    private fun secOfDay(stamp: String, dayStr: String): Int {
        if (stamp.length < 19) return 0
        val date = stamp.substring(0, 10)
        if (date < dayStr) return 0
        if (date > dayStr) return TimelineView.DAY
        val h = stamp.substring(11, 13).toIntOrNull() ?: 0
        val m = stamp.substring(14, 16).toIntOrNull() ?: 0
        val s = stamp.substring(17, 19).toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    private fun nowSecIfToday(dayStr: String): Int {
        if (dayFmt.format(Calendar.getInstance().time) != dayStr) return 0
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
