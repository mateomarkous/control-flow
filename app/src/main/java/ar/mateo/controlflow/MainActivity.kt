package ar.mateo.controlflow

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

// Android key codes (same ones the Flow remote sends)
object K {
    const val HOME = 3; const val BACK = 4
    const val D0 = 7 // 0..9 = 7..16
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val OK = 23
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val POWER = 26
    const val EXIT = 111 // ESCAPE
    const val CH_UP = 166; const val CH_DOWN = 167
    const val GUIDE = 172; const val TV_INPUT = 178; const val LAST = 229
}

object Apps {
    const val NETFLIX = "netflix://"
    const val YOUTUBE = "https://www.youtube.com"
    const val PRIME = "https://app.primevideo.com"
    const val DISNEY = "https://www.disneyplus.com"
    const val HBO = "market://launch?id=com.wbd.stream"
    const val FLOW = "market://launch?id=ar.com.flow.androidtv_stb"
}

private val Bg = Color(0xFF0D0D0F)
private val Body = Color(0xFF232427)
private val BodyDark = Color(0xFF1A1B1D)
private val Btn = Color(0xFF111214)
private val BtnPressed = Color(0xFF2C2E32)
private val Label = Color(0xFFEDEDED)
private val Turq = Color(0xFF6DD3CF)
private val TurqPressed = Color(0xFF52B8B4)
private val Pill = Color(0xFFF3F3F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ctl.init(this)
        CastCtl.init(this)
        setContent { AppRoot() }
    }

    override fun onStart() { super.onStart(); Ctl.onStart(); CastCtl.onStart() }
    override fun onStop() { super.onStop(); Ctl.onStop(); CastCtl.onStop() }

    /** The phone's volume buttons control the TV (Flow tab) or the Chromecast (Chromecast tab). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val down = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (up || down) {
            if (UiState.tab == 0 && Ctl.host != null && Ctl.conn.value == Conn.Connected) {
                Ctl.key(if (up) K.VOL_UP else K.VOL_DOWN); return true
            }
            if (UiState.tab == 1 && CastCtl.state.value.conn == Conn.Connected && !CastCtl.state.value.fixedVolume) {
                CastCtl.volumeStep(if (up) 0.05 else -0.05); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((UiState.tab == 0 && Ctl.conn.value == Conn.Connected) || (UiState.tab == 1 && CastCtl.state.value.conn == Conn.Connected)) return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

/** Small UI state shared with the activity. */
object UiState {
    @Volatile var tab = 0
    var showFlowInfo by mutableStateOf(false)
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(prefs.getInt("tab", 0)) }
    UiState.tab = tab
    MaterialTheme(colorScheme = darkColorScheme(primary = Turq, onPrimary = Color(0xFF0E2A29), background = Bg, surface = Body)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TabSwitch(tab) { tab = it; prefs.edit().putInt("tab", it).apply() }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (tab == 0) FlowTab() else CastScreen()
                }
            }
        }
    }
}

@Composable
private fun TabSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val conn by Ctl.conn.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(38.dp))
        Spacer(Modifier.width(10.dp))
        TabPills(selected, onSelect, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        // Flow connection status (tap: reconnect / change box)
        if (selected == 0 && Ctl.host != null) {
            val color = when (conn) {
                Conn.Connected -> Color(0xFF39D27A)
                Conn.Connecting, Conn.Idle -> Color(0xFFF2B33D)
                else -> Color(0xFFEF5350)
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Body).clickable { UiState.showFlowInfo = true },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) }
        } else Spacer(Modifier.size(38.dp))
    }
}

