package ar.mateo.controlflow

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val CBg = Color(0xFF232427)
private val CBgDark = Color(0xFF1A1B1D)
private val CTurq = Color(0xFF6DD3CF)
private val CTurqPressed = Color(0xFF52B8B4)
private val CLabel = Color(0xFFEDEDED)
private val CGrey = Color(0xFF9A9A9A)

/** Controller for the Chromecast tab. Only connected while the tab is on screen. */
object CastCtl {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    val state = MutableStateFlow(CastState())
    private var client: CastClient? = null
    private var visible = false
    private var started = false

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }
    private val prefs get() = app.getSharedPreferences("cast", Context.MODE_PRIVATE)
    var host: String?
        get() = prefs.getString("host", null)
        private set(v) { prefs.edit().putString("host", v).apply() }
    var name: String?
        get() = prefs.getString("name", null)
        private set(v) { prefs.edit().putString("name", v).apply() }

    /** True while the notification / lock-screen controls service is running (keeps the connection). */
    @Volatile var serviceRunning = false

    fun onStart() { started = true; if (visible && client == null) connect() }
    fun onStop() { started = false; if (!serviceRunning) disconnect() }
    fun setVisible(v: Boolean) {
        visible = v
        if (v && started) { if (client == null) connect() } else if (!v && !serviceRunning) disconnect()
    }

    /** Called by the service when it finishes. */
    fun serviceStopped() {
        serviceRunning = false
        main.post { if (!(visible && started)) disconnect() }
    }

    /** Title the user dismissed from the notification: don't bring the notification back for it. */
    @Volatile var dismissedTitle: String? = null

    private fun maybeStartService(st: CastState) {
        if (st.media == null || serviceRunning || !started) return
        if (dismissedTitle != null && st.media.title == dismissedTitle) return
        serviceRunning = true
        main.post {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, CastService::class.java))
            } catch (_: Exception) { serviceRunning = false }
        }
    }

    fun choose(h: String, n: String) { host = h; name = n; connect() }

    fun connect() {
        val h = host ?: return
        client?.close()
        client = CastClient(h) { st ->
            state.value = st
            maybeStartService(st)
            if (st.conn is Conn.Error && visible && started) {
                main.postDelayed({ if (visible && started && state.value.conn is Conn.Error) connect() }, 3000)
            }
        }.also { it.start() }
    }

    fun disconnect() { client?.close(); client = null; state.value = CastState() }

    fun playPause() = client?.playPause()
    fun play() = client?.play()
    fun pause() = client?.pause()
    fun next() = client?.next()
    fun previous() = client?.previous()
    fun setRate(r: Double) = client?.setRate(r)
    fun setSubtitle(id: Int?) = client?.setSubtitle(id)
    fun volumeStep(d: Double) { if (state.value.conn == Conn.Connected && !state.value.fixedVolume) setVolume(state.value.volume + d) }
    fun seekBy(s: Double) = client?.seekBy(s)
    fun seekTo(s: Double) = client?.seekTo(s)
    fun stopApp() = client?.stopApp()
    fun setVolume(v: Double) = client?.setVolume(v)
    fun toggleMute() = client?.toggleMute()
}

@Composable
fun CastScreen() {
    DisposableEffect(Unit) { CastCtl.setVisible(true); onDispose { CastCtl.setVisible(false) } }
    // Lock-screen / notification controls need notification permission on Android 13+
    val ctx = LocalContext.current
    val askNotif = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val st by CastCtl.state.collectAsState()
    var choosing by remember { mutableStateOf(CastCtl.host == null) }
    if (choosing) {
        CastPicker(onPicked = { choosing = false }, onCancel = if (CastCtl.host != null) ({ choosing = false }) else null)
    } else {
        CastRemote(st, onChange = { choosing = true })
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

@Composable
private fun rememberImage(url: String?): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, url) {
        value = null
        if (url != null) value = withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000
                c.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (_: Exception) { null }
        }
    }
    return img
}

