@file:OptIn(ExperimentalFoundationApi::class)

package it.andreadeangelis.launcher

import android.app.AlarmManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFFEDEAE4)
private val Muted = Color(0xFFB5B0A7)
private val Dim = Color(0xFF8C8880)
private val Accent = Color(0xFFE8A33D)
private val Panel = Color(0xB31A1A1C)
private val Line = Color(0xFF2A2A2D)
private val Night = Color(0xFF0B0B0C)

data class Cmd(val label: String, val run: () -> Unit)

data class Hit(
    val label: String,
    val icon: ImageBitmap?,
    val kind: String,
    val isNew: Boolean,
    val app: AppEntry?,
    val run: () -> Unit,
)

class MainActivity : ComponentActivity() {

    private val apps = mutableStateOf<List<AppEntry>>(emptyList())
    private val newPkgs = mutableStateOf<Set<String>>(emptySet())
    private val pinned = mutableStateOf<List<String>>(emptyList())
    private val nextAlarm = mutableStateOf<String?>(null)
    private val resetTick = mutableIntStateOf(0)

    private val commands: List<Cmd> by lazy {
        listOf(
            Cmd("Sveglia") { openAlarms() },
            Cmd("Hotspot") { openHotspot() },
            Cmd("Spegni PC") { toast("Comando da collegare al programma del PC") },
            Cmd("Wi-Fi") { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            Cmd("Bluetooth") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Cmd("Impostazioni") { openSettings(Settings.ACTION_SETTINGS) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resetTick.intValue = resetTick.intValue + 1
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { AppStore.load(this@MainActivity) }
            apps.value = list
            newPkgs.value = AppStore.newPackages(this@MainActivity, list)
            pinned.value = AppStore.pinned(this@MainActivity, list)
            nextAlarm.value = alarmText()
        }
    }

    // ---------- Azioni ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun safeStart(i: Intent, fail: String) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(fail)
        }
    }

