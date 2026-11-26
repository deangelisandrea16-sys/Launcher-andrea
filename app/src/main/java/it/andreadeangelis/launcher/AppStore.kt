package it.andreadeangelis.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Normalizer

data class AppEntry(
    val key: String,
    val label: String,
    val pkg: String,
    val activity: String,
    val icon: ImageBitmap,
    val search: String,
)

private val MARKS = Regex("\\p{M}+")

fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()

object AppStore {
    // Solo preferenze di layout: nessun dato sensibile.
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    // App fissate di default, risolte al primo avvio (per pacchetto o per nome).
    private val DEFAULT_PINNED = listOf(
        "pkg:com.anthropic.claude",
        "label:AndreaOS",
        "pkg:notion.id",
        "pkg:com.google.android.apps.docs",
        "pkg:com.google.android.calendar",
        "pkg:com.apple.android.music",
        "pkg:com.shazam.android",
        "pkg:com.instagram.android",
    )

    fun load(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val iconPx = (48 * c.resources.displayMetrics.density).toInt()
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != c.packageName }
            .mapNotNull { ri ->
                try {
                    val label = ri.loadLabel(pm).toString()
                    val pkg = ri.activityInfo.packageName
                    val bmp = ri.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap()
                    AppEntry("$pkg/${ri.activityInfo.name}", label, pkg, ri.activityInfo.name, bmp, normalize(label))
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.key }
            .sortedBy { it.search }
    }

    /** Pacchetti installati dopo il primo avvio e non ancora aperti. */
    fun newPackages(c: Context, apps: List<AppEntry>): Set<String> {
        val p = prefs(c)
        val all = apps.map { it.pkg }.toSet()
        if (!p.getBoolean("seenInit", false)) {
            p.edit().putStringSet("seen", HashSet(all)).putBoolean("seenInit", true).apply()
            return emptySet()
        }
        val seen = p.getStringSet("seen", emptySet()) ?: emptySet()
        return all - seen
    }

    fun markSeen(c: Context, pkg: String) {
        val p = prefs(c)
        val seen = HashSet(p.getStringSet("seen", emptySet()) ?: emptySet())
        seen.add(pkg)
        p.edit().putStringSet("seen", seen).apply()
    }

    fun pinned(c: Context, apps: List<AppEntry>): List<String> {
        val p = prefs(c)
        val saved = p.getString("essentials", null)
        if (saved != null) return saved.split("|").filter { it.isNotBlank() }
        if (apps.isEmpty()) return emptyList()
        val resolved = DEFAULT_PINNED.mapNotNull { m ->
            if (m.startsWith("pkg:")) {
                apps.firstOrNull { it.pkg == m.removePrefix("pkg:") }?.pkg
            } else {
                apps.firstOrNull { it.label.equals(m.removePrefix("label:"), ignoreCase = true) }?.pkg
            }
        }.distinct()
        setPinned(c, resolved)
        return resolved
    }

    fun setPinned(c: Context, list: List<String>) {
        prefs(c).edit().putString("essentials", list.joinToString("|")).apply()
    }
}