@Composable
fun CastRemote(st: CastState, onChange: () -> Unit) {
    val u = 1.dp
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    val m = st.media

    // Everything fits on one screen: the picture takes whatever height is left.
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Brush.verticalGradient(listOf(CBg, CBgDark)))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Device header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val dot = when (st.conn) {
                Conn.Connected -> Color(0xFF39D27A)
                Conn.Connecting, Conn.Idle -> Color(0xFFF2B33D)
                else -> Color(0xFFEF5350)
            }
            Box(Modifier.size(11.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(CastCtl.name ?: "Chromecast", color = CLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (val c = st.conn) {
                    Conn.Connected -> "Conectado"
                    Conn.Connecting, Conn.Idle -> "Conectando…"
                    is Conn.Error -> c.msg
                    else -> ""
                }
                Text(sub, color = CGrey, fontSize = 12.sp, maxLines = 1)
            }
            if (st.appName != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF4A4C51), RoundedCornerShape(14.dp))
                        .clickable { CastCtl.stopApp() }.padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("■ Cortar", color = CLabel, fontSize = 13.sp) }
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onChange) { Text("Cambiar", color = CTurq) }
        }

        // Picture (flexible height)
        val img = rememberImage(m?.image)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF111214)),
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(st.appName ?: "Nada en reproducción", color = Color(0xFF55575C), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Title
        Column(Modifier.fillMaxWidth()) {
            Text(st.appName?.uppercase() ?: "", color = CTurq, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                m?.title ?: when {
                    st.conn != Conn.Connected -> " "
                    st.appName == null -> "No se está transmitiendo nada"
                    !st.controllable -> "Esta app no se puede controlar desde acá"
                    else -> "Esperando que empiece la reproducción…"
                },
                color = CLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (m?.subtitle != null) Text(m.subtitle, color = CGrey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Progress
        if (m != null && m.duration > 0) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val pos = if (now > 0) m.positionNow() else 0.0
            Column(Modifier.fillMaxWidth()) {
                Slider(
                    value = if (dragging) dragValue else (pos / m.duration).toFloat().coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { CastCtl.seekTo(dragValue * m.duration); dragging = false },
                    modifier = Modifier.height(28.dp),
                    colors = SliderDefaults.colors(thumbColor = CTurq, activeTrackColor = CTurq, inactiveTrackColor = Color(0xFF3A3B3F)),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(if (dragging) dragValue * m.duration else pos), color = CGrey, fontSize = 12.sp)
                    Text(fmt(m.duration), color = CGrey, fontSize = 12.sp)
                }
            }
        }

        // Transport: previous · -10 · play/pause · +10 · next
        val dim = m == null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundKey(u, 50, onFire = { CastCtl.previous() }) { TrackIcon(next = false, dim = dim || m?.supports(128) == false) }
            RoundKey(u, 60, onFire = { CastCtl.seekBy(-10.0) }) { SkipLabel("10", back = true, dim = dim) }
            val playing = m?.state == "PLAYING" || m?.state == "BUFFERING"
            RoundKey(u, 84, bg = CTurq, bgPressed = CTurqPressed, onFire = { CastCtl.playPause() }) {
                Canvas(Modifier.size(30.dp)) {
                    val c = Color(0xFF16302F)
                    if (playing) {
                        val w = size.width * 0.28f
                        drawRect(c, Offset(size.width * 0.12f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                        drawRect(c, Offset(size.width * 0.60f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                    } else {
                        val p = Path(); p.moveTo(size.width * 0.18f, 0f); p.lineTo(size.width, size.height / 2); p.lineTo(size.width * 0.18f, size.height); p.close()
                        drawPath(p, c)
                    }
                }
            }
            RoundKey(u, 60, onFire = { CastCtl.seekBy(10.0) }) { SkipLabel("10", back = false, dim = dim) }
            RoundKey(u, 50, onFire = { CastCtl.next() }) { TrackIcon(next = true, dim = dim || m?.supports(64) == false) }
        }

        // Volume: mute · − · slider · +
        if (st.conn == Conn.Connected && !st.fixedVolume) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundKey(u, 44, onFire = { CastCtl.toggleMute() }) { SpeakerIcon(muted = st.muted) }
                Spacer(Modifier.width(8.dp))
                RoundKey(u, 40, repeat = true, onFire = { CastCtl.volumeStep(-0.05) }) { Text("−", color = CLabel, fontSize = 20.sp) }
                var volDrag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = volDrag ?: st.volume.toFloat(),
                    onValueChange = { volDrag = it },
                    onValueChangeFinished = { volDrag?.let { CastCtl.setVolume(it.toDouble()) }; volDrag = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).height(28.dp),
                    colors = SliderDefaults.colors(thumbColor = CLabel, activeTrackColor = CLabel, inactiveTrackColor = Color(0xFF3A3B3F)),
                )
                RoundKey(u, 40, repeat = true, onFire = { CastCtl.volumeStep(0.05) }) { Text("+", color = CLabel, fontSize = 20.sp) }
                Spacer(Modifier.width(6.dp))
                Text("${((volDrag ?: st.volume.toFloat()) * 100).toInt()}%", color = CGrey, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }

        // Extras: speed · subtitles
        if (m != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                if (m.supports(8192)) {
                    val speeds = listOf(1.0, 1.25, 1.5, 2.0, 0.75)
                    val label = if (m.rate % 1.0 == 0.0) "${m.rate.toInt()}x" else "${m.rate}x"
                    Chip("Velocidad $label") {
                        val i = speeds.indexOfFirst { kotlin.math.abs(it - m.rate) < 0.01 }
                        CastCtl.setRate(speeds[(i + 1) % speeds.size])
                    }
                }
                if (m.textTracks.isNotEmpty()) {
                    val cur = m.textTracks.firstOrNull { it.first in m.activeTracks }
                    Chip("Subtítulos: ${cur?.second ?: "No"}") {
                        val idx = m.textTracks.indexOf(cur)
                        val nextTrack = if (idx + 1 < m.textTracks.size) m.textTracks[idx + 1].first else null
                        CastCtl.setSubtitle(nextTrack)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E3034)).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = CLabel, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun TrackIcon(next: Boolean, dim: Boolean) = Canvas(Modifier.size(20.dp)) {
    val col = if (dim) Color(0xFF66686D) else CLabel
    val p = Path()
    if (next) { p.moveTo(0f, 0f); p.lineTo(size.width * 0.75f, size.height / 2); p.lineTo(0f, size.height); p.close() }
    else { p.moveTo(size.width, 0f); p.lineTo(size.width * 0.25f, size.height / 2); p.lineTo(size.width, size.height); p.close() }
    drawPath(p, col)
    val x = if (next) size.width * 0.82f else 0f
    drawRect(col, Offset(x, 0f), androidx.compose.ui.geometry.Size(size.width * 0.18f, size.height))
}

@Composable
private fun SkipLabel(n: String, back: Boolean, dim: Boolean) {
    val col = if (dim) Color(0xFF66686D) else CLabel
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            val w = size.minDimension * 0.07f
            drawArc(col, if (back) -70f else -110f, if (back) -280f else 280f, false, style = Stroke(w, cap = StrokeCap.Round))
            // arrow head at the top
            val x = size.width / 2; val y = size.height * 0.04f
            val d = size.width * 0.12f
            val p = Path()
            if (back) { p.moveTo(x - d * 0.2f, y - d * 0.7f); p.lineTo(x - d * 1.1f, y + 0.6f); p.lineTo(x - d * 0.2f, y + d * 0.8f) }
            else { p.moveTo(x + d * 0.2f, y - d * 0.7f); p.lineTo(x + d * 1.1f, y + 0.6f); p.lineTo(x + d * 0.2f, y + d * 0.8f) }
            drawPath(p, col, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(n, color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeakerIcon(muted: Boolean) = Canvas(Modifier.size(24.dp)) {
    val p = Path()
    p.moveTo(0f, size.height * 0.35f); p.lineTo(size.width * 0.25f, size.height * 0.35f)
    p.lineTo(size.width * 0.55f, size.height * 0.08f); p.lineTo(size.width * 0.55f, size.height * 0.92f)
    p.lineTo(size.width * 0.25f, size.height * 0.65f); p.lineTo(0f, size.height * 0.65f); p.close()
    drawPath(p, CLabel)
    val w = size.minDimension * 0.08f
    if (muted) {
        drawLine(Color(0xFFEF5350), Offset(size.width * 0.68f, size.height * 0.3f), Offset(size.width, size.height * 0.7f), w, StrokeCap.Round)
        drawLine(Color(0xFFEF5350), Offset(size.width, size.height * 0.3f), Offset(size.width * 0.68f, size.height * 0.7f), w, StrokeCap.Round)
    } else {
        drawArc(CLabel, -45f, 90f, false, Offset(size.width * 0.45f, size.height * 0.25f), androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.5f), style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun CastPicker(onPicked: () -> Unit, onCancel: (() -> Unit)?) {
    val ctx = LocalContext.current
    val discovery = remember { Discovery(ctx, "_googlecast._tcp") }
    val devices by discovery.devices.collectAsState()
    DisposableEffect(Unit) { discovery.start(); onDispose { discovery.stop() } }
    var manualIp by remember { mutableStateOf(CastCtl.host ?: "") }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Elegí tu Chromecast", color = CLabel, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("El celular tiene que estar en el mismo Wi-Fi.", color = Color(0xFFAAAAAA), fontSize = 14.sp)
        if (devices.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CTurq)
                Spacer(Modifier.width(10.dp))
                Text("Buscando dispositivos…", color = CLabel)
            }
        }
        for (d in devices) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CBg)
                    .border(1.dp, if (d.host == CastCtl.host) CTurq else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { CastCtl.choose(d.host, d.name); onPicked() }.padding(16.dp)
            ) {
                Text(d.name, color = CLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(d.host, color = CGrey, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("¿No aparece? Escribí su IP:", color = Color(0xFFAAAAAA), fontSize = 14.sp)
        OutlinedTextField(
            value = manualIp, onValueChange = { manualIp = it.trim() }, singleLine = true,
            label = { Text("Ej: 192.168.0.30") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { CastCtl.choose(manualIp, "Chromecast"); onPicked() }, enabled = manualIp.isNotBlank()) {
            Text("Usar esta IP")
        }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Volver") }
    }
}
