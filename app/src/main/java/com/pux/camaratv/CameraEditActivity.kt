package com.pux.camaratv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Add or edit a camera. Can be prefilled with an IP/port found by [ScanActivity]. */
class CameraEditActivity : Activity() {

    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private var existing: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_edit)
        name = findViewById(R.id.name)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)

        existing = CameraStore.get(this, intent.getStringExtra(EXTRA_ID))
        val cam = existing
        if (cam != null) {
            findViewById<TextView>(R.id.title).text = "Editar cámara"
            name.setText(cam.name)
            host.setText(cam.host)
            port.setText(cam.port.toString())
            user.setText(cam.user)
            password.setText(cam.password)
        } else {
            name.setText("Cámara ${CameraStore.all(this).size + 1}")
            host.setText(intent.getStringExtra(EXTRA_HOST) ?: "")
            port.setText(intent.getIntExtra(EXTRA_PORT, CameraStore.DEFAULT_PORT).toString())
            user.setText("admin")
        }

        findViewById<Button>(R.id.test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        (if (host.text.isEmpty()) host else password).requestFocus()
    }

    /** Validates the form; shows the problem and returns null if something is wrong. */
    private fun readForm(): Camera? {
        val h = host.text.toString().trim()
        val p = port.text.toString().trim().toIntOrNull()
        if (!IP_REGEX.matches(h)) {
            showStatus("La IP no es válida (ejemplo: 192.168.100.71)", error = true)
            host.requestFocus()
            return null
        }
        if (p == null || p !in 1..65535) {
            showStatus("El puerto debe ser un número entre 1 y 65535", error = true)
            port.requestFocus()
            return null
        }
        return Camera(
            existing?.id ?: CameraStore.newId(),
            name.text.toString().trim().ifEmpty { h },
            h,
            p,
            user.text.toString().trim(),
            password.text.toString(),
        )
    }

    private fun testConnection() {
        val cam = readForm() ?: return
        showStatus("Probando conexión con ${cam.host}:${cam.port}…")
        Thread {
            val result = try {
                DvripClient(cam).use { c ->
                    c.connect()
                    val info = c.systemInfo()
                    "✔ Conectado. Modelo: ${info.optString("DeviceModel", "desconocido")}" to false
                }
            } catch (e: Exception) {
                "✘ ${e.message ?: "No se pudo conectar"}" to true
            }
            runOnUiThread { showStatus(result.first, result.second) }
        }.start()
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
        private val IP_REGEX = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    }
}
