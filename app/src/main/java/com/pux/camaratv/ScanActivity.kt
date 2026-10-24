package com.pux.camaratv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import android.widget.TextView
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans the local /24 network for devices with the XMEye port (34567) open,
 * i.e. cameras and recorders this app can show, and offers to add them.
 */
class ScanActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private lateinit var again: Button
    private val found = sortedSetOf<Int>() // last octet of each IP found
    private var prefix = ""
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        again = findViewById(R.id.again)

        again.setOnClickListener { scan() }
        list.setOnItemClickListener { _, _, position, _ ->
            val ip = prefix + found.elementAt(position)
            val known = CameraStore.all(this).firstOrNull { it.host == ip }
            val intent = Intent(this, CameraEditActivity::class.java)
            if (known != null) {
                intent.putExtra(CameraEditActivity.EXTRA_ID, known.id)
            } else {
                intent.putExtra(CameraEditActivity.EXTRA_HOST, ip).putExtra(CameraEditActivity.EXTRA_PORT, CameraStore.DEFAULT_PORT)
            }
            startActivity(intent)
        }
        scan()
    }

    override fun onResume() {
        super.onResume()
        showResults() // refresh "ya agregada" after adding one
    }

    override fun onDestroy() {
        scanning = false
        super.onDestroy()
    }

    private fun scan() {
        if (scanning) return
        val own = localAddress()
        if (own == null) {
            status.text = "No se encontró la conexión de red del TV box. ¿Está conectado al Wi-Fi o por cable?"
            return
        }
        prefix = own.substringBeforeLast('.') + "."
        found.clear()
        showResults()
        scanning = true
        again.isEnabled = false
        progress.progress = 0
        status.text = "Buscando cámaras en ${prefix}1 – ${prefix}254…"

        Thread {
            val done = AtomicInteger()
            val pool = Executors.newFixedThreadPool(48)
            for (last in 1..254) {
                pool.execute {
                    if (scanning && isPortOpen(prefix + last, CameraStore.DEFAULT_PORT)) {
                        runOnUiThread {
                            found += last
                            showResults()
                        }
                    }
                    val n = done.incrementAndGet()
                    runOnUiThread { progress.progress = n }
                }
            }
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.MINUTES)
            runOnUiThread {
                scanning = false
                again.isEnabled = true
                status.text = when (found.size) {
                    0 -> "No se encontraron cámaras. Verifica que estén encendidas y en la misma red."
                    1 -> "Se encontró 1 cámara. Selecciónala para agregarla."
                    else -> "Se encontraron ${found.size} cámaras. Selecciona una para agregarla."
                }
            }
        }.start()
    }

    private fun showResults() {
        val known = CameraStore.all(this)
        val rows = found.map { last ->
            val ip = prefix + last
            val cam = known.firstOrNull { it.host == ip }
            mapOf(
                "title" to ip,
                "detail" to if (cam != null) "Ya agregada como \"${cam.name}\" · seleccionar para editar" else "Nueva · seleccionar para agregar",
            )
        }
        list.adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("title", "detail"), intArrayOf(android.R.id.text1, android.R.id.text2),
        )
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 700) }
        true
    } catch (_: Exception) {
        false
    }

    /** IPv4 address of the TV box on the local network (Wi-Fi or Ethernet). */
    private fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
}
