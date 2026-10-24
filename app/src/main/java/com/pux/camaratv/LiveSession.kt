package com.pux.camaratv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.IOException
import java.util.concurrent.Executors

enum class PtzDirection(val xmeyeCommand: String, val x: Float, val y: Float) {
    UP("DirectionUp", 0f, 0.5f),
    DOWN("DirectionDown", 0f, -0.5f),
    LEFT("DirectionLeft", -0.5f, 0f),
    RIGHT("DirectionRight", 0.5f, 0f),
}

/** UI hooks a session reports to. Both may be called from any thread. */
class SessionListener(
    val status: (text: String, autoHide: Boolean) -> Unit,
    val firstFrame: () -> Unit,
)

/** A live connection to one camera, drawing onto a Surface. start()/stop() are called on the main thread. */
interface LiveSession {
    val supportsQuality: Boolean
    fun start()
    fun stop()
    fun ptz(direction: PtzDirection, stop: Boolean)

    companion object {
        fun create(context: Context, camera: Camera, surface: Surface, mainStream: Boolean, listener: SessionListener): LiveSession =
            when (camera.type) {
                CameraType.XMEYE -> XmeyeSession(camera, surface, mainStream, listener)
                CameraType.ONVIF, CameraType.RTSP -> RtspSession(context, camera, surface, mainStream, listener)
            }
    }
}

/** XMEye / iCSee cameras: own protocol on port 34567, decoded with MediaCodec. */
private class XmeyeSession(
    private val camera: Camera,
    private val surface: Surface,
    private val mainStream: Boolean,
    private val listener: SessionListener,
) : LiveSession {

    override val supportsQuality = true

    @Volatile private var running = false
    @Volatile private var client: DvripClient? = null
    private var worker: Thread? = null
    private val ptzExecutor = Executors.newSingleThreadExecutor()

    override fun start() {
        running = true
        worker = Thread({ loop() }, "xmeye").apply { start() }
    }

    override fun stop() {
        running = false
        client?.close()
        worker?.interrupt()
        worker?.join(2000)
        ptzExecutor.shutdown()
    }

    override fun ptz(direction: PtzDirection, stop: Boolean) {
        val c = client ?: return
        ptzExecutor.execute {
            try { c.ptz(direction.xmeyeCommand, stop) } catch (_: IOException) {}
        }
    }

    private fun loop() {
        while (running) {
            listener.status("Conectando a ${camera.name}…", false)
            val c = DvripClient(camera)
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
                            listener.firstFrame()
                        }
                    },
                    onAudio = { codec, data -> if (!firstFrame) audio.play(codec, data) },
                )
            } catch (e: Exception) {
                if (!running) break
                listener.status("${camera.name}: sin conexión (${e.message ?: e.javaClass.simpleName}). Reintentando…", false)
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            } finally {
                c.close()
                decoder.release()
                audio.release()
            }
        }
    }
}

/** ONVIF cameras (stream URL asked to the camera) and plain RTSP URLs, played with ExoPlayer. */
@OptIn(UnstableApi::class)
private class RtspSession(
    private val context: Context,
    private val camera: Camera,
    private val surface: Surface,
    private val mainStream: Boolean,
    private val listener: SessionListener,
) : LiveSession {

    override val supportsQuality = camera.type == CameraType.ONVIF

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var player: ExoPlayer? = null
    @Volatile private var onvif: OnvifClient? = null
    @Volatile private var profile: String? = null

    override fun start() {
        running = true
        connect()
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        executor.shutdownNow()
    }

    override fun ptz(direction: PtzDirection, stop: Boolean) {
        val client = onvif ?: return
        val token = profile ?: return
        if (!client.hasPtz) return
        executor.execute {
            try {
                if (stop) client.stop(token) else client.move(token, direction.x, direction.y)
            } catch (_: IOException) {}
        }
    }

    /** Resolves the stream URL off the main thread, then plays it. */
    private fun connect() {
        listener.status("Conectando a ${camera.name}…", false)
        executor.execute {
            try {
                val url = withCredentials(resolveUrl())
                main.post { if (running) play(url) }
            } catch (e: Exception) {
                retry(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun resolveUrl(): String {
        if (camera.type == CameraType.RTSP) {
            if (camera.rtspUrl.isBlank()) throw IOException("Falta la URL RTSP")
            return camera.rtspUrl.trim()
        }
        val client = OnvifClient(camera)
        client.connect()
        val profiles = client.profiles()
        if (profiles.isEmpty()) throw OnvifException("La cámara no tiene perfiles de video")
        val token = if (mainStream) profiles[0] else profiles.getOrElse(1) { profiles[0] }
        onvif = client
        profile = token
        return client.streamUri(token)
    }

    private fun play(url: String) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 500) // keep latency low
            .build()
        val p = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        player = p
        p.setVideoSurface(surface)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = listener.firstFrame()

            override fun onPlayerError(error: PlaybackException) {
                p.release()
                if (player === p) player = null
                retry(error.cause?.message ?: error.errorCodeName)
            }
        })
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true) // more reliable over Wi-Fi than UDP
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(url)),
        )
        p.playWhenReady = true
        p.prepare()
    }

    private fun retry(reason: String) {
        if (!running) return
        listener.status("${camera.name}: sin conexión ($reason). Reintentando…", false)
        main.postDelayed({ if (running) connect() }, 4000)
    }

    /** RTSP needs the credentials in the URL: rtsp://user:pass@host/… */
    private fun withCredentials(url: String): String {
        if (camera.user.isEmpty() || !url.contains("://")) return url
        val afterScheme = url.substringAfter("://")
        if (afterScheme.substringBefore('/').contains('@')) return url
        val userInfo = Uri.encode(camera.user) + ":" + Uri.encode(camera.password)
        return url.substringBefore("://") + "://" + userInfo + "@" + afterScheme
    }
}
