package ar.mateo.controlflow

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/** Client certificate: created once and kept in the app's private storage. The box remembers it after pairing. */
object CertStore {
    private const val ALIAS = "client"
    val PASS = "controlflow".toCharArray()

    @Synchronized
    fun load(ctx: Context): KeyStore {
        val f = File(ctx.filesDir, "client.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            try {
                f.inputStream().use { ks.load(it, PASS) }
                return ks
            } catch (_: Exception) {
                f.delete()
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=atvremote/ControlFlow")
        val now = Date(System.currentTimeMillis() - 86_400_000L)
        val until = Date(now.time + 20L * 365 * 86_400_000L)
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.currentTimeMillis()), now, until, name, kp.public)
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, PASS, arrayOf(cert))
        f.outputStream().use { ks.store(it, PASS) }
        return ks
    }

    fun cert(ks: KeyStore) = ks.getCertificate(ALIAS) as X509Certificate
}

/** App-wide controller: remembers the box, keeps the session and exposes the connection state. */
object Ctl {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    val conn = MutableStateFlow<Conn>(Conn.Idle)
    private var session: RemoteSession? = null
    private var foreground = false
    private var pairing: Pairing? = null

    @Volatile private var ssl: SSLContext? = null
    @Volatile private var keyStore: KeyStore? = null

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    private val prefs get() = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var host: String?
        get() = prefs.getString("host", null)
        set(v) { prefs.edit().putString("host", v).apply() }
    var boxName: String?
        get() = prefs.getString("name", null)
        set(v) { prefs.edit().putString("name", v).apply() }

    @Synchronized
    private fun sslContext(): SSLContext {
        ssl?.let { return it }
        val ks = CertStore.load(app)
        keyStore = ks
        return Tls.context(ks, CertStore.PASS).also { ssl = it }
    }

    fun onStart() { foreground = true; connect() }
    fun onStop() { foreground = false; disconnect() }

    fun connect() {
        val h = host ?: return
        session?.close()
        val s = RemoteSession(h, { sslContext() }) { st ->
            conn.value = st
            if (st is Conn.Error && foreground) main.postDelayed({ if (foreground && conn.value is Conn.Error) connect() }, 2500)
        }
        session = s
        s.start()
    }

    fun disconnect() {
        session?.close(); session = null
        conn.value = Conn.Idle
    }

    fun key(code: Int, direction: Int = 3) {
        val s = session
        if (s == null || conn.value != Conn.Connected) { if (conn.value != Conn.Connecting) connect(); return }
        s.key(code, direction)
    }

    fun openApp(link: String) {
        val s = session
        if (s == null || conn.value != Conn.Connected) { if (conn.value != Conn.Connecting) connect(); return }
        s.openApp(link)
    }

    fun sendText(text: String, letterByLetter: Boolean = false) {
        val s = session
        if (s == null || conn.value != Conn.Connected) { if (conn.value != Conn.Connecting) connect(); return }
        s.sendText(text, letterByLetter)
    }

    // ---- Pairing ----
    fun startPairing(h: String, cb: (String?) -> Unit) {
        disconnect()
        thread {
            val err = try {
                pairing?.close()
                val ctx = sslContext()
                val p = Pairing(h, ctx, CertStore.cert(keyStore!!))
                p.start()
                pairing = p
                null
            } catch (e: Exception) {
                "No pude conectarme al deco ($h): ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { cb(err) }
        }
    }

    /** cb(null) = OK; otherwise error message. */
    fun finishPairing(h: String, name: String?, code: String, cb: (String?) -> Unit) {
        thread {
            val err = try {
                val p = pairing ?: throw IllegalStateException("Se perdió la vinculación, empezá de nuevo")
                if (p.finish(code)) {
                    pairing = null
                    host = h; boxName = name
                    null
                } else "Ese código no coincide. Revisalo en la tele."
            } catch (e: Exception) {
                pairing?.close(); pairing = null
                e.message ?: "Error al vincular"
            }
            main.post { cb(err); if (err == null && foreground) connect() }
        }
    }

    fun cancelPairing() { pairing?.close(); pairing = null }
}

/** Finds Android TV boxes in the Wi-Fi network (the same way the Google TV app does). */
class Discovery(ctx: Context, private val type: String = "_androidtvremote2._tcp") {
    data class Dev(val name: String, val host: String)

    val devices = MutableStateFlow<List<Dev>>(emptyList())
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { running = false }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String?) {}
        override fun onDiscoveryStopped(serviceType: String?) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(this@Discovery) { queue.addLast(info) }
            next()
        }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val info = synchronized(this) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            queue.removeFirst()
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(this@Discovery) { resolving = false }
                    next()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val addr = si.host?.hostAddress
                    if (addr != null && !addr.contains(":")) {
                        // Chromecasts publish their friendly name in the "fn" TXT record
                        val fn = try { si.attributes["fn"]?.let { String(it, Charsets.UTF_8) } } catch (_: Exception) { null }
                        val d = Dev(fn?.takeIf { it.isNotBlank() } ?: si.serviceName ?: addr, addr)
                        devices.value = (devices.value.filter { it.host != addr } + d)
                    }
                    synchronized(this@Discovery) { resolving = false }
                    next()
                }
            })
        } catch (_: Exception) {
            synchronized(this) { resolving = false }
        }
    }

    fun start() {
        if (running) return
        running = true
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }
}