    private fun launch(a: AppEntry) {
        safeStart(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.activity)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            "Impossibile aprire ${a.label}",
        )
        AppStore.markSeen(this, a.pkg)
        newPkgs.value = newPkgs.value - a.pkg
    }

    private fun launchPkg(pkg: String, name: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toast("$name non è installata") else safeStart(i, "Impossibile aprire $name")
    }

    private fun openAlarms() = safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Sveglia non disponibile")

    private fun openSettings(action: String) = safeStart(Intent(action), "Impostazione non disponibile")

    private fun openHotspot() {
        try {
            startActivity(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    private fun appInfo(pkg: String) = safeStart(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        "Info non disponibili",
    )

    private fun uninstall(pkg: String) = safeStart(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
        "Impossibile disinstallare",
    )

    private fun togglePin(pkg: String) {
        val cur = pinned.value
        val next = if (pkg in cur) cur - pkg else cur + pkg
        pinned.value = next
        AppStore.setPinned(this, next)
    }

    private fun mediaKey(code: Int) {
        val am = getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun alarmText(): String? {
        val am = getSystemService(AlarmManager::class.java) ?: return null
        val t = am.nextAlarmClock?.triggerTime ?: return null
        val alarm = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        val sameDay = alarm.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            alarm.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) "HH:mm" else "EEE HH:mm"
        return "Sveglia " + SimpleDateFormat(fmt, Locale.ITALY).format(Date(t))
    }

    private fun search(query: String): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val cmdHits = commands
            .filter { normalize(it.label).contains(q) }
            .map { c -> Hit(c.label, null, "Comando", false, null, c.run) }
        val appHits = apps.value
            .filter { it.search.contains(q) }
            .sortedBy { if (it.search.startsWith(q)) 0 else 1 }
            .map { a -> Hit(a.label, a.icon, "App", a.pkg in newPkgs.value, a) { launch(a) } }
        return appHits + cmdHits
    }

    // ---------- Interfaccia ----------

    @Composable
    private fun Root() {
        var query by remember { mutableStateOf("") }
        var drawerOpen by remember { mutableStateOf(false) }
        val tick = resetTick.intValue

        LaunchedEffect(tick) {
            query = ""
            drawerOpen = false
        }
        BackHandler(enabled = drawerOpen || query.isNotEmpty()) {
            if (drawerOpen) drawerOpen = false else query = ""
        }

        val results = remember(query, apps.value, newPkgs.value) { search(query) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x590B0B0C), Color(0xE60B0B0C))))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -120f) drawerOpen = true },
                        ) { _, dy -> total += dy }
                    }
            ) {
                Spacer(Modifier.height(24.dp))
                ClockHeader()
                Spacer(Modifier.height(18.dp))
                SearchField(query, "Cerca app e comandi", { query = it }) { results.firstOrNull()?.run?.invoke() }
                Spacer(Modifier.height(18.dp))
                if (query.isBlank()) {
                    HomeContent()
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Scorri verso l'alto per tutte le app",
                        color = Dim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { drawerOpen = true }
                            .padding(vertical = 14.dp),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(results) { h -> HitRow(h) }
                        if (results.isEmpty()) {
                            item { Text("Nessun risultato", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Drawer { drawerOpen = false }
            }
        }
    }

    @Composable
    private fun ClockHeader() {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = Date()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
        Text(
            SimpleDateFormat("HH:mm", Locale.ITALY).format(now),
            color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp,
        )
        Text(
            SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(now).replaceFirstChar { it.uppercase() },
            color = Muted, fontSize = 15.sp,
        )
        val alarm = nextAlarm.value
        if (alarm != null) {
            Text(
                alarm,
                color = Accent, fontSize = 13.sp,
                modifier = Modifier.clickable { openAlarms() }.padding(top = 6.dp),
            )
        }
    }

    @Composable
    private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, onGo: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape)
                .background(Panel)
                .border(1.dp, Line, shape)
                .padding(horizontal = 16.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = Dim, fontSize = 16.sp)
                    inner()
                }
            },
        )
    }

    @Composable
    private fun HomeContent() {
        val byPkg = apps.value.associateBy { it.pkg }
        val fav = pinned.value.mapNotNull { byPkg[it] }
        fav.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { a -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppTile(a) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        MusicCard()
        Spacer(Modifier.height(16.dp))
        Text("COMANDI RAPIDI", color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            commands.take(3).forEach { c ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Line, RoundedCornerShape(14.dp))
                        .clickable { c.run() },
                    contentAlignment = Alignment.Center,
                ) { Text(c.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }

    @Composable
    private fun AppTile(a: AppEntry) {
        var menu by remember { mutableStateOf(false) }
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = { launch(a) }, onLongClick = { menu = true })
                    .padding(vertical = 6.dp),
            ) {
                Image(a.icon, contentDescription = a.label, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(6.dp))
                Text(a.label, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppMenu(a, menu) { menu = false }
        }
    }

    @Composable
    private fun AppMenu(a: AppEntry, open: Boolean, onDismiss: () -> Unit) {
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            val isPinned = a.pkg in pinned.value
            DropdownMenuItem(
                text = { Text(if (isPinned) "Rimuovi dalle principali" else "Fissa nelle principali") },
                onClick = { togglePin(a.pkg); onDismiss() },
            )
            DropdownMenuItem(text = { Text("Info app") }, onClick = { appInfo(a.pkg); onDismiss() })
            DropdownMenuItem(text = { Text("Disinstalla") }, onClick = { uninstall(a.pkg); onDismiss() })
        }
    }

    @Composable
    private fun MusicCard() {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Panel)
                .border(1.dp, Line, shape)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "APPLE MUSIC", color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f).clickable { launchPkg("com.apple.android.music", "Apple Music") },
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .clickable { launchPkg("com.shazam.android", "Shazam") }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Shazam", color = Night, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaButton(Glyph.Prev, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                MediaButton(Glyph.Play, true) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                MediaButton(Glyph.Next, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
            }
        }
    }

    private enum class Glyph { Prev, Play, Next }

    @Composable
    private fun MediaButton(g: Glyph, primary: Boolean, onClick: () -> Unit) {
        val bg = if (primary) Ink else Color.Transparent
        val fg = if (primary) Night else Ink
        Box(
            Modifier
                .size(if (primary) 52.dp else 44.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val h = size.height
                when (g) {
                    Glyph.Play -> drawPath(Path().apply { moveTo(w * 0.2f, 0f); lineTo(w, h / 2); lineTo(w * 0.2f, h); close() }, fg)
                    Glyph.Next -> {
                        drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.75f, h / 2); lineTo(0f, h); close() }, fg)
                        drawLine(fg, Offset(w * 0.9f, 0f), Offset(w * 0.9f, h), strokeWidth = w * 0.16f)
                    }
                    Glyph.Prev -> {
                        drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.25f, h / 2); lineTo(w, h); close() }, fg)
                        drawLine(fg, Offset(w * 0.1f, 0f), Offset(w * 0.1f, h), strokeWidth = w * 0.16f)
                    }
                }
            }
        }
    }

    @Composable
    private fun HitRow(h: Hit) {
        var menu by remember { mutableStateOf(false) }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { h.run() }, onLongClick = { if (h.app != null) menu = true })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (h.icon != null) {
                    Image(h.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Line),
                        contentAlignment = Alignment.Center,
                    ) { Text(h.label.take(1), color = Accent, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(14.dp))
                Text(h.label, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (h.isNew) {
                    Text(
                        "Nuova", color = Night, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(h.kind, color = Dim, fontSize = 12.sp)
            }
            if (h.app != null) AppMenu(h.app, menu) { menu = false }
        }
    }

    @Composable
    private fun Drawer(onClose: () -> Unit) {
        var q by remember { mutableStateOf("") }
        val all = apps.value
        val nq = normalize(q)
        val shown = if (nq.isEmpty()) all else all.filter { it.search.contains(nq) }
        val fresh = shown.filter { it.pkg in newPkgs.value }
        val rest = shown.filter { it.pkg !in newPkgs.value }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF20B0B0C))
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > 120f) onClose() },
                        ) { _, dy -> total += dy }
                    }
                    .padding(vertical = 16.dp),
            ) {
                Text("Tutte le app", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    "Chiudi", color = Accent, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(10.dp),
                )
            }
            SearchField(q, "Cerca tra ${all.size} app", { q = it }) { shown.firstOrNull()?.let { launch(it) } }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (fresh.isNotEmpty()) {
                    item { SectionLabel("NUOVE") }
                    items(fresh, key = { "n" + it.key }) { a -> HitRow(Hit(a.label, a.icon, "", true, a) { launch(a) }) }
                    item { SectionLabel("TUTTE") }
                }
                items(rest, key = { it.key }) { a -> HitRow(Hit(a.label, a.icon, "", false, a) { launch(a) }) }
            }
        }
    }

    @Composable
    private fun SectionLabel(t: String) {
        Text(t, color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
    }
}