@Composable
private fun TabPills(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(Body).padding(3.dp),
    ) {
        listOf("Flow", "Chromecast").forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(if (on) Turq else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFF16302F) else Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The original Flowbox remote (unchanged behaviour). */
@Composable
fun FlowTab() {
    val conn by Ctl.conn.collectAsState()
    var setup by remember { mutableStateOf(Ctl.host == null) }
    LaunchedEffect(conn) { if (conn == Conn.NeedPairing) setup = true }
    if (setup) {
        SetupScreen(
            repairHost = if (conn == Conn.NeedPairing) Ctl.host else null,
            onDone = { setup = false },
            onCancel = if (Ctl.host != null) ({ setup = false; Ctl.cancelPairing(); Ctl.connect() }) else null,
        )
    } else {
        RemoteScreen(conn, onSetup = { setup = true })
    }
}

// ---------------------------------------------------------------- Remote

@Composable
fun RemoteScreen(conn: Conn, onSetup: () -> Unit) {
    var showKeyboard by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) } // -1 = new shortcut
    val shortcuts by Ctl.shortcuts.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        // Fixed rows are sized in "units"; the D-pad takes all the height that is left.
        val fixedH = 690f
        val u = min(maxHeight.value / (fixedH + 230f), maxWidth.value / 370f).dp
        Column(
            Modifier
                .fillMaxSize()
                .shadow(16.dp, RoundedCornerShape(u * 46))
                .clip(RoundedCornerShape(u * 46))
                .background(Brush.verticalGradient(listOf(Body, BodyDark)))
                .padding(horizontal = u * 20, vertical = u * 14),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // MODE · LAST · keyboard · GUIDE · power
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundKey(u, 54, onFire = { Ctl.key(K.TV_INPUT) }) { TextLabel("MODE", u, 12) }
                RoundKey(u, 54, onFire = { Ctl.key(K.LAST) }) { TextLabel("LAST", u, 13) }
                RoundKey(u, 54, onFire = { showKeyboard = true }) { KeyboardIcon(u) }
                RoundKey(u, 54, onFire = { Ctl.key(K.GUIDE) }) { TextLabel("GUIDE", u, 12) }
                RoundKey(u, 54, onFire = { Ctl.key(K.POWER) }) { PowerIcon(u) }
            }
            // D-pad: as big as the remaining space allows
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = u * 8), contentAlignment = Alignment.Center) {
                val d = min(maxWidth.value * 0.80f, maxHeight.value) / u.value
                DPad(u, d.toInt())
            }
            // Back · flow · EXIT
            Box(Modifier.fillMaxWidth().height(u * 64)) {
                Box(Modifier.align(Alignment.TopStart).offset(x = u * 6)) {
                    RoundKey(u, 56, onFire = { Ctl.key(K.BACK) }) { BackIcon(u) }
                }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    RoundKey(u, 64, bg = Turq, bgPressed = TurqPressed, onFire = { Ctl.openApp(Apps.FLOW) }) {
                        Text("flow", color = Color(0xFF16302F), fontSize = sp(u, 23), fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).offset(x = -u * 6)) {
                    RoundKey(u, 56, onFire = { Ctl.key(K.EXIT) }) { TextLabel("EXIT", u, 14) }
                }
            }
            Spacer(Modifier.height(u * 6))
            // VOL · home · CH
            Row(Modifier.fillMaxWidth().padding(horizontal = u * 6), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Rocker(u, "VOL", { Ctl.key(K.VOL_UP) }, { Ctl.key(K.VOL_DOWN) }, top = { PlusIcon(u) }, bottom = { MinusIcon(u) })
                RoundKey(u, 70, onFire = { Ctl.key(K.HOME) }) { RingIcon(u) }
                Rocker(u, "CH", { Ctl.key(K.CH_UP) }, { Ctl.key(K.CH_DOWN) }, top = { Chevron(u, up = true) }, bottom = { Chevron(u, up = false) })
            }
            Spacer(Modifier.height(u * 14))
            // App shortcuts: 4 per row, editable (long press), "+" to add
            val tiles: List<Int> = shortcuts.indices.toList() + (if (shortcuts.size < 8) listOf(-1) else emptyList())
            tiles.chunked(4).forEachIndexed { r, row ->
                if (r > 0) Spacer(Modifier.height(u * 8))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 8)) {
                    for (i in row) {
                        if (i == -1) AddTile(u, Modifier.weight(1f)) { editIndex = -1 }
                        else {
                            val sc = shortcuts[i]
                            ShortcutTile(u, sc, Modifier.weight(1f), onTap = { Ctl.openShortcut(i) }, onLongPress = { editIndex = i })
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(u * 14))
            // Numbers: wide keys using the full width
            val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -1))
            rows.forEachIndexed { r, row ->
                if (r > 0) Spacer(Modifier.height(u * 8))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 10)) {
                    for (n in row) {
                        if (n < 0) Spacer(Modifier.weight(1f))
                        else WideKey(u, Modifier.weight(1f), onFire = { Ctl.key(K.D0 + n) }) { TextLabel("$n", u, 24, FontWeight.Normal) }
                    }
                }
            }
        }
    }

    if (showKeyboard) KeyboardDialog(onClose = { showKeyboard = false })
    editIndex?.let { idx -> ShortcutDialog(idx, onClose = { editIndex = null }) }

    if (UiState.showFlowInfo) {
        val current by Ctl.currentApp.collectAsState()
        AlertDialog(
            onDismissRequest = { UiState.showFlowInfo = false },
            title = { Text("Conexión") },
            text = {
                Text(
                    "Deco: ${Ctl.boxName ?: "Flowbox"} (${Ctl.host ?: "-"})\nEstado: " + when (conn) {
                        Conn.Connected -> "conectado"
                        Conn.Connecting -> "conectando…"
                        Conn.Idle -> "desconectado"
                        Conn.NeedPairing -> "hay que vincular de nuevo"
                        is Conn.Error -> "error: ${conn.msg}"
                    } + (current?.let { "\nApp en pantalla: $it" } ?: "")
                )
            },
            confirmButton = { TextButton(onClick = { UiState.showFlowInfo = false; Ctl.connect() }) { Text("Reconectar") } },
            dismissButton = { TextButton(onClick = { UiState.showFlowInfo = false; onSetup() }) { Text("Cambiar deco / vincular") } },
        )
    }
}

