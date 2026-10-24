package com.pux.camaratv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds cameras on the local network three ways: ONVIF WS-Discovery (any brand),
 * the XMEye port (34567) and the RTSP port (554). Selecting one opens the add form prefilled.
 */
class ScanActivity : Activity() {

    private class Found(val ip: String) {
        val types = sortedSetOf<CameraType>()
        var onvifPort = 80
        var model = ""

        /** Best way to talk to it: XMEye gives alarms, ONVIF finds the stream by itself, RTSP is the fallback. */
        val bestType get() = types.firstOrNull() ?: CameraType.RTSP
    }

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private lateinit var again: Button
    private val found = sortedMapOf<Int, Found>() // by last octet
    private var prefix = ""
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        again = findViewById(R.id.again)
        progress.max = 254 + DISCOVERY_STEPS

        again.setOnClickListener { scan() }
        list.setOnItemClickListener { _, _, position, _ -> open(found.values.elementAt(position)) }
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

    private fun open(item: Found) {
        val known = CameraStore.all(this).firstOrNull { it.host == item.ip }
        val intent = Intent(this, CameraEditActivity::class.java)
        if (known != null) {
            intent.putExtra(CameraEditActivity.EXTRA_ID, known.id)
        } else {
            val type = item.bestType
            intent.putExtra(CameraEditActivity.EXTRA_HOST, item.ip)
                .putExtra(CameraEditActivity.EXTRA_TYPE, type.name)
                .putExtra(CameraEditActivity.EXTRA_PORT, if (type == CameraType.ONVIF) item.onvifPort else type.defaultPort)
            if (item.model.isNotEmpty()) intent.putExtra(CameraEditActivity.EXTRA_NAME, item.model)
        }
        startActivity(intent)
    }

    private fun scan() {
        if (scanning) return
        val own = localAddress()
        if (own == null) {
            status.text = "No se encontró la conexión de red. ¿El equipo está conectado al Wi-Fi o por cable?"
            return
        }
        prefix = own.substringBeforeLast('.') + "."
        found.clear()
        showResults()
        scanning = true
        again.isEnabled = false
        progress.progress = 0
        status.text = "Buscando cámaras en ${prefix}1 – ${prefix}254…"

        val done = AtomicInteger()
        fun step(n: Int = 1) = runOnUiThread { progress.progress = done.addAndGet(n) }

        Thread {
            val discovery = Thread { discoverOnvif { step() } }.apply { start() }

            val pool = Executors.newFixedThreadPool(48)
            for (last in 1..254) {
                pool.execute {
                    val ip = prefix + last
                    if (scanning) {
                        if (isPortOpen(ip, CameraType.XMEYE.defaultPort)) add(ip) { it.types += CameraType.XMEYE }
                        if (isPortOpen(ip, CameraType.RTSP.defaultPort)) add(ip) { it.types += CameraType.RTSP }
                    }
                    step()
                }
            }
            pool.shutdown()
            pool.awaitTermination(3, TimeUnit.MINUTES)
            discovery.join()
            runOnUiThread {
                scanning = false
                again.isEnabled = true
                progress.progress = progress.max
                status.text = when (found.size) {
                    0 -> "No se encontraron cámaras. Verifica que estén encendidas y en la misma red."
                    1 -> "Se encontró 1 cámara. Selecciónala para agregarla."
                    else -> "Se encontraron ${found.size} cámaras. Selecciona una para agregarla."
                }
            }
        }.start()
    }

    private fun add(ip: String, update: (Found) -> Unit) = runOnUiThread {
        if (!ip.startsWith(prefix)) return@runOnUiThread
        val last = ip.removePrefix(prefix).toIntOrNull() ?: return@runOnUiThread
        update(found.getOrPut(last) { Found(ip) })
        showResults()
    }

    /** ONVIF WS-Discovery: multicast Probe for video devices, collect who answers for ~3 s. */
    private fun discoverOnvif(onStep: () -> Unit) {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("camaratv-discovery")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val probe = PROBE.replace("{uuid}", UUID.randomUUID().toString()).toByteArray()
                val target = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, target, 3702)) }

                val buf = ByteArray(16 * 1024)
                repeat(DISCOVERY_STEPS) {
                    try {
                        while (true) {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            parseProbeMatch(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: "")
                        }
                    } catch (_: SocketTimeoutException) {
                        onStep()
                    }
                }
            }
        } catch (_: Exception) {
            // No multicast on this network: the port scan still runs
        } finally {
            lock?.release()
        }
    }

    private fun parseProbeMatch(xml: String, sender: String) {
        val xaddrs = Regex("<[^>]*XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
        val xaddr = xaddrs.mapNotNull { runCatching { URI(it) }.getOrNull() }.firstOrNull { it.host == sender }
            ?: xaddrs.mapNotNull { runCatching { URI(it) }.getOrNull() }.firstOrNull()
        val ip = xaddr?.host ?: sender
        val scopes = Regex("<[^>]*Scopes>([^<]+)<").find(xml)?.groupValues?.get(1).orEmpty()
        fun scope(key: String) = Regex("onvif://www\\.onvif\\.org/$key/(\\S+)").find(scopes)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val model = listOfNotNull(scope("name"), scope("hardware")).distinct().joinToString(" ")
        add(ip) {
            it.types += CameraType.ONVIF
            if (xaddr != null && xaddr.port > 0) it.onvifPort = xaddr.port
            if (model.isNotEmpty()) it.model = model
        }
    }

    private fun showResults() {
        val known = CameraStore.all(this)
        val rows = found.values.map { item ->
            val cam = known.firstOrNull { it.host == item.ip }
            val kinds = item.types.joinToString(" · ") {
                when (it) {
                    CameraType.XMEYE -> "XMEye/iCSee"
                    CameraType.ONVIF -> "ONVIF"
                    CameraType.RTSP -> "RTSP"
                }
            }
            mapOf(
                "title" to item.ip + if (item.model.isNotEmpty()) " — ${item.model}" else "",
                "detail" to "$kinds · " + if (cam != null) "ya agregada como \"${cam.name}\"" else "seleccionar para agregar",
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

    /** IPv4 address of this device on the local network (Wi-Fi or Ethernet). */
    private fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    companion object {
        private const val DISCOVERY_STEPS = 10 // × 300 ms of listening

        private const val PROBE = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" """ +
            """xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">""" +
            """<e:Header><w:MessageID>uuid:{uuid}</w:MessageID>""" +
            """<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>""" +
            """<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>""" +
            """<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
    }
}
