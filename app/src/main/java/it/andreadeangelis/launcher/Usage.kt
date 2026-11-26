package it.andreadeangelis.launcher

import android.content.Context
import java.util.Calendar

/** Statistiche d'uso solo locali: quali app apri in ogni fascia di 3 ore. */
object Usage {
    private fun prefs(c: Context) = c.getSharedPreferences("usage", Context.MODE_PRIVATE)

    private fun bucket(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) / 3

    private fun read(c: Context, key: String): Map<String, Int> =
        (prefs(c).getString(key, "") ?: "").split("|").mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: 0)
        }.toMap()

    fun record(c: Context, pkg: String) {
        val key = "b${bucket()}"
        val map = read(c, key).toMutableMap()
        map[pkg] = (map[pkg] ?: 0) + 1
        prefs(c).edit().putString(key, map.entries.joinToString("|") { "${it.key}=${it.value}" }).apply()
    }

    /** Le app più usate in questa fascia oraria (vuoto finché i dati sono pochi). */
    fun top(c: Context, n: Int): List<String> {
        val m = read(c, "b${bucket()}")
        if (m.values.sum() < 6) return emptyList()
        return m.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }
}