/** Colors available for shortcut labels. */
val ShortcutColors = listOf(
    Color(0xFFD81F26), Color(0xFF202020), Color(0xFF1B2530), Color(0xFF13235E),
    Color(0xFF2A1F8F), Color(0xFF1DB954), Color(0xFF0064FF), Color(0xFFE5A00D),
)

@Composable
fun WideKey(u: Dp, modifier: Modifier, repeat: Boolean = false, onFire: () -> Unit, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val fire by rememberUpdatedState(onFire)
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(u * 26)
    Box(
        modifier.height(u * 52)
            .graphicsLayer { val s = if (pressed) 0.95f else 1f; scaleX = s; scaleY = s }
            .shadow(if (pressed) 1.dp else 4.dp, shape).clip(shape)
            .background(if (pressed) BtnPressed else Btn)
            .border(1.dp, Color(0x22FFFFFF), shape)
            .pressKey(repeat, { pressed = it }, { _, _ -> fire() }, haptic),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ShortcutTile(u: Dp, sc: Ctl.Shortcut, modifier: Modifier, onTap: () -> Unit, onLongPress: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val view = LocalView.current
    val shape = RoundedCornerShape(u * 22)
    Box(
        modifier.height(u * 44)
            .graphicsLayer { val s = if (pressed) 0.95f else 1f; scaleX = s; scaleY = s }
            .shadow(3.dp, shape).clip(shape).background(if (pressed) Color(0xFFD9D9D6) else Pill)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); tap() },
                    onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); long() },
                )
            }
            .padding(horizontal = u * 4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            sc.name, color = ShortcutColors.getOrElse(sc.color) { ShortcutColors[1] },
            fontSize = sp(u, if (sc.name.length > 9) 12 else 15), fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AddTile(u: Dp, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(u * 22)
    Box(
        modifier.height(u * 44).clip(shape).border(1.5.dp, Color(0xFF55575C), shape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text("+ App", color = Color(0xFFAAAAAA), fontSize = sp(u, 14), fontWeight = FontWeight.SemiBold) }
}

/** Add / edit a shortcut. index = -1 creates a new one. */
@Composable
fun ShortcutDialog(index: Int, onClose: () -> Unit) {
    val list = Ctl.shortcuts.value
    val editing = list.getOrNull(index)
    val current by Ctl.currentApp.collectAsState()
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var link by remember { mutableStateOf(editing?.link ?: "") }
    var pkg by remember { mutableStateOf(editing?.packageName()) }
    var color by remember { mutableStateOf(editing?.color ?: 1) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (editing == null) "Nuevo botón" else "Editar botón") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(14) }, singleLine = true,
                    label = { Text("Nombre del botón") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("1. Abrí la app en la tele con el control y tocá:", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                Button(
                    onClick = { current?.let { pkg = it; link = Ctl.defaultLinkFor(it); testResult = null } },
                    enabled = current != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Turq),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (current != null) "Usar la app en pantalla" else "Esperando a que el deco informe la app…", fontSize = 14.sp) }
                current?.let { Text("En pantalla ahora: $it", color = Color(0xFF8BD9A9), fontSize = 12.sp) }
                pkg?.let { Text("App elegida: $it", color = Label, fontSize = 13.sp) }
                Text("2. Link para abrirla (el deco abre apps con links):", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                OutlinedTextField(
                    value = link, onValueChange = { link = it.trim(); testResult = null }, singleLine = true,
                    label = { Text("Link") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                val suggestions = pkg?.let { Ctl.knownLinks[it] }.orEmpty()
                if (suggestions.isNotEmpty()) {
                    Text("Links sugeridos (tocá uno):", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    for (sug in suggestions) {
                        Text(sug, color = Turq, fontSize = 13.sp, modifier = Modifier.clickable { link = sug; testResult = null }.padding(vertical = 2.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        testResult = "Probando…"
                        Ctl.testLaunch(link, pkg) { ok ->
                            testResult = when (ok) { true -> "✓ Abrió la app"; false -> "✗ No abrió, probá otro link"; null -> "Enviado (no puedo verificar sin app elegida)" }
                        }
                    }, enabled = link.isNotBlank()) { Text("Probar") }
                    testResult?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("✓")) Color(0xFF8BD9A9) else if (it.startsWith("✗")) Color(0xFFEF6F6C) else Label) }
                }
                Text("Color del nombre", color = Label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(c)
                                .border(if (i == color) 3.dp else 1.dp, if (i == color) Turq else Color(0x55FFFFFF), CircleShape)
                                .clickable { color = i }
                        )
                    }
                }
                if (editing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            if (index > 0) { val l = list.toMutableList(); val t = l[index]; l[index] = l[index - 1]; l[index - 1] = t; Ctl.saveShortcuts(l); onClose() }
                        }, enabled = index > 0) { Text("◀ Mover") }
                        TextButton(onClick = {
                            if (index < list.size - 1) { val l = list.toMutableList(); val t = l[index]; l[index] = l[index + 1]; l[index + 1] = t; Ctl.saveShortcuts(l); onClose() }
                        }, enabled = index < list.size - 1) { Text("Mover ▶") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { Ctl.saveShortcuts(list.filterIndexed { i, _ -> i != index }); onClose() }) {
                            Text("Borrar", color = Color(0xFFEF6F6C))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sc = Ctl.Shortcut(name.trim(), link, color, pkg)
                    val l = list.toMutableList()
                    if (editing == null) l.add(sc) else l[index] = sc
                    Ctl.saveShortcuts(l); onClose()
                },
                enabled = name.isNotBlank() && link.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

// ---- small drawn icons
@Composable
private fun KeyboardIcon(u: Dp) = Canvas(Modifier.size(u * 28, u * 20)) {
    val w = size.minDimension * 0.09f
    drawRoundRect(Label, style = Stroke(w), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.15f))
    val kx = size.width / 6f; val ky = size.height / 4f
    for (r in 1..2) for (c in 1..5) drawCircle(Label, radius = w * 0.7f, center = Offset(c * kx, r * ky))
    drawLine(Label, Offset(kx * 1.6f, ky * 3.1f), Offset(kx * 4.4f, ky * 3.1f), w, StrokeCap.Round)
}
@Composable
private fun PowerIcon(u: Dp) = Canvas(Modifier.size(u * 24)) {
    val w = size.minDimension * 0.11f
    drawArc(Label, -60f, 300f, false, style = Stroke(w, cap = StrokeCap.Round))
    drawLine(Label, Offset(center.x, size.height * 0.02f), Offset(center.x, size.height * 0.45f), w, StrokeCap.Round)
}

