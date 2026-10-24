package com.pux.camaratv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Hardware-decodes H.264/H.265 frames from the camera straight onto a Surface. */
class VideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var configured: Triple<Int, Int, Int>? = null
    private var frameIndex = 0L
    private val info = MediaCodec.BufferInfo()

    fun submit(frame: VideoFrame) {
        val key = Triple(frame.codec, frame.width, frame.height)
        if (codec == null || configured != key) {
            // The decoder can only start on a keyframe, which carries the parameter sets
            if (!frame.isKey) return
            configure(frame)
            configured = key
        }
        val mc = codec ?: return

        var index: Int
        do {
            index = mc.dequeueInputBuffer(10_000)
            drain(mc)
        } while (index < 0)

        val buffer = mc.getInputBuffer(index)!!
        buffer.clear()
        if (frame.data.size > buffer.capacity()) {
            mc.queueInputBuffer(index, 0, 0, 0, 0)
            return
        }
        buffer.put(frame.data)
        val fps = if (frame.fps in 1..60) frame.fps else 25
        mc.queueInputBuffer(index, 0, frame.data.size, frameIndex++ * 1_000_000L / fps, 0)
        drain(mc)
    }

    fun release() {
        codec?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        codec = null
    }

    private fun drain(mc: MediaCodec) {
        while (true) {
            val out = mc.dequeueOutputBuffer(info, 0)
            if (out >= 0) {
                mc.releaseOutputBuffer(out, true)
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return
            }
        }
    }

    private fun configure(frame: VideoFrame) {
        release()
        val hevc = frame.codec == DvripClient.CODEC_H265
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, frame.width, frame.height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        val nals = splitNals(frame.data)
        if (hevc) {
            // csd-0 = VPS + SPS + PPS
            val csd = ByteArrayOutputStream()
            nals.filter { hevcType(it) in 32..34 }.forEach { csd.write(START_CODE); csd.write(it) }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()))
        } else {
            nals.firstOrNull { avcType(it) == 7 }?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + it)) }
            nals.firstOrNull { avcType(it) == 8 }?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + it)) }
        }

        val mc = MediaCodec.createDecoderByType(mime)
        mc.configure(format, surface, null, 0)
        mc.start()
        codec = mc
        frameIndex = 0
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        private fun hevcType(nal: ByteArray) = if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3F
        private fun avcType(nal: ByteArray) = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

        /** Splits an Annex-B buffer into NAL units (without start codes). */
        fun splitNals(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>() // (start code position, payload position)
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) {
                        starts += i to i + 3; i += 3; continue
                    }
                    if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                        starts += i to i + 4; i += 4; continue
                    }
                }
                i++
            }
            return starts.mapIndexed { n, (_, payload) ->
                val end = if (n + 1 < starts.size) starts[n + 1].first else data.size
                data.copyOfRange(payload, end)
            }
        }
    }
}
