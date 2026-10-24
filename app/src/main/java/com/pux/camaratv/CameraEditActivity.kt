package com.pux.camaratv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** Add or edit a camera. Can be prefilled with what [ScanActivity] found. */
class CameraEditActivity : Activity() {

    private lateinit var typeGroup: RadioGroup
    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private var existing: Camera? = null

    private val typeButtons by lazy {
        mapOf(
            CameraType.XMEYE to findViewById<RadioButton>(R.id.typeXmeye),
            CameraType.ONVIF to findViewById<RadioButton>(R.id.typeOnvif),
            CameraType.RTSP to findViewById<RadioButton>(R.id.typeRtsp),
        )
    }

    private val selectedType: CameraType
        get() = typeButtons.entries.first { it.value.isChecked }.key

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_edit)
        typeGroup = findViewById(R.id.type)
        name = findViewById(R.id.name)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        url = findViewById(R.id.url)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        typeButtons.forEach { (type, button) -> button.text = type.label }

        existing = CameraStore.get(this, intent.getStringExtra(EXTRA_ID))
        val cam = existing
        val type: CameraType
        if (cam != null) {
            findViewById<TextView>(R.id.title).text = "Editar cámara"
            type = cam.type
            name.setText(cam.name)
            host.setText(cam.host)
            port.setText(cam.port.toString())
            url.setText(cam.rtspUrl)
            user.setText(cam.user)
            password.setText(cam.password)
        } else {
            type = intent.getStringExtra(EXTRA_TYPE)?.let { runCatching { CameraType.valueOf(it) }.getOrNull() } ?: CameraType.ONVIF
            name.setText(intent.getStringExtra(EXTRA_NAME) ?: "Cámara ${CameraStore.all(this).size + 1}")
            host.setText(intent.getStringExtra(EXTRA_HOST) ?: "")
            port.setText(intent.getIntExtra(EXTRA_PORT, type.defaultPort).toString())
            intent.getStringExtra(EXTRA_HOST)?.let { url.setText("rtsp://$it:554/") }
            user.setText("admin")
        }
        typeButtons.getValue(type).isChecked = true
        updateFields()

        typeGroup.setOnCheckedChangeListener { _, _ ->
            // Swap the port only if it still holds another type's default
            val p = port.text.toString().toIntOrNull()
            if (p == null || CameraType.entries.any { it.defaultPort == p }) port.setText(selectedType.defaultPort.toString())
            updateFields()
        }
        findViewById<Button>(R.id.test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        if (cam == null && host.text.isNotEmpty()) password.requestFocus()
    }

    private fun updateFields() {
        val rtsp = selectedType == CameraType.RTSP
        listOf(R.id.hostLabel, R.id.host, R.id.portLabel, R.id.port).forEach { findViewById<View>(it).visibility = if (rtsp) View.GONE else View.VISIBLE }
        listOf(R.id.urlLabel, R.id.url, R.id.urlHelp).forEach { findViewById<View>(it).visibility = if (rtsp) View.VISIBLE else View.GONE }
    }

    /** Validates the form; shows the problem and returns null if something is wrong. */
    private fun readForm(): Camera? {
        val type = selectedType
        var h = host.text.toString().trim()
        var p = port.text.toString().trim().toIntOrNull()
        val u = url.text.toString().trim()
        if (type == CameraType.RTSP) {
            val parsed = runCatching { URI(u) }.getOrNull()
            if (parsed?.scheme?.lowercase() != "rtsp" || parsed.host.isNullOrEmpty()) {
                showStatus("La URL debe empezar con rtsp:// (ejemplo: rtsp://192.168.1.64:554/stream1)", error = true)
                url.requestFocus()
                return null
            }
            h = parsed.host
            p = if (parsed.port > 0) parsed.port else 554
        } else {
            if (!IP_REGEX.matches(h)) {
                showStatus("La IP no es válida (ejemplo: 192.168.1.64)", error = true)
                host.requestFocus()
                return null
            }
            if (p == null || p !in 1..65535) {
                showStatus("El puerto debe ser un número entre 1 y 65535", error = true)
                port.requestFocus()
                return null
            }
        }
        return Camera(
            existing?.id ?: CameraStore.newId(),
            name.text.toString().trim().ifEmpty { h },
            h,
            p!!,
            user.text.toString().trim(),
            password.text.toString(),
            type,
            if (type == CameraType.RTSP) u else "",
        )
    }

    private fun testConnection() {
        val cam = readForm() ?: return
        showStatus("Probando conexión con ${cam.host}:${cam.port}…")
        Thread {
            val result = try {
                when (cam.type) {
                    CameraType.XMEYE -> DvripClient(cam).use { c ->
                        c.connect()
                        "✔ Conectado. Modelo: ${c.systemInfo().optString("DeviceModel", "desconocido")}"
                    }
                    CameraType.ONVIF -> {
                        val c = OnvifClient(cam)
                        c.connect()
                        val device = c.deviceName().ifEmpty { "cámara ONVIF" }
                        val profiles = c.profiles()
                        if (profiles.isEmpty()) throw OnvifException("La cámara no tiene perfiles de video")
                        c.streamUri(profiles[0])
                        "✔ Conectado: $device" + if (c.hasPtz) " · con PTZ" else ""
                    }
                    CameraType.RTSP -> {
                        if (!rtspAnswers(cam.host, cam.port)) throw java.io.IOException("No responde como servidor RTSP")
                        "✔ La cámara responde. Guarda y ábrela para comprobar el video."
                    }
                } to false
            } catch (e: Exception) {
                "✘ ${e.message ?: "No se pudo conectar"}" to true
            }
            runOnUiThread { showStatus(result.first, result.second) }
        }.start()
    }

    /** Sends an RTSP OPTIONS request and checks that something RTSP answers. */
    private fun rtspAnswers(host: String, port: Int): Boolean =
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 4000
            s.getOutputStream().write("OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val reply = ByteArray(64)
            val n = s.getInputStream().read(reply)
            n > 0 && String(reply, 0, n).startsWith("RTSP/")
        }

    private fun save() {
        val cam = readForm() ?: return
        CameraStore.save(this, cam)
        finish()
    }

    private fun showStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) 0xFFEF9A9A.toInt() else 0xFFA5D6A7.toInt())
        status.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TYPE = "type"
        const val EXTRA_NAME = "name"
        private val IP_REGEX = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    }
}