@Composable
private fun BackIcon(u: Dp) = Canvas(Modifier.size(u * 26)) {
    val w = size.minDimension * 0.1f
    val p = Path()
    p.moveTo(size.width * 0.28f, size.height * 0.3f)
    p.lineTo(size.width * 0.65f, size.height * 0.3f)
    p.cubicTo(size.width * 0.95f, size.height * 0.3f, size.width * 0.95f, size.height * 0.8f, size.width * 0.65f, size.height * 0.8f)
    p.lineTo(size.width * 0.3f, size.height * 0.8f)
    drawPath(p, Label, style = Stroke(w, cap = StrokeCap.Round))
    val h = Path()
    h.moveTo(size.width * 0.45f, size.height * 0.1f)
    h.lineTo(size.width * 0.22f, size.height * 0.3f)
    h.lineTo(size.width * 0.45f, size.height * 0.5f)
    drawPath(h, Label, style = Stroke(w, cap = StrokeCap.Round))
}

@Composable
private fun RingIcon(u: Dp) = Canvas(Modifier.size(u * 40)) {
    drawCircle(Label, radius = size.minDimension * 0.42f, style = Stroke(size.minDimension * 0.1f))
}

@Composable
private fun PlusIcon(u: Dp) = Canvas(Modifier.size(u * 26)) {
    val w = size.minDimension * 0.09f
    drawLine(Label, Offset(0f, center.y), Offset(size.width, center.y), w)
    drawLine(Label, Offset(center.x, 0f), Offset(center.x, size.height), w)
}

