@file:OptIn(ExperimentalFoundationApi::class)

package it.andreadeangelis.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFFEDEAE4)
private val Muted = Color(0xFFB5B0A7)
private val Dim = Color(0xFF8C8880)
private val Accent = Color(0xFFE8A33D)
private val Glass = Color(0x0FFFFFFF)
private val GlassLine = Color(0x1AFFFFFF)
private val Night = Color(0xFF050506)

data class Cmd(val label: String, val run: () -> Unit)

data class Hit(
    val label: String,
    val icon: ImageBitmap?,
    val kind: String,
    val isNew: Boolean,
    val app: AppEntry?,
    val run: () -> Unit,
)

data class Suggestion(val app: AppEntry, val why: String)

/** Modalità della home: cambiano le app suggerite. */
enum class Mode(val label: String, val title: String) {
    Mattina("Mattina", "MATTINA"), Giorno("Giorno", "GIORNO"), Set("Set", "SUL SET"), Sera("Sera", "SERA")
}

class MainActivity : ComponentActivity() {

    private val apps = mutableStateOf<List<AppEntry>>(emptyList())
    private val newPkgs = mutableStateOf<Set<String>>(emptySet())
    private val pinned = mutableStateOf<List<String>>(emptyList())
    private val nextAlarm = mutableStateOf<String?>(null)
    private val nextEvent = mutableStateOf<String?>(null)
    private val calendarOk = mutableStateOf(false)
    private val mediaOk = mutableStateOf(false)
    private val playing = mutableStateOf<NowPlaying?>(null)
    private val modeOverride = mutableStateOf<Mode?>(null)
    private val usageTick = mutableIntStateOf(0)
    private val resetTick = mutableIntStateOf(0)
    private val enterTick = mutableIntStateOf(0)

    /** Ultima zona toccata: l'app si apre "crescendo" da lì. */
    private var pendingFrom: Rect? = null
    private var pendingAt = 0L

    private fun markFrom(r: Rect?) {
        pendingFrom = r
        pendingAt = android.os.SystemClock.uptimeMillis()
    }

