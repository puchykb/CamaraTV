package com.pux.camaratv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/** Plays the camera's G.711 (A-law / µ-law) 8 kHz mono audio. */
class AudioPlayer {

    private var track: AudioTrack? = null
    private var pcm = ShortArray(0)

    fun play(codec: Int, data: ByteArray) {
        val table = when (codec) {
            CODEC_G711A -> ALAW
            CODEC_G711U -> ULAW
            else -> return
        }
        val t = track ?: createTrack().also { track = it }
        if (pcm.size < data.size) pcm = ShortArray(data.size)
        for (i in data.indices) pcm[i] = table[data[i].toInt() and 0xFF]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Never stall the video: if the buffer is full, drop this chunk
            t.write(pcm, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            t.write(pcm, 0, data.size)
        }
    }

    fun release() {
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 2 * 2) // ~0.5 s of audio at most
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.play()
        return t
    }

    companion object {
        const val CODEC_G711U = 10
        const val CODEC_G711A = 14
        private const val SAMPLE_RATE = 8000

        private val ALAW = ShortArray(256) { i ->
            val a = i xor 0x55
            val exponent = (a shr 4) and 0x07
            val mantissa = a and 0x0F
            var sample = if (exponent == 0) (mantissa shl 4) + 8 else ((mantissa shl 4) + 0x108) shl (exponent - 1)
            if (a and 0x80 == 0) sample = -sample
            sample.toShort()
        }

        private val ULAW = ShortArray(256) { i ->
            val u = i.inv() and 0xFF
            val exponent = (u shr 4) and 0x07
            val mantissa = u and 0x0F
            val sample = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
            (if (u and 0x80 != 0) -sample else sample).toShort()
        }
    }
}