@Composable
private fun MinusIcon(u: Dp) = Canvas(Modifier.size(u * 26)) {
    drawLine(Label, Offset(0f, center.y), Offset(size.width, center.y), size.minDimension * 0.09f)
}

@Composable
private fun Chevron(u: Dp, up: Boolean) = Canvas(Modifier.size(u * 30, u * 18)) {
    val w = size.height * 0.15f
    val p = Path()
    if (up) { p.moveTo(0f, size.height); p.lineTo(center.x, 0f); p.lineTo(size.width, size.height) }
    else { p.moveTo(0f, 0f); p.lineTo(center.x, size.height); p.lineTo(size.width, 0f) }
    drawPath(p, Label, style = Stroke(w, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- Setup / pairing

@Composable
fun SetupScreen(repairHost: String?, onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val ctx = LocalContext.current
    val discovery = remember { Discovery(ctx) }
    val devices by discovery.devices.collectAsState()
    DisposableEffect(Unit) { discovery.start(); onDispose { discovery.stop() } }

    var manualIp by remember { mutableStateOf(Ctl.host ?: "") }
    var target by remember { mutableStateOf<Discovery.Dev?>(null) }
    var step by remember { mutableStateOf(0) } // 0 choose, 1 connecting, 2 enter code, 3 verifying
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun begin(d: Discovery.Dev) {
        target = d; step = 1; error = null; code = ""
        Ctl.startPairing(d.host) { err ->
            if (err == null) step = 2 else { error = err; step = 0 }
        }
    }

    LaunchedEffect(repairHost) {
        if (repairHost != null) begin(Discovery.Dev(Ctl.boxName ?: "Flowbox", repairHost))
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Control Flow", color = Turq, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        when (step) {
            0 -> {
                Text("Elegí tu Flowbox", color = Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("El celular tiene que estar en el mismo Wi-Fi que el deco.", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Turq)
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando decos en tu red…", color = Label)
                    }
                }
                for (d in devices) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Body).clickable { begin(d) }.padding(16.dp)
                    ) {
                        Text(d.name, color = Label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(d.host, color = Color(0xFF9A9A9A), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("¿No aparece? Escribí la IP del deco:", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                OutlinedTextField(
                    value = manualIp, onValueChange = { manualIp = it.trim() }, singleLine = true,
                    label = { Text("Ej: 192.168.0.25") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { if (manualIp.isNotBlank()) begin(Discovery.Dev("Flowbox", manualIp)) }, enabled = manualIp.isNotBlank()) {
                    Text("Conectar a esta IP")
                }
            }
            1 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Turq)
                    Spacer(Modifier.width(12.dp))
                    Text("Conectando con ${target?.name}…", color = Label, fontSize = 17.sp)
                }
            }
            2, 3 -> {
                Text("Mirá la tele", color = Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Aparece un código de 6 caracteres. Escribilo acá:", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val t = target ?: return@Button
                        step = 3; error = null
                        Ctl.finishPairing(t.host, t.name, code) { err ->
                            if (err == null) onDone() else { error = err; step = 2 }
                        }
                    },
                    enabled = code.length == 6 && step == 2,
                    colors = ButtonDefaults.buttonColors(containerColor = Turq),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (step == 3) "Vinculando…" else "Vincular", fontSize = 17.sp) }
                TextButton(onClick = { Ctl.cancelPairing(); step = 0 }) { Text("Elegir otro deco") }
            }
        }
        error?.let { Text(it, color = Color(0xFFEF6F6C), fontSize = 14.sp) }
        if (onCancel != null && step != 3) {
            TextButton(onClick = onCancel) { Text("Volver al control") }
        }
    }
}
