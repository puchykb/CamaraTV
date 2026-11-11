package com.pux.camaratv

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A complete video frame (one access unit, Annex-B) pulled out of the camera stream. */
class VideoFrame(val data: ByteArray, val isKey: Boolean, val codec: Int, val width: Int, val height: Int, val fps: Int)

/** One recorded clip on the camera's storage. Times are "yyyy-MM-dd HH:mm:ss". */
class Recording(val begin: String, val end: String, val fileName: String) {
    /** The camera tags motion/human clips in the file name ([M]/[H]); scheduled ones are [R]. */
    val isMotion: Boolean get() = fileName.contains("[M]") || fileName.contains("[H]")
}

class DvripException(message: String) : IOException(message)

/**
 * Minimal client for the Xiongmai "DVRIP/Sofia" protocol (XMEye / iCSee cameras, TCP port 34567).
 * Only implements what is needed for live view: login, keep-alive and the OPMonitor stream.
 */
class DvripClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
) : Closeable {

    constructor(camera: Camera) : this(camera.host, camera.port, camera.user, camera.password)

    private val socket = Socket()
    private lateinit var input: DataInputStream
    private var session = 0
    private var sessionHex = "0x00000000"
    private var sequence = 0
    private var aliveIntervalMs = 20_000L

    fun connect() {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))

        val resp = request(
            LOGIN_REQ,
            JSONObject()
                .put("EncryptType", "MD5")
                .put("LoginType", "DVRIP-Web")
                .put("PassWord", sofiaHash(password))
                .put("UserName", user),
        )
        when (val ret = resp.optInt("Ret")) {
            100 -> Unit
            203 -> throw DvripException("Usuario o contraseña incorrectos")
            205 -> throw DvripException("Usuario bloqueado en la cámara")
            else -> throw DvripException("La cámara rechazó el login (código $ret)")
        }
        sessionHex = resp.getString("SessionID")
        session = sessionHex.removePrefix("0x").toLong(16).toInt()
        aliveIntervalMs = (resp.optInt("AliveInterval", 30) * 1000L * 2 / 3).coerceAtLeast(5000)
    }

    /**
     * Starts the live stream and calls [onFrame] for every video frame and [onAudio] (codec, data)
     * for every audio chunk until the socket closes.
     */
    fun stream(mainStream: Boolean, onFrame: (VideoFrame) -> Unit, onAudio: (Int, ByteArray) -> Unit) {
        val param = JSONObject()
            .put("Channel", 0)
            .put("CombinMode", "NONE")
            .put("StreamType", if (mainStream) "Main" else "Extra1")
            .put("TransMode", "TCP")
        fun monitor(action: String) = JSONObject()
            .put("Name", "OPMonitor")
            .put("SessionID", sessionHex)
            .put("OPMonitor", JSONObject().put("Action", action).put("Parameter", param))

        val claim = request(MONITOR_CLAIM_REQ, monitor("Claim"))
        if (claim.optInt("Ret") != 100) throw DvripException("La cámara no permitió ver el video (código ${claim.optInt("Ret")})")
        send(MONITOR_REQ, monitor("Start"))

        val parser = MediaParser(onFrame, onAudio)
        var nextKeepAlive = System.currentTimeMillis() + aliveIntervalMs
        while (true) {
            val (msgId, body) = readPacket()
            if (msgId == MONITOR_DATA) parser.feed(body)
            if (System.currentTimeMillis() >= nextKeepAlive) {
                send(KEEPALIVE_REQ, JSONObject().put("Name", "KeepAlive").put("SessionID", sessionHex))
                nextKeepAlive = System.currentTimeMillis() + aliveIntervalMs
            }
        }
    }

    /**
     * Lists the clips recorded between [from] and [to] ("yyyy-MM-dd HH:mm:ss"), paging through the
     * camera's OPFileQuery responses (which cap the number of entries per reply).
     */
    fun queryRecordings(from: String, to: String): List<Recording> {
        val out = ArrayList<Recording>()
        val seen = HashSet<String>()
        var begin = from
        var guard = 0
        while (guard++ < 50) {
            val resp = request(
                FILEQUERY_REQ,
                JSONObject()
                    .put("Name", "OPFileQuery")
                    .put("SessionID", sessionHex)
                    .put(
                        "OPFileQuery",
                        JSONObject()
                            .put("BeginTime", begin)
                            .put("EndTime", to)
                            .put("Channel", 0)
                            .put("DriverTypeMask", "0x0000FFFF")
                            .put("Event", "*")
                            .put("Type", "h264")
                            .put("StreamType", "0x00000000"),
                    ),
            )
            val arr = resp.optJSONArray("OPFileQuery") ?: break
            if (arr.length() == 0) break
            var last = begin
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = o.optString("FileName")
                if (seen.add(file)) {
                    out += Recording(o.optString("BeginTime"), o.optString("EndTime"), file)
                    added++
                }
                val e = o.optString("EndTime")
                if (e > last) last = e
            }
            // Times sort lexicographically, so keep paging from the last clip until no progress.
            if (added == 0 || last <= begin || last >= to) break
            begin = last
        }
        out.sortBy { it.begin }
        return out
    }

    /**
     * Plays the recording for the time range [from]..[to] ("yyyy-MM-dd HH:mm:ss"), reusing the same
     * frame format as live view. Calls [onFrame]/[onAudio] until [shouldStop] is true, the range ends
     * (socket EOF) or the connection drops.
     */
    fun playback(
        from: String,
        to: String,
        onFrame: (VideoFrame) -> Unit,
        onAudio: (Int, ByteArray) -> Unit,
        shouldStop: () -> Boolean,
    ) {
        val param = JSONObject()
            .put("PlayMode", "ByTime")
            .put("FileName", "")
            .put("StreamType", 0)
            .put("Value", 0)
            .put("TransMode", "TCP")
            .put("BeginTime", from)
            .put("EndTime", to)
            .put("Channel", 0)
        fun op(action: String): JSONObject {
            val inner = JSONObject().put("Action", action).put("Parameter", param)
            if (action == "Start") inner.put("StartTime", from).put("EndTime", to)
            return JSONObject().put("Name", "OPPlayBack").put("SessionID", sessionHex).put("OPPlayBack", inner)
        }

        val claim = request(PLAYBACK_CLAIM_REQ, op("Claim"))
        if (claim.optInt("Ret") != 100) throw DvripException("La cámara no permitió la reproducción (código ${claim.optInt("Ret")})")
        send(PLAYBACK_REQ, op("Start"))

        val parser = MediaParser(onFrame, onAudio)
        socket.soTimeout = 8000
        var nextKeepAlive = System.currentTimeMillis() + aliveIntervalMs
        try {
            while (!shouldStop()) {
                val packet = try {
                    readPacket()
                } catch (_: SocketTimeoutException) {
                    null // quiet moment: fall through to the keep-alive / stop checks
                } catch (_: EOFException) {
                    break // the camera closed the stream: end of the recording
                }
                if (packet != null && packet.first == PLAYBACK_DATA) parser.feed(packet.second)
                if (System.currentTimeMillis() >= nextKeepAlive) {
                    send(KEEPALIVE_REQ, JSONObject().put("Name", "KeepAlive").put("SessionID", sessionHex))
                    nextKeepAlive = System.currentTimeMillis() + aliveIntervalMs
                }
            }
        } finally {
            try { send(PLAYBACK_REQ, op("Stop")) } catch (_: Exception) {}
        }
    }

    /** Model, firmware, serial… as reported by the camera. */
    fun systemInfo(): JSONObject {
        val resp = request(SYSINFO_REQ, JSONObject().put("Name", "SystemInfo").put("SessionID", sessionHex))
        return resp.optJSONObject("SystemInfo") ?: JSONObject()
    }

    /**
     * Subscribes to the camera's alarms and calls [onAlarm] with each "AlarmInfo" object
     * (Event = VideoMotion / HumanDetect…, Status = Start / Stop) until the connection drops.
     */
    fun watchAlarms(onAlarm: (JSONObject) -> Unit) {
        val guard = request(GUARD_REQ, JSONObject().put("Name", "").put("SessionID", sessionHex))
        if (guard.optInt("Ret") != 100) throw DvripException("La cámara no permitió recibir alarmas (código ${guard.optInt("Ret")})")

        socket.soTimeout = 5000
        var lastPacket = System.currentTimeMillis()
        var nextKeepAlive = lastPacket + aliveIntervalMs
        while (true) {
            try {
                val (msgId, body) = readPacket()
                lastPacket = System.currentTimeMillis()
                if (msgId == ALARM_EVENT) {
                    val json = JSONObject(String(body, Charsets.UTF_8).trimEnd('\u0000', '\n'))
                    onAlarm(json.optJSONObject("AlarmInfo") ?: json)
                }
            } catch (_: SocketTimeoutException) {
                // Quiet period: just fall through to the keep-alive check
            }
            val now = System.currentTimeMillis()
            if (now - lastPacket > aliveIntervalMs * 3) throw DvripException("La cámara dejó de responder")
            if (now >= nextKeepAlive) {
                send(KEEPALIVE_REQ, JSONObject().put("Name", "KeepAlive").put("SessionID", sessionHex))
                nextKeepAlive = now + aliveIntervalMs
            }
        }
    }

    /**
     * Starts ([stop] = false) or stops a PTZ move such as "DirectionUp". Fire-and-forget: the reply
     * arrives on the stream socket and is skipped by [stream], so it is safe to call while streaming.
     */
    fun ptz(command: String, stop: Boolean) {
        val param = JSONObject()
            .put("AUX", JSONObject().put("Number", 0).put("Status", "On"))
            .put("Channel", 0)
            .put("MenuOpts", "Enter")
            .put("POINT", JSONObject().put("bottom", 0).put("left", 0).put("right", 0).put("top", 0))
            .put("Pattern", "SetBegin")
            .put("Preset", if (stop) -1 else 65535)
            .put("Step", 5)
            .put("Tour", 0)
        send(
            PTZ_REQ,
            JSONObject()
                .put("Name", "OPPTZControl")
                .put("SessionID", sessionHex)
                .put("OPPTZControl", JSONObject().put("Command", command).put("Parameter", param)),
        )
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    private fun request(msgId: Int, json: JSONObject): JSONObject {
        send(msgId, json)
        while (true) {
            val (id, body) = readPacket()
            if (id == msgId + 1) return JSONObject(String(body, Charsets.UTF_8).trimEnd('\u0000', '\n'))
        }
    }

    @Synchronized
    private fun send(msgId: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8) + byteArrayOf(0x0a, 0x00)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(0xFF.toByte()).put(0x01).put(0).put(0)
            .putInt(session)
            .putInt(sequence++)
            .put(0).put(0)
            .putShort(msgId.toShort())
            .putInt(payload.size)
        val out = socket.getOutputStream()
        out.write(header.array() + payload)
        out.flush()
    }

    private fun readPacket(): Pair<Int, ByteArray> {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (header[0] != 0xFF.toByte()) throw DvripException("Respuesta inválida de la cámara")
        val msgId = bb.getShort(14).toInt() and 0xFFFF
        val length = bb.getInt(16)
        if (length < 0 || length > 8 * 1024 * 1024) throw DvripException("Paquete inválido ($length bytes)")
        val body = ByteArray(length)
        input.readFully(body)
        return msgId to body
    }

    /**
     * Reassembles media frames from the DVRIP data packets. Frames can span several packets.
     * Each frame starts with 00 00 01 + type: FC/FE = I-frame, FD = P-frame, FA = audio, F9 = info.
     */
    private class MediaParser(
        private val onFrame: (VideoFrame) -> Unit,
        private val onAudio: (Int, ByteArray) -> Unit,
    ) {
        private var buf = ByteArray(1024 * 1024)
        private var len = 0
        private var codec = 0
        private var width = 0
        private var height = 0
        private var fps = 0

        fun feed(data: ByteArray) {
            if (len + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + data.size))
            System.arraycopy(data, 0, buf, len, data.size)
            len += data.size

            var pos = 0
            while (true) {
                if (len - pos < 8) break
                if (buf[pos].toInt() != 0 || buf[pos + 1].toInt() != 0 || buf[pos + 2].toInt() != 1) {
                    pos++ // lost sync: resynchronize on the next start marker
                    continue
                }
                val type = buf[pos + 3].toInt() and 0xFF
                val headerLen: Int
                val payloadLen: Int
                when (type) {
                    0xFC, 0xFE -> {
                        if (len - pos < 16) break
                        headerLen = 16
                        payloadLen = le32(pos + 12)
                    }
                    0xFD -> {
                        headerLen = 8
                        payloadLen = le32(pos + 4)
                    }
                    0xFA, 0xF9 -> {
                        headerLen = 8
                        payloadLen = (buf[pos + 6].toInt() and 0xFF) or ((buf[pos + 7].toInt() and 0xFF) shl 8)
                    }
                    else -> {
                        pos++
                        continue
                    }
                }
                if (payloadLen < 0 || payloadLen > 4 * 1024 * 1024) {
                    pos++
                    continue
                }
                if (len - pos < headerLen + payloadLen) break

                if (type == 0xFC || type == 0xFE) {
                    codec = buf[pos + 4].toInt() and 0xFF
                    fps = buf[pos + 5].toInt() and 0xFF
                    width = (buf[pos + 6].toInt() and 0xFF) * 8
                    height = (buf[pos + 7].toInt() and 0xFF) * 8
                }
                if (type == 0xFC || type == 0xFE || type == 0xFD) {
                    val frame = buf.copyOfRange(pos + headerLen, pos + headerLen + payloadLen)
                    onFrame(VideoFrame(frame, type != 0xFD, codec, width, height, fps))
                } else if (type == 0xFA) {
                    onAudio(buf[pos + 4].toInt() and 0xFF, buf.copyOfRange(pos + headerLen, pos + headerLen + payloadLen))
                }
                pos += headerLen + payloadLen
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, len - pos)
                len -= pos
            }
        }

        private fun le32(i: Int) = (buf[i].toInt() and 0xFF) or
            ((buf[i + 1].toInt() and 0xFF) shl 8) or
            ((buf[i + 2].toInt() and 0xFF) shl 16) or
            ((buf[i + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        const val CODEC_H264 = 2
        const val CODEC_H265 = 3

        private const val HEADER_SIZE = 20
        private const val LOGIN_REQ = 1000
        private const val KEEPALIVE_REQ = 1006
        private const val SYSINFO_REQ = 1020
        private const val PTZ_REQ = 1400
        private const val GUARD_REQ = 1500
        private const val ALARM_EVENT = 1504
        private const val MONITOR_REQ = 1410
        private const val MONITOR_DATA = 1412
        private const val MONITOR_CLAIM_REQ = 1413
        private const val FILEQUERY_REQ = 1440
        private const val PLAYBACK_REQ = 1420
        private const val PLAYBACK_DATA = 1422
        private const val PLAYBACK_CLAIM_REQ = 1424

        /** XMEye "Sofia" password hash: MD5 folded into 8 alphanumeric characters. */
        fun sofiaHash(password: String): String {
            val d = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 8) {
                val n = ((d[2 * i].toInt() and 0xFF) + (d[2 * i + 1].toInt() and 0xFF)) % 62
                sb.append(
                    when {
                        n < 10 -> '0' + n
                        n < 36 -> 'A' + (n - 10)
                        else -> 'a' + (n - 36)
                    },
                )
            }
            return sb.toString()
        }
    }
}
