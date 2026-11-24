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

    fun onStart() { started = true; if (visible) connect() }
    fun onStop() { started = false; disconnect() }
    fun setVisible(v: Boolean) {
        visible = v
        if (v && started) connect() else if (!v) disconnect()
    }

    fun choose(h: String, n: String) { host = h; name = n; connect() }

    fun connect() {
        val h = host ?: return
        client?.close()
        client = CastClient(h) { st ->
            state.value = st
            if (st.conn is Conn.Error && visible && started) {
                main.postDelayed({ if (visible && started && state.value.conn is Conn.Error) connect() }, 3000)
            }
        }.also { it.start() }
    }

    fun disconnect() { client?.close(); client = null; state.value = CastState() }

    fun playPause() = client?.playPause()
    fun seekBy(s: Double) = client?.seekBy(s)
    fun seekTo(s: Double) = client?.seekTo(s)
    fun stopApp() = client?.stopApp()
    fun setVolume(v: Double) = client?.setVolume(v)
    fun toggleMute() = client?.toggleMute()
}

@Composable
fun CastScreen() {
    DisposableEffect(Unit) { CastCtl.setVisible(true); onDispose { CastCtl.setVisible(false) } }
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

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Brush.verticalGradient(listOf(CBg, CBgDark)))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Device header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val dot = when (st.conn) {
                Conn.Connected -> Color(0xFF39D27A)
                Conn.Connecting, Conn.Idle -> Color(0xFFF2B33D)
                else -> Color(0xFFEF5350)
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(CastCtl.name ?: "Chromecast", color = CLabel, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (val c = st.conn) {
                    Conn.Connected -> "Conectado"
                    Conn.Connecting, Conn.Idle -> "Conectando…"
                    is Conn.Error -> c.msg
                    else -> ""
                }
                Text(sub, color = CGrey, fontSize = 13.sp, maxLines = 1)
            }
            TextButton(onClick = onChange) { Text("Cambiar", color = CTurq) }
        }

        // Now playing
        val m = st.media
        val img = rememberImage(m?.image)
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF111214)),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(st.appName ?: "Nada en reproducción", color = Color(0xFF55575C), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth()) {
            Text(st.appName?.uppercase() ?: "", color = CTurq, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                m?.title ?: when {
                    st.conn != Conn.Connected -> " "
                    st.appName == null -> "No se está transmitiendo nada"
                    !st.controllable -> "Esta app no se puede controlar desde acá"
                    else -> "Esperando que empiece la reproducción…"
                },
                color = CLabel, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (m?.subtitle != null) Text(m.subtitle, color = CGrey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m == null && st.appName == null && st.conn == Conn.Connected) {
                Text("Mandá algo desde una app (YouTube, Disney+, etc.) y lo vas a poder controlar acá.", color = CGrey, fontSize = 13.sp)
            }
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
                    colors = SliderDefaults.colors(thumbColor = CTurq, activeTrackColor = CTurq, inactiveTrackColor = Color(0xFF3A3B3F)),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(if (dragging) dragValue * m.duration else pos), color = CGrey, fontSize = 13.sp)
                    Text(fmt(m.duration), color = CGrey, fontSize = 13.sp)
                }
            }
        }

        // Transport controls
        val enabled = m != null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundKey(u, 68, onFire = { CastCtl.seekBy(-10.0) }) { SkipLabel("10", back = true, dim = !enabled) }
            val playing = m?.state == "PLAYING" || m?.state == "BUFFERING"
            RoundKey(u, 100, bg = CTurq, bgPressed = CTurqPressed, onFire = { CastCtl.playPause() }) {
                Canvas(Modifier.size(36.dp)) {
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
            RoundKey(u, 68, onFire = { CastCtl.seekBy(10.0) }) { SkipLabel("10", back = false, dim = !enabled) }
        }

        // Volume
        if (st.conn == Conn.Connected && !st.fixedVolume) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundKey(u, 52, onFire = { CastCtl.toggleMute() }) { SpeakerIcon(muted = st.muted) }
                Spacer(Modifier.width(10.dp))
                var volDrag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = volDrag ?: st.volume.toFloat(),
                    onValueChange = { volDrag = it },
                    onValueChangeFinished = { volDrag?.let { CastCtl.setVolume(it.toDouble()) }; volDrag = null },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = CLabel, activeTrackColor = CLabel, inactiveTrackColor = Color(0xFF3A3B3F)),
                )
                Spacer(Modifier.width(10.dp))
                Text("${((volDrag ?: st.volume.toFloat()) * 100).toInt()}%", color = CGrey, fontSize = 13.sp, modifier = Modifier.width(40.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundKey(u, 56, repeat = true, onFire = { CastCtl.setVolume(CastCtl.state.value.volume - 0.05) }) {
                    Text("VOL −", color = CLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                RoundKey(u, 56, repeat = true, onFire = { CastCtl.setVolume(CastCtl.state.value.volume + 0.05) }) {
                    Text("VOL +", color = CLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (st.appName != null) {
            OutlinedButton(onClick = { CastCtl.stopApp() }) { Text("Dejar de transmitir", color = CLabel) }
        }
    }
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
