package ar.mateo.controlflow

import java.io.BufferedInputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Connection state shown in the UI. */
sealed class Conn {
    data object Idle : Conn()
    data object Connecting : Conn()
    data object Connected : Conn()
    data object NeedPairing : Conn()
    data class Error(val msg: String) : Conn()
}

object Tls {
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466

    fun context(ks: KeyStore, pass: CharArray): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        // The box uses a self-signed certificate: accept it (we only talk to the box in our own Wi-Fi).
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
    }

    fun connect(ctx: SSLContext, host: String, port: Int, readTimeoutMs: Int, onTcp: () -> Unit = {}): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5000)
        raw.tcpNoDelay = true
        onTcp()
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = readTimeoutMs
        s.startHandshake()
        return s
    }
}

/** Pairing (port 6467). The TV shows a 6-character hex code that the user types in the app. */
class Pairing(
    private val host: String,
    private val ctx: SSLContext,
    private val clientCert: X509Certificate,
    private val clientName: String = "Control Flow",
) {
    private var socket: SSLSocket? = null
    private lateinit var ins: InputStream

    private fun outer(): PB = PB().int(1, 2).int(2, 200) // protocol_version=2, status=OK

    private fun send(m: PB) = Framing.write(socket!!.outputStream, m.toBytes())

    private fun receive(expectField: Int): PMsg {
        val m = PMsg.parse(Framing.read(ins))
        val status = m.int(2)
        if (status != 200L) throw IllegalStateException("El deco rechazó la vinculación (código $status)")
        if (!m.has(expectField)) throw IllegalStateException("Respuesta inesperada del deco: ${m.keys()}")
        return m
    }

    /** Opens the connection and asks the TV to show the code. Blocking: call from a background thread. */
    fun start() {
        val s = Tls.connect(ctx, host, Tls.PAIRING_PORT, 15000)
        socket = s
        ins = BufferedInputStream(s.inputStream)
        val hexEncoding = PB().int(1, 3).int(2, 6) // HEXADECIMAL, 6 symbols
        send(outer().msg(10, PB().str(1, "atvremote").str(2, clientName)))
        receive(11) // pairing_request_ack
        send(outer().msg(20, PB().msg(1, hexEncoding).int(3, 1))) // options: input encoding, role INPUT
        receive(20) // options
        send(outer().msg(30, PB().msg(1, hexEncoding).int(2, 1))) // configuration
        receive(31) // configuration_ack -> the TV now shows the code
    }

    /** Returns false if the code doesn't match (typo). Throws if the box rejects it. */
    fun finish(codeRaw: String): Boolean {
        val code = codeRaw.trim().uppercase()
        require(code.length == 6 && code.all { it in "0123456789ABCDEF" }) { "El código tiene que tener 6 caracteres (0-9, A-F)" }
        val s = socket ?: throw IllegalStateException("No hay vinculación en curso")
        val server = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val client = clientCert.publicKey as RSAPublicKey
        val hash = secretHash(client, server, code)
        if ((hash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) return false
        send(outer().msg(40, PB().bytes(1, hash)))
        receive(41) // secret_ack
        close()
        return true
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        fun unsigned(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        fun secretHash(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(unsigned(client.modulus))
            md.update(unsigned(client.publicExponent))
            md.update(unsigned(server.modulus))
            md.update(unsigned(server.publicExponent))
            md.update(hexToBytes(code.substring(2)))
            return md.digest()
        }

        fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Remote control session (port 6466). Keeps the connection alive answering the box's pings. */
class RemoteSession(
    private val host: String,
    private val ctxProvider: () -> SSLContext,
    private val onState: (Conn) -> Unit,
    private val onApp: (String) -> Unit = {},
) {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private val writer = Executors.newSingleThreadExecutor()

    // PING | KEY | IME | POWER | VOLUME | APP_LINK
    private val features = 1 or 2 or 4 or 32 or 64 or 512

    // Text-input state reported by the box when a text field is focused
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile var imeSeen = false
        private set

    fun start() {
        thread(name = "atv-remote", isDaemon = true) { run() }
    }

    private fun run() {
        var gotMessage = false
        var tcpUp = false
        try {
            onState(Conn.Connecting)
            val s = Tls.connect(ctxProvider(), host, Tls.REMOTE_PORT, 25000) { tcpUp = true }
            socket = s
            if (closed) { s.close(); return }
            val ins = BufferedInputStream(s.inputStream)
            while (!closed) {
                val m = PMsg.parse(Framing.read(ins))
                gotMessage = true
                handle(m)
            }
        } catch (e: Exception) {
            if (!closed) {
                // The box drops the TLS connection of a phone it hasn't paired with
                // (shows up as an SSL error or a reset/broken pipe before any message arrives).
                val rejected = tcpUp && !gotMessage && (e is SSLException || e is java.net.SocketException || e is java.io.EOFException)
                onState(if (rejected) Conn.NeedPairing else Conn.Error(friendly(e)))
            }
        } finally {
            shutdown()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "El deco no responde"
        is java.net.ConnectException -> "No encuentro el deco en la red"
        is java.net.NoRouteToHostException -> "No encuentro el deco en la red"
        is java.io.EOFException -> "Se cortó la conexión"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun handle(m: PMsg) {
        when {
            m.has(1) -> { // remote_configure -> answer with ours
                val info = PB().str(1, "Control Flow").str(2, "Mateo").int(3, 1).str(4, "1")
                    .str(5, "atvremote").str(6, "1.0.0")
                write(PB().msg(1, PB().int(1, features).msg(2, info)))
                onState(Conn.Connected)
            }
            m.has(2) -> write(PB().msg(2, PB().int(1, features))) // remote_set_active
            m.has(8) -> write(PB().msg(9, PB().int(1, m.msg(8)?.int(1) ?: 0L))) // ping -> pong
            m.has(20) -> { // remote_ime_key_inject: a text field got focus
                val k = m.msg(20)
                k?.msg(1)?.let { info ->
                    imeCounter = info.int(1).toInt()
                    info.str(12)?.takeIf { it.isNotBlank() }?.let(onApp) // package of the app on screen
                }
                k?.msg(2)?.let { fieldCounter = it.int(1).toInt() }
                imeSeen = true
            }
            m.has(22) -> { // remote_ime_show_request
                m.msg(22)?.msg(2)?.let { fieldCounter = it.int(1).toInt(); imeSeen = true }
            }
            m.has(21) -> { // remote_ime_batch_edit (echo of the field state)
                m.msg(21)?.let {
                    if (imeCounter == 0) imeCounter = it.int(1).toInt()
                    if (fieldCounter == 0) fieldCounter = it.int(2).toInt()
                    imeSeen = true
                }
            }
            else -> {}
        }
    }

    private fun write(m: PB) {
        val bytes = m.toBytes()
        writer.execute {
            try {
                socket?.let { Framing.write(it.outputStream, bytes) }
            } catch (e: Exception) {
                if (!closed) { onState(Conn.Error("Se cortó la conexión")); close() }
            }
        }
    }

    /** direction: 1 = start long press, 2 = end long press, 3 = short press */
    fun key(code: Int, direction: Int = 3) = write(PB().msg(10, PB().int(1, code).int(2, direction)))

    fun openApp(link: String) = write(PB().msg(90, PB().str(1, link)))

    /** Types text into the focused field on the TV (search boxes). */
    fun sendText(text: String, letterByLetter: Boolean = false) {
        if (text.isEmpty()) return
        if (imeSeen && !letterByLetter) {
            val n = text.length
            val obj = PB().int(1, n).int(2, n).str(3, text)
            val edit = PB().int(1, 1).msg(2, obj)
            write(PB().msg(21, PB().int(1, imeCounter).int(2, fieldCounter).msg(3, edit)))
        } else {
            for (code in keyCodesFor(text)) key(code)
        }
    }

    companion object {
        /** Fallback: the text as Android key codes (letters, digits, space and a few symbols; accents removed). */
        fun keyCodesFor(text: String): List<Int> {
            val plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.lowercase().mapNotNull { c ->
                when (c) {
                    in 'a'..'z' -> 29 + (c - 'a')
                    in '0'..'9' -> 7 + (c - '0')
                    ' ' -> 62
                    '.' -> 56; ',' -> 55; '-' -> 69; '@' -> 77; '/' -> 76; '\'' -> 75
                    else -> null
                }
            }
        }
    }

    private fun shutdown() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun close() {
        closed = true
        writer.execute { shutdown() }
        writer.shutdown()
    }
}
