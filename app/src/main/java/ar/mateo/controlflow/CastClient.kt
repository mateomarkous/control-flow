package ar.mateo.controlflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** What is playing on the Chromecast right now. */
data class CastMedia(
    val title: String?,
    val subtitle: String?,
    val image: String?,
    val state: String,          // PLAYING, PAUSED, BUFFERING, IDLE
    val position: Double,       // seconds, at the moment [at]
    val duration: Double,       // seconds (0 = unknown / live)
    val at: Long,               // System.currentTimeMillis() when [position] was reported
    val commands: Long = 0,     // supportedMediaCommands bitmask (0 = unknown)
    val rate: Double = 1.0,     // playback speed
    val textTracks: List<Pair<Int, String>> = emptyList(), // subtitles available (id, name)
    val activeTracks: List<Int> = emptyList(),
) {
    fun supports(bit: Long) = commands == 0L || (commands and bit) != 0L
    fun positionNow(): Double {
        val p = if (state == "PLAYING") position + (System.currentTimeMillis() - at) / 1000.0 else position
        return if (duration > 0) p.coerceIn(0.0, duration) else p.coerceAtLeast(0.0)
    }
}

data class CastState(
    val conn: Conn = Conn.Idle,
    val volume: Double = 0.0,
    val muted: Boolean = false,
    val fixedVolume: Boolean = false,
    val appName: String? = null,      // e.g. "YouTube"; null = nothing open
    val controllable: Boolean = false, // the open app accepts play/pause
    val media: CastMedia? = null,
)

/**
 * Google Cast (CASTV2) client: TLS on port 8009, messages are a 4-byte length + protobuf CastMessage
 * carrying JSON. Controls whatever media session is running (play/pause/seek/volume/stop).
 */