    private val askCalendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val commands: List<Cmd> by lazy {
        listOf(
            Cmd("Sveglia") { openAlarms() },
            Cmd("Hotspot") { openHotspot() },
            Cmd("Spegni PC") { toast("Comando da collegare al programma del PC") },
            Cmd("Wi-Fi") { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            Cmd("Bluetooth") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Cmd("Notifiche") { expandNotifications() },
            Cmd("Impostazioni") { openSettings(Settings.ACTION_SETTINGS) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val np = withContext(Dispatchers.Default) { Media.current(this@MainActivity) }
                    playing.value = np
                    delay(1500)
                }
            }
        }
        setContent {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Grotesk, color = Ink)) { Root() }
        }
    }

    override fun onResume() {
        super.onResume()
        enterTick.intValue = enterTick.intValue + 1
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
            calendarOk.value = CalendarRepo.granted(this@MainActivity)
            mediaOk.value = Media.hasAccess(this@MainActivity)
            val ev = withContext(Dispatchers.IO) { CalendarRepo.next(this@MainActivity) }
            nextEvent.value = ev?.let { "${SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(it.begin))} · ${it.title}" }
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
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(a.pkg, a.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val from = if (android.os.SystemClock.uptimeMillis() - pendingAt < 600) pendingFrom else null
        pendingFrom = null
        val opts = from?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(),
                it.width.toInt().coerceAtLeast(1), it.height.toInt().coerceAtLeast(1),
            ).toBundle()
        }
        try {
            startActivity(i, opts)
        } catch (e: Exception) {
            toast("Impossibile aprire ${a.label}")
        }
        AppStore.markSeen(this, a.pkg)
        Usage.record(this, a.pkg)
        newPkgs.value = newPkgs.value - a.pkg
        usageTick.intValue = usageTick.intValue + 1
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

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
            // Alcune versioni non lo permettono: nessun problema.
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

    private fun autoMode(): Mode {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> Mode.Mattina
            in 12..18 -> Mode.Giorno
            else -> Mode.Sera
        }
    }

    private fun find(spec: String): AppEntry? {
        val list = apps.value
        return if (spec.startsWith("pkg:")) list.firstOrNull { it.pkg == spec.removePrefix("pkg:") }
        else list.firstOrNull { it.label.equals(spec.removePrefix("label:"), ignoreCase = true) }
    }

    private fun defaults(m: Mode): List<Pair<String, String>> = when (m) {
        Mode.Mattina -> listOf(
            "pkg:com.google.android.calendar" to "La tua giornata",
            "pkg:com.anthropic.claude" to "Il tuo assistente",
            "pkg:notion.id" to "Note e progetti",
        )
        Mode.Giorno -> listOf(
            "label:AndreaOS" to "Lavori e clienti",
            "pkg:com.google.android.apps.docs" to "I tuoi file",
            "pkg:com.google.android.gm" to "Posta",
        )
        Mode.Set -> listOf(
            "pkg:com.android.camera" to "Riprese veloci",
            "pkg:dji.go.v5" to "Drone",
            "label:Race Tracker" to "Gara in corso",
        )
        Mode.Sera -> listOf(
            "pkg:com.apple.android.music" to "Riprendi l'ascolto",
            "pkg:com.instagram.android" to "Social",
            "pkg:com.shazam.android" to "Riconosci un brano",
        )
    }

    private fun suggestions(m: Mode): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        if (m != Mode.Set) {
            Usage.top(this, 3).forEach { pkg ->
                apps.value.firstOrNull { it.pkg == pkg }?.let { out += Suggestion(it, "Usata spesso a quest'ora") }
            }
        }
        defaults(m).forEach { (spec, why) ->
            if (out.size < 3) find(spec)?.let { a -> if (out.none { it.app.pkg == a.pkg }) out += Suggestion(a, why) }
        }
        return out.take(3)
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

    private fun letterOf(a: AppEntry): String {
        val c = a.search.firstOrNull() ?: return "#"
        return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
    }

    // ---------- Interfaccia ----------

    @Composable
    private fun Root() {
        var searchOpen by remember { mutableStateOf(false) }
        var drawerOpen by remember { mutableStateOf(false) }
        var railLetter by remember { mutableStateOf<String?>(null) }
        val tick = resetTick.intValue

        LaunchedEffect(tick) {
            searchOpen = false
            drawerOpen = false
            railLetter = null
            modeOverride.value = null
        }
        BackHandler(enabled = searchOpen || drawerOpen || railLetter != null) {
            when {
                railLetter != null -> railLetter = null
                searchOpen -> searchOpen = false
                else -> drawerOpen = false
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x99050506), Color(0xF2050506))))
        ) {
            // Luce calda d'ambiente
            Box(
                Modifier
                    .offset(x = (-140).dp, y = (-160).dp)
                    .size(460.dp)
                    .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.22f), Color.Transparent)))
            )

            val enter = remember { Animatable(1f) }
            LaunchedEffect(enterTick.intValue) {
                enter.snapTo(0f)
                enter.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val v = enter.value
                        alpha = 0.2f + 0.8f * v
                        scaleX = 0.94f + 0.06f * v
                        scaleY = 0.94f + 0.06f * v
                        translationY = (1f - v) * 60f
                    }
                    .systemBarsPadding()
                    .padding(start = 20.dp, end = 52.dp, top = 12.dp, bottom = 16.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -120f) drawerOpen = true
                                if (total > 120f) expandNotifications()
                            },
                        ) { _, dy -> total += dy }
                    }
            ) {
                ModeChips()
                Spacer(Modifier.height(26.dp))
                ClockBlock()
                Spacer(Modifier.weight(1f))
                NowBlock()
                Spacer(Modifier.height(14.dp))
                MusicRow()
                Spacer(Modifier.height(14.dp))
                BottomBar { searchOpen = true }
            }

            Rail(railLetter) { railLetter = it }

            AnimatedVisibility(
                visible = searchOpen,
                enter = fadeIn() + slideInVertically { it / 6 },
                exit = fadeOut(),
            ) { SearchScreen { searchOpen = false } }

            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { Drawer { drawerOpen = false } }
        }
    }

    @Composable
    private fun ModeChips() {
        val current = modeOverride.value ?: autoMode()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.values().forEach { m ->
                val on = m == current
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (on) Ink else Color.Transparent)
                        .border(1.dp, if (on) Ink else Color(0x24FFFFFF), RoundedCornerShape(18.dp))
                        .clickable { modeOverride.value = if (m == autoMode()) null else m }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m.label, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.5.sp, color = if (on) Night else Muted)
                }
            }
        }
    }

    @Composable
    private fun ClockBlock() {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = Date()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
        Text(
            SimpleDateFormat("HH:mm", Locale.ITALY).format(now),
            fontSize = 104.sp, fontWeight = FontWeight.Light, letterSpacing = (-5).sp, lineHeight = 100.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(now).uppercase(Locale.ITALY),
            fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp, color = Muted,
        )
        Spacer(Modifier.height(14.dp))
        nextAlarm.value?.let { InfoLine(it) { openAlarms() } }
        when {
            !calendarOk.value -> InfoLine("Mostra i tuoi eventi") { askCalendar.launch(Manifest.permission.READ_CALENDAR) }
            nextEvent.value != null -> InfoLine(nextEvent.value!!) { launchPkg("com.google.android.calendar", "Calendar") }
            else -> InfoLine("Nessun evento in arrivo") { launchPkg("com.google.android.calendar", "Calendar") }
        }
    }

    @Composable
    private fun InfoLine(text: String, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(vertical = 4.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun NowBlock() {
        val m = modeOverride.value ?: autoMode()
        val tick = usageTick.intValue
        val list = remember(m, apps.value, tick) { suggestions(m) }
        Text("ADESSO · ${m.title}", fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp, color = Dim)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { s -> SuggestionCard(s) }
        }
        if (pinned.value.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val byPkg = apps.value.associateBy { it.pkg }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pinned.value.mapNotNull { byPkg[it] }.take(6).forEach { a -> PinnedDot(a) }
            }
        }
    }

    @Composable
    private fun SuggestionCard(s: Suggestion) {
        var menu by remember { mutableStateOf(false) }
        val bounds = remember { mutableStateOf<Rect?>(null) }
        val shape = RoundedCornerShape(18.dp)
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .onGloballyPositioned { bounds.value = it.boundsInWindow() }
                    .clip(shape)
                    .background(Glass)
                    .border(1.dp, GlassLine, shape)
                    .combinedClickable(onClick = { markFrom(bounds.value); launch(s.app) }, onLongClick = { menu = true })
                    .padding(horizontal = 12.dp),
            ) {
                Image(s.app.icon, contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.app.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.why, fontFamily = Mono, fontSize = 11.sp, color = Dim, maxLines = 1)
                }
                Chevron()
            }
            AppMenu(s.app, menu) { menu = false }
        }
    }

    @Composable
    private fun PinnedDot(a: AppEntry) {
        var menu by remember { mutableStateOf(false) }
        val bounds = remember { mutableStateOf<Rect?>(null) }
        Box {
            Image(
                a.icon, contentDescription = a.label,
                modifier = Modifier
                    .size(44.dp)
                    .onGloballyPositioned { bounds.value = it.boundsInWindow() }
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { markFrom(bounds.value); launch(a) }, onLongClick = { menu = true }),
            )
            AppMenu(a, menu) { menu = false }
        }
    }

    @Composable
    private fun Chevron() {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val h = size.height
            drawPath(
                Path().apply { moveTo(w * 0.35f, h * 0.15f); lineTo(w * 0.75f, h * 0.5f); lineTo(w * 0.35f, h * 0.85f) },
                Dim, style = Stroke(width = w * 0.14f),
            )
        }
    }

    @Composable
    private fun MusicRow() {
        val np = playing.value
        val isPlaying = np?.playing == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wave(isPlaying)
            Spacer(Modifier.width(12.dp))
            val label = when {
                !mediaOk.value -> "Attiva il controllo musica"
                np != null && np.title.isNotBlank() -> if (np.artist.isNotBlank()) "${np.title} — ${np.artist}" else np.title
                else -> "Apple Music · tocca per riprendere"
            }
            Text(
                label, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        when {
                            !mediaOk.value -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            np != null -> launchPkg(np.pkg, "L'app musicale")
                            else -> launchPkg("com.apple.android.music", "Apple Music")
                        }
                    }
                    .padding(vertical = 8.dp),
            )
            MediaButton(Glyph.Prev, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            MediaButton(if (isPlaying) Glyph.Pause else Glyph.Play, true) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            MediaButton(Glyph.Next, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
    }

    @Composable
    private fun Wave(active: Boolean) {
        val t = rememberInfiniteTransition(label = "wave")
        val speeds = listOf(900, 1150, 800, 1300, 1000)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
            speeds.forEach { ms ->
                val v by t.animateFloat(
                    initialValue = 0.25f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse),
                    label = "bar",
                )
                val hgt = if (active) v else 0.25f
                Box(Modifier.width(3.dp).fillMaxHeight(hgt).clip(RoundedCornerShape(2.dp)).background(Accent))
            }
        }
    }

    private enum class Glyph { Prev, Play, Pause, Next }

    @Composable
    private fun MediaButton(g: Glyph, primary: Boolean, onClick: () -> Unit) {
        val fg = if (primary) Night else Ink
        Box(
            Modifier
                .size(if (primary) 40.dp else 36.dp)
                .clip(CircleShape)
                .background(if (primary) Ink else Color.Transparent)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) {
                val w = size.width
                val h = size.height
                when (g) {
                    Glyph.Play -> drawPath(Path().apply { moveTo(w * 0.2f, 0f); lineTo(w, h / 2); lineTo(w * 0.2f, h); close() }, fg)
                    Glyph.Pause -> {
                        drawRect(fg, Offset(w * 0.15f, 0f), androidx.compose.ui.geometry.Size(w * 0.25f, h))
                        drawRect(fg, Offset(w * 0.6f, 0f), androidx.compose.ui.geometry.Size(w * 0.25f, h))
                    }
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
    private fun BottomBar(onSearch: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, GlassLine, RoundedCornerShape(28.dp))
                    .clickable { onSearch() }
                    .padding(horizontal = 18.dp),
            ) {
                SearchGlyph()
                Spacer(Modifier.width(10.dp))
                Text("Cerca o scorri le lettere", fontSize = 15.sp, color = Dim)
            }
            Spacer(Modifier.width(12.dp))
            ShazamOrb()
        }
    }

    @Composable
    private fun SearchGlyph() {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            drawCircle(Muted, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(width = w * 0.11f))
            drawLine(Muted, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.95f, w * 0.95f), strokeWidth = w * 0.11f)
        }
    }

    @Composable
    private fun ShazamOrb() {
        val t = rememberInfiniteTransition(label = "orb")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
        val extra = with(LocalDensity.current) { 18.dp.toPx() }
        Box(
            Modifier
                .size(56.dp)
                .drawBehind {
                    drawCircle(Accent.copy(alpha = 0.45f * (1f - p)), radius = size.minDimension / 2 + extra * p)
                }
                .clip(CircleShape)
                .background(Accent)
                .clickable { launchPkg("com.shazam.android", "Shazam") },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val xs = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                val hs = listOf(0.1f, 0.35f, 0.6f, 0.35f, 0.1f)
                xs.forEachIndexed { i, x ->
                    val half = w * hs[i]
                    drawLine(Night, Offset(w * x, w / 2 - half), Offset(w * x, w / 2 + half), strokeWidth = w * 0.1f)
                }
            }
        }
    }

    /** La fila di lettere: scorri col pollice, scegli la lettera, poi spostati a sinistra sull'app e lascia. */
    @Composable
    private fun Rail(letter: String?, onLetter: (String?) -> Unit) {
        val haptic = LocalHapticFeedback.current
        val density = LocalDensity.current
        val all = apps.value
        val letters = remember(all) { all.map { letterOf(it) }.distinct().sorted() }
        val groups = remember(all) { all.groupBy { letterOf(it) } }
        var hover by remember { mutableIntStateOf(-1) }
        val rowPx = with(density) { 52.dp.toPx() }
        val headerPx = with(density) { 56.dp.toPx() }
        val leftLimit = with(density) { (-28).dp.toPx() }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(top = 150.dp, bottom = 150.dp)
        ) {
            val railH = constraints.maxHeight.toFloat()
            val n = letters.size.coerceAtLeast(1)
            val list = letter?.let { groups[it] }.orEmpty().take(8)
            val idx = letter?.let { letters.indexOf(it) } ?: 0
            val panelH = headerPx + rowPx * list.size
            val centerY = (idx + 0.5f) * railH / n
            val panelTop = (centerY - headerPx / 2).coerceIn(0f, (railH - panelH).coerceAtLeast(0f))

            if (letter != null) {
                // Tocca fuori per chiudere
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onLetter(null) }
                )
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, panelTop.roundToInt()) }
                        .padding(end = 50.dp)
                        .width(230.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF0161619))
                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp)
                ) {
                    Row(Modifier.height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(letter, fontSize = 30.sp, fontWeight = FontWeight.Light, color = Accent, modifier = Modifier.weight(1f))
                        Text("${groups[letter]?.size ?: 0}", fontFamily = Mono, fontSize = 11.sp, color = Dim)
                    }
                    list.forEachIndexed { i, a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (i == hover) Color(0x26E8A33D) else Color.Transparent)
                                .clickable { onLetter(null); launch(a) }
                                .padding(horizontal = 8.dp),
                        ) {
                            Image(a.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(40.dp)
                    .fillMaxHeight()
                    .pointerInput(letters, groups) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var current: String? = null
                            var target = -1
                            fun track(pos: Offset) {
                                if (pos.x >= leftLimit) {
                                    val i = ((pos.y / size.height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                                    val l = letters.getOrNull(i)
                                    if (l != null && l != current) {
                                        current = l
                                        onLetter(l)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    target = -1
                                } else {
                                    val cnt = (current?.let { groups[it]?.size } ?: 0).coerceAtMost(8)
                                    val ci = current?.let { letters.indexOf(it) } ?: 0
                                    val cy = (ci + 0.5f) * size.height / letters.size
                                    val ph = headerPx + rowPx * cnt
                                    val top = (cy - headerPx / 2).coerceIn(0f, (size.height - ph).coerceAtLeast(0f))
                                    val r = ((pos.y - top - headerPx) / rowPx).toInt()
                                    val nt = if (pos.y >= top + headerPx && r in 0 until cnt) r else -1
                                    if (nt != target && nt >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    target = nt
                                }
                                hover = target
                            }
                            track(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                track(ch.position)
                                ch.consume()
                            }
                            val l = current
                            if (target >= 0 && l != null) {
                                groups[l]?.getOrNull(target)?.let { a -> onLetter(null); launch(a) }
                            }
                            hover = -1
                        }
                    },
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                letters.forEach { l ->
                    val on = l == letter
                    Text(
                        l,
                        fontFamily = Mono,
                        fontSize = if (on) 17.sp else 11.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        color = if (on) Accent else Dim,
                    )
                }
            }
        }
    }

    @Composable
    private fun SearchField(value: String, hint: String, focus: FocusRequester?, onChange: (String) -> Unit, onGo: () -> Unit) {
        val shape = RoundedCornerShape(28.dp)
        var mod = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, GlassLine, shape)
            .padding(horizontal = 18.dp)
        if (focus != null) mod = mod.focusRequester(focus)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontFamily = Grotesk),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = mod,
            decorationBox = { inner ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    SearchGlyph()
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(hint, color = Dim, fontSize = 16.sp)
                        inner()
                    }
                }
            },
        )
    }

    @Composable
    private fun SearchScreen(onClose: () -> Unit) {
        var query by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val results = remember(query, apps.value, newPkgs.value) { search(query) }
        LaunchedEffect(Unit) {
            delay(80)
            try { focus.requestFocus() } catch (e: Exception) { }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2050506))
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SearchField(query, "Cerca app e comandi", focus, { query = it }) { results.firstOrNull()?.let { h -> onClose(); h.run() } }
                }
                Text(
                    "Chiudi", color = Accent, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (query.isBlank()) {
                    item { SectionLabel("COMANDI") }
                    items(commands) { c -> HitRow(Hit(c.label, null, "Comando", false, null) { onClose(); c.run() }) }
                } else {
                    items(results) { h -> HitRow(h.copy(run = { onClose(); h.run() })) }
                    if (results.isEmpty()) {
                        item { Text("Nessun risultato", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppMenu(a: AppEntry, open: Boolean, onDismiss: () -> Unit) {
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            val isPinned = a.pkg in pinned.value
            DropdownMenuItem(
                text = { Text(if (isPinned) "Rimuovi dalle fissate" else "Fissa nella home") },
                onClick = { togglePin(a.pkg); onDismiss() },
            )
            DropdownMenuItem(text = { Text("Info app") }, onClick = { appInfo(a.pkg); onDismiss() })
            DropdownMenuItem(text = { Text("Disinstalla") }, onClick = { uninstall(a.pkg); onDismiss() })
        }
    }

    @Composable
    private fun HitRow(h: Hit) {
        var menu by remember { mutableStateOf(false) }
        val bounds = remember { mutableStateOf<Rect?>(null) }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { bounds.value = it.boundsInWindow() }
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { markFrom(bounds.value); h.run() }, onLongClick = { if (h.app != null) menu = true })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (h.icon != null) {
                    Image(h.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F)),
                        contentAlignment = Alignment.Center,
                    ) { Text(h.label.take(1), color = Accent, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(14.dp))
                Text(h.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (h.isNew) {
                    Text(
                        "Nuova", color = Night, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(h.kind, fontFamily = Mono, color = Dim, fontSize = 11.sp)
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
                .background(Color(0xF5050506))
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
                Text("Tutte le app", fontSize = 24.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                Text(
                    "Chiudi", color = Accent, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(10.dp),
                )
            }
            SearchField(q, "Cerca tra ${all.size} app", null, { q = it }) { shown.firstOrNull()?.let { launch(it) } }
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
        Text(
            t, fontFamily = Mono, color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}
