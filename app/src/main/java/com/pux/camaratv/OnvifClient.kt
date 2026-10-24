package com.pux.camaratv

import android.util.Base64
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class OnvifException(message: String) : IOException(message)

/**
 * Minimal ONVIF client (SOAP over HTTP with WS-Security digest): device info, media profiles,
 * RTSP stream URIs and PTZ continuous move. Works with most IP cameras regardless of brand.
 */
class OnvifClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
) {
    constructor(camera: Camera) : this(camera.host, camera.port, camera.user, camera.password)

    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private var mediaUrl: String? = null
    private var ptzUrl: String? = null
    private var eventsUrl: String? = null
    private var clockOffsetMs = 0L
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    val hasPtz get() = ptzUrl != null
    val hasEvents get() = eventsUrl != null

    /** Syncs the clock with the camera (needed for the auth digest) and finds its services. */
    fun connect() {
        try {
            val time = call(deviceUrl, """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>""", auth = false)
            cameraUtcTime(time)?.let { clockOffsetMs = it - System.currentTimeMillis() }
        } catch (_: IOException) {
            // Some cameras require auth even for this; use our own clock then
        }
        val caps = call(deviceUrl, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>""")
        mediaUrl = xaddr(caps, "Media") ?: throw OnvifException("La cámara no ofrece video por ONVIF")
        ptzUrl = xaddr(caps, "PTZ")
        eventsUrl = xaddr(caps, "Events")
    }

    /** "Marca Modelo" as reported by the camera. */
    fun deviceName(): String {
        val doc = call(deviceUrl, """<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>""")
        return listOf(text(doc, "Manufacturer"), text(doc, "Model")).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Media profile tokens, usually [main stream, sub stream, …]. */
    fun profiles(): List<String> {
        val doc = call(mediaUrl!!, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
        return elements(doc, "Profiles").map { it.getAttribute("token") }.filter { it.isNotEmpty() }
    }

    fun streamUri(profile: String): String {
        val doc = call(
            mediaUrl!!,
            """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup>""" +
                """<Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>""" +
                """<Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport>""" +
                """</StreamSetup><ProfileToken>${esc(profile)}</ProfileToken></GetStreamUri>""",
        )
        val uri = text(doc, "Uri").ifEmpty { throw OnvifException("La cámara no devolvió la dirección del video") }
        return fixHost(uri)
    }

    /** Pan/tilt at the given speed (-1..1); call [stop] to end the move. */
    fun move(profile: String, x: Float, y: Float) {
        val url = ptzUrl ?: return
        call(
            url,
            """<ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken>""" +
                """<Velocity><PanTilt xmlns="http://www.onvif.org/ver10/schema" x="$x" y="$y"/></Velocity></ContinuousMove>""",
        )
    }

    fun stop(profile: String) {
        val url = ptzUrl ?: return
        call(
            url,
            """<Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken>""" +
                """<PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>""",
        )
    }

    // --- Motion events (PullPoint subscription) ---

    /**
     * Subscribes to the camera's events and calls [onAlarm] with {"Event": "VideoMotion"|"HumanDetect",
     * "Status": "Start"|"Stop"} — the same shape as XMEye alarms — until [cancel] is called or the
     * connection fails. Call [connect] first.
     */
    fun watchMotion(onAlarm: (JSONObject) -> Unit) {
        val events = eventsUrl ?: throw OnvifException("La cámara no ofrece eventos ONVIF")
        val sub = call(
            events,
            """<CreatePullPointSubscription xmlns="http://www.onvif.org/ver10/events/wsdl">""" +
                "<InitialTerminationTime>PT${SUBSCRIPTION_SECONDS}S</InitialTerminationTime></CreatePullPointSubscription>",
            action = "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest",
        )
        val reference = elements(sub, "SubscriptionReference").firstOrNull()
            ?: throw OnvifException("La cámara no aceptó la suscripción a eventos")
        val address = fixHost(text(reference, "Address").ifEmpty { events })
        // Some brands (e.g. Dahua) identify the subscription by headers they ask us to echo back
        val referenceParams = elements(reference, "ReferenceParameters").firstOrNull()
            ?.let { (0 until it.childNodes.length).joinToString("") { i -> serialize(it.childNodes.item(i)) } }
            .orEmpty()

        fun subscriptionCall(body: String, action: String, readTimeout: Int = 8000) =
            call(address, body, action = action, extraHeaders = addressing(action, address) + referenceParams, readTimeout = readTimeout)

        try {
            var nextRenew = System.currentTimeMillis() + SUBSCRIPTION_SECONDS * 1000L / 2
            var failures = 0
            while (!cancelled) {
                val doc = try {
                    subscriptionCall(
                        """<PullMessages xmlns="http://www.onvif.org/ver10/events/wsdl"><Timeout>PT${PULL_SECONDS}S</Timeout>""" +
                            "<MessageLimit>32</MessageLimit></PullMessages>",
                        "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest",
                        readTimeout = (PULL_SECONDS + 10) * 1000,
                    ).also { failures = 0 }
                } catch (e: IOException) {
                    // A dropped reply (flaky Wi-Fi) shouldn't cost the subscription; the camera keeps queuing events
                    if (cancelled || e is OnvifException || ++failures > MAX_PULL_FAILURES) throw e
                    Thread.sleep(1000)
                    continue
                }
                elements(doc, "NotificationMessage").forEach { msg -> motionAlarm(msg)?.let(onAlarm) }

                if (System.currentTimeMillis() >= nextRenew) {
                    try {
                        subscriptionCall(
                            """<Renew xmlns="http://docs.oasis-open.org/wsn/b-2"><TerminationTime>PT${SUBSCRIPTION_SECONDS}S</TerminationTime></Renew>""",
                            "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest",
                        )
                    } catch (_: OnvifException) {
                        // Many cameras extend the subscription on every pull instead
                    }
                    nextRenew = System.currentTimeMillis() + SUBSCRIPTION_SECONDS * 1000L / 2
                }
            }
        } finally {
            if (!cancelled) {
                try {
                    subscriptionCall(
                        """<Unsubscribe xmlns="http://docs.oasis-open.org/wsn/b-2"/>""",
                        "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest",
                    )
                } catch (_: IOException) {}
            }
        }
    }

    /** Stops [watchMotion] from another thread, aborting the pending long-poll. */
    fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
    }

    /**
     * Turns an ONVIF notification into an alarm if it is a motion / person event.
     * Topics vary by brand: RuleEngine/CellMotionDetector/Motion (IsMotion), VideoSource/MotionAlarm (State),
     * RuleEngine/MyRuleDetector/PeopleDetect (State)…
     */
    private fun motionAlarm(msg: Element): JSONObject? {
        val topic = text(msg, "Topic")
        val human = HUMAN_TOPIC.containsMatchIn(topic)
        if (!human && !topic.contains("Motion", ignoreCase = true)) return null

        val message = elements(msg, "Message").lastOrNull() ?: return null
        // The initial "Initialized" message only reports the current state when we subscribe
        if (message.getAttribute("PropertyOperation").equals("Initialized", ignoreCase = true)) return null

        val data = elements(message, "Data").firstOrNull() ?: return null
        val value = elements(data, "SimpleItem").firstOrNull { it.getAttribute("Value").lowercase() in setOf("true", "false") }
            ?.getAttribute("Value") ?: return null
        return JSONObject()
            .put("Event", if (human) "HumanDetect" else "VideoMotion")
            .put("Status", if (value.equals("true", ignoreCase = true)) "Start" else "Stop")
            .put("Topic", topic)
    }

    private fun addressing(action: String, to: String) =
        """<a:Action xmlns:a="http://www.w3.org/2005/08/addressing">$action</a:Action>""" +
            """<a:To xmlns:a="http://www.w3.org/2005/08/addressing">${esc(to)}</a:To>"""

    // --- SOAP plumbing ---

    private fun call(
        url: String,
        body: String,
        auth: Boolean = true,
        action: String? = null,
        extraHeaders: String = "",
        readTimeout: Int = 8000,
    ): Document {
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">""" +
            "<s:Header>${if (auth && user.isNotEmpty()) security() else ""}$extraHeaders</s:Header>" +
            "<s:Body>$body</s:Body></s:Envelope>"

        val conn = URL(url).openConnection() as HttpURLConnection
        activeConnection = conn
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = readTimeout
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type",
                "application/soap+xml; charset=utf-8" + if (action != null) "; action=\"$action\"" else "",
            )
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val doc = parse(bytes)
            if (code !in 200..299 || (doc != null && elements(doc, "Fault").isNotEmpty())) {
                val reason = doc?.let { text(it, "Text") }.orEmpty()
                if (code == 401 || reason.contains("auth", ignoreCase = true) || reason.contains("Sender not Authorized", ignoreCase = true)) {
                    throw OnvifException("Usuario o contraseña incorrectos")
                }
                throw OnvifException("La cámara respondió con error ($code${if (reason.isNotEmpty()) ": $reason" else ""})")
            }
            return doc ?: throw OnvifException("Respuesta ONVIF inválida")
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }

    /** WS-Security UsernameToken with PasswordDigest = Base64(SHA1(nonce + created + password)). */
    private fun security(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + clockOffsetMs))
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">""" +
            "<UsernameToken><Username>${esc(user)}</Username>" +
            """<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>""" +
            """<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">""" +
            Base64.encodeToString(nonce, Base64.NO_WRAP) + "</Nonce>" +
            """<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>""" +
            "</UsernameToken></Security>"
    }

    private fun cameraUtcTime(doc: Document): Long? {
        val utc = elements(doc, "UTCDateTime").firstOrNull() ?: return null
        fun n(name: String) = text(utc, name).toIntOrNull()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(n("Year") ?: return null, (n("Month") ?: return null) - 1, n("Day") ?: return null, n("Hour") ?: 0, n("Minute") ?: 0, n("Second") ?: 0)
        return cal.timeInMillis
    }

    /** XAddr of a service in GetCapabilities, e.g. Capabilities/Media/XAddr. */
    private fun xaddr(doc: Document, service: String): String? =
        elements(doc, service).firstNotNullOfOrNull { el ->
            elements(el, "XAddr").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }?.let { fixHost(it) }

    /** Cameras sometimes report another IP (e.g. a second interface); use the one we reached them on. */
    private fun fixHost(url: String): String = try {
        val u = URI(url)
        if (u.host == null || u.host == host) url
        else URI(u.scheme, u.userInfo, host, u.port, u.path, u.query, u.fragment).toString()
    } catch (_: Exception) {
        url
    }

    companion object {
        private const val SUBSCRIPTION_SECONDS = 60
        private const val PULL_SECONDS = 10
        private const val MAX_PULL_FAILURES = 3
        private val HUMAN_TOPIC = Regex("People|Human|Person", RegexOption.IGNORE_CASE)

        private fun serialize(node: Node): String {
            val writer = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }.transform(DOMSource(node), StreamResult(writer))
            return writer.toString()
        }

        private fun parse(bytes: ByteArray): Document? = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(bytes.inputStream())
        } catch (_: Exception) {
            null
        }

        fun elements(doc: Document, localName: String): List<Element> = doc.getElementsByTagNameNS("*", localName).toElements()
        fun elements(el: Element, localName: String): List<Element> = el.getElementsByTagNameNS("*", localName).toElements()

        private fun org.w3c.dom.NodeList.toElements() = (0 until length).map { item(it) as Element }

        fun text(doc: Document, localName: String) = elements(doc, localName).firstOrNull()?.textContent?.trim().orEmpty()
        fun text(el: Element, localName: String) = elements(el, localName).firstOrNull()?.textContent?.trim().orEmpty()

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