class CastClient(private val host: String, private val onUpdate: (CastState) -> Unit) {
    companion object {
        const val PORT = 8009
        const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val SENDER = "sender-cf"
        const val RECEIVER = "receiver-0"
        private const val BACKDROP = "E8C28D3C"
        private val json = Json { ignoreUnknownKeys = true }

        fun sslContext(): SSLContext {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        }

        fun encode(src: String, dst: String, ns: String, payload: String): ByteArray =
            PB().int(1, 0).str(2, src).str(3, dst).str(4, ns).int(5, 0).str(6, payload).toBytes()
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private val writer = Executors.newSingleThreadExecutor()
    private var timer: ScheduledExecutorService? = null
    private val reqId = AtomicInteger(1)

    @Volatile private var state = CastState()
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Int? = null

    private fun publish(s: CastState) { state = s; if (!closed) onUpdate(s) }

    fun start() { thread(name = "cast", isDaemon = true) { run() } }

    private fun run() {
        try {
            publish(state.copy(conn = Conn.Connecting))
            val raw = Socket()
            raw.connect(InetSocketAddress(host, PORT), 5000)
            raw.tcpNoDelay = true
            val s = sslContext().socketFactory.createSocket(raw, host, PORT, true) as SSLSocket
            s.soTimeout = 20000
            s.startHandshake()
            socket = s
            if (closed) { s.close(); return }
            send(RECEIVER, NS_CONN, """{"type":"CONNECT","origin":{},"userAgent":"ControlFlow"}""")
            sendJson(RECEIVER, NS_RECV, buildJsonObject { put("type", "GET_STATUS") })
            publish(state.copy(conn = Conn.Connected))
            timer = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ tick() }, 5, 5, TimeUnit.SECONDS)
            }
            val ins = DataInputStream(BufferedInputStream(s.inputStream))
            while (!closed) {
                val len = ins.readInt()
                if (len < 0 || len > 1_000_000) throw EOFException("Mensaje inválido")
                val buf = ByteArray(len)
                ins.readFully(buf)
                handle(PMsg.parse(buf))
            }
        } catch (e: Exception) {
            if (!closed) publish(state.copy(conn = Conn.Error(friendly(e))))
        } finally {
            shutdown()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "El Chromecast no responde"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "No encuentro el Chromecast en la red"
        is EOFException -> "Se cortó la conexión"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun tick() {
        send(RECEIVER, NS_HEART, """{"type":"PING"}""")
        sendJson(RECEIVER, NS_RECV, buildJsonObject { put("type", "GET_STATUS") })
        transportId?.let { sendJson(it, NS_MEDIA, buildJsonObject { put("type", "GET_STATUS") }) }
    }

    private fun handle(m: PMsg) {
        val src = m.str(2) ?: return
        val ns = m.str(4) ?: return
        val payload = m.str(6) ?: return
        val obj = try { json.parseToJsonElement(payload) as? JsonObject } catch (_: Exception) { null } ?: return
        val type = obj.str("type")
        when (ns) {
            NS_HEART -> if (type == "PING") send(src, NS_HEART, """{"type":"PONG"}""")
            NS_CONN -> if (type == "CLOSE" && src == transportId) {
                transportId = null; mediaSessionId = null
                publish(state.copy(media = null, controllable = false))
            }
            NS_RECV -> if (type == "RECEIVER_STATUS") onReceiverStatus(obj.obj("status"))
            NS_MEDIA -> if (type == "MEDIA_STATUS") onMediaStatus(obj["status"] as? JsonArray)
        }
    }

    private fun onReceiverStatus(status: JsonObject?) {
        status ?: return
        val vol = status.obj("volume")
        var s = state.copy(
            volume = vol?.num("level") ?: state.volume,
            muted = vol?.bool("muted") ?: state.muted,
            fixedVolume = vol?.str("controlType") == "fixed",
        )
        val apps = (status["applications"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val app = apps.firstOrNull { it.str("appId") != BACKDROP && it.bool("isIdleScreen") != true }
        if (app == null) {
            transportId = null; sessionId = null; mediaSessionId = null
            s = s.copy(appName = null, controllable = false, media = null)
        } else {
            val hasMedia = (app["namespaces"] as? JsonArray)?.any { (it as? JsonObject)?.str("name") == NS_MEDIA } ?: false
            sessionId = app.str("sessionId")
            s = s.copy(appName = app.str("displayName") ?: "App", controllable = hasMedia || s.controllable)
            val tid = app.str("transportId")
            if (tid != null && tid != transportId) {
                transportId = tid
                mediaSessionId = null
                s = s.copy(media = null)
                send(tid, NS_CONN, """{"type":"CONNECT","origin":{},"userAgent":"ControlFlow"}""")
                sendJson(tid, NS_MEDIA, buildJsonObject { put("type", "GET_STATUS") })
            }
        }
        publish(s)
    }

    private fun onMediaStatus(arr: JsonArray?) {
        val st = arr?.firstOrNull() as? JsonObject
        if (st == null) {
            mediaSessionId = null
            publish(state.copy(media = null))
            return
        }
        mediaSessionId = st.int("mediaSessionId") ?: mediaSessionId
        val prev = state.media
        val media = st.obj("media")
        val meta = media?.obj("metadata")
        val image = (meta?.get("images") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("url") }
        val playerState = st.str("playerState") ?: prev?.state ?: "IDLE"
        if (playerState == "IDLE" && st.str("idleReason") != null) {
            mediaSessionId = null
            publish(state.copy(media = null, controllable = true))
            return
        }
        val tracks = (media?.get("tracks") as? JsonArray)?.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "TEXT") return@mapNotNull null
            val id = o.int("trackId") ?: return@mapNotNull null
            id to (o.str("name") ?: o.str("language")?.uppercase() ?: "Pista $id")
        }
        val active = (st["activeTrackIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val m = CastMedia(
            commands = (st["supportedMediaCommands"] as? JsonPrimitive)?.longOrNull ?: prev?.commands ?: 0L,
            rate = st.num("playbackRate") ?: prev?.rate ?: 1.0,
            textTracks = tracks ?: prev?.textTracks ?: emptyList(),
            activeTracks = active ?: prev?.activeTracks ?: emptyList(),
            title = meta?.str("title") ?: prev?.title,
            subtitle = meta?.str("subtitle") ?: meta?.str("artist") ?: meta?.str("seriesTitle") ?: meta?.str("albumName") ?: prev?.subtitle,
            image = image ?: prev?.image,
            state = playerState,
            position = st.num("currentTime") ?: prev?.positionNow() ?: 0.0,
            duration = media?.num("duration") ?: prev?.duration ?: 0.0,
            at = System.currentTimeMillis(),
        )
        publish(state.copy(media = m, controllable = true))
    }

    // ---------------------------------------------------------------- commands

    fun playPause() {
        val m = state.media ?: return
        val playing = m.state == "PLAYING" || m.state == "BUFFERING"
        mediaCmd(if (playing) "PAUSE" else "PLAY")
        publish(state.copy(media = m.copy(state = if (playing) "PAUSED" else "PLAYING", position = m.positionNow(), at = System.currentTimeMillis())))
    }

    fun play() { val m = state.media ?: return; mediaCmd("PLAY"); publish(state.copy(media = m.copy(state = "PLAYING", position = m.positionNow(), at = System.currentTimeMillis()))) }
    fun pause() { val m = state.media ?: return; mediaCmd("PAUSE"); publish(state.copy(media = m.copy(state = "PAUSED", position = m.positionNow(), at = System.currentTimeMillis()))) }
    fun next() = mediaCmd("QUEUE_UPDATE") { put("jump", 1) }
    fun previous() = mediaCmd("QUEUE_UPDATE") { put("jump", -1) }

    fun setRate(rate: Double) {
        val m = state.media ?: return
        mediaCmd("SET_PLAYBACK_RATE") { put("playbackRate", rate) }
        publish(state.copy(media = m.copy(rate = rate, position = m.positionNow(), at = System.currentTimeMillis())))
    }

    /** trackId = null turns subtitles off. */
    fun setSubtitle(trackId: Int?) {
        val m = state.media ?: return
        mediaCmd("EDIT_TRACKS_INFO") { put("activeTrackIds", JsonArray(listOfNotNull(trackId?.let { JsonPrimitive(it) }))) }
        publish(state.copy(media = m.copy(activeTracks = listOfNotNull(trackId))))
    }

    fun seekBy(seconds: Double) {
        val m = state.media ?: return
        seekTo(m.positionNow() + seconds)
    }

    fun seekTo(seconds: Double) {
        val m = state.media ?: return
        val t = if (m.duration > 0) seconds.coerceIn(0.0, m.duration) else seconds.coerceAtLeast(0.0)
        mediaCmd("SEEK") { put("currentTime", t); put("resumeState", if (m.state == "PAUSED") "PLAYBACK_PAUSE" else "PLAYBACK_START") }
        publish(state.copy(media = m.copy(position = t, at = System.currentTimeMillis())))
    }

    /** Closes the app that is casting (like "stop casting"). */
    fun stopApp() {
        val sid = sessionId ?: return
        sendJson(RECEIVER, NS_RECV, buildJsonObject { put("type", "STOP"); put("sessionId", sid) })
    }

    fun setVolume(level: Double) {
        val l = level.coerceIn(0.0, 1.0)
        sendJson(RECEIVER, NS_RECV, buildJsonObject { put("type", "SET_VOLUME"); putJsonObject("volume") { put("level", l) } })
        publish(state.copy(volume = l))
    }

    fun toggleMute() {
        val mute = !state.muted
        sendJson(RECEIVER, NS_RECV, buildJsonObject { put("type", "SET_VOLUME"); putJsonObject("volume") { put("muted", mute) } })
        publish(state.copy(muted = mute))
    }

    private fun mediaCmd(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val tid = transportId ?: return
        val msid = mediaSessionId ?: return
        sendJson(tid, NS_MEDIA, buildJsonObject { put("type", type); put("mediaSessionId", msid); extra() })
    }

    // ---------------------------------------------------------------- plumbing

    private fun sendJson(dst: String, ns: String, o: JsonObject) {
        val withId = JsonObject(o + ("requestId" to JsonPrimitive(reqId.getAndIncrement())))
        send(dst, ns, withId.toString())
    }

    private fun send(dst: String, ns: String, payload: String) {
        val bytes = encode(SENDER, dst, ns, payload)
        try {
            writer.execute {
                try {
                    val s = socket ?: return@execute
                    val out = s.outputStream
                    val len = bytes.size
                    out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {
                    if (!closed) { publish(state.copy(conn = Conn.Error("Se cortó la conexión"))); close() }
                }
            }
        } catch (_: Exception) { /* writer already shut down */ }
    }

    private fun shutdown() {
        timer?.shutdownNow(); timer = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun close() {
        if (closed) return
        closed = true
        try { writer.execute { shutdown() }; writer.shutdown() } catch (_: Exception) { shutdown() }
    }
}

// small JSON helpers
private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
@Suppress("unused") private fun JsonElement?.isNull() = this == null || this is JsonNull
