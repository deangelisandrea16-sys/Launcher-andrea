package it.andreadeangelis.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Meteo da Open-Meteo (gratuito, senza chiavi). Posizione approssimativa, mai salvata altrove. */
object Weather {
    fun granted(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun describe(code: Int): String = when (code) {
        0 -> "Sereno"
        1, 2 -> "Poco nuvoloso"
        3 -> "Nuvoloso"
        45, 48 -> "Nebbia"
        in 51..57 -> "Pioggerella"
        in 61..67, in 80..82 -> "Pioggia"
        in 71..77, 85, 86 -> "Neve"
        in 95..99 -> "Temporale"
        else -> ""
    }

    /** Da chiamare in background. */
    fun now(c: Context): String? {
        if (!granted(c)) return null
        return try {
            val lm = c.getSystemService(LocationManager::class.java) ?: return null
            val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time } ?: return null
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                loc.latitude, loc.longitude,
            )
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
            if (conn.responseCode != 200) return null
            val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            val t = Math.round(cur.getDouble("temperature_2m"))
            val d = describe(cur.optInt("weather_code", -1))
            if (d.isEmpty()) "$t°" else "$t° · $d"
        } catch (e: Exception) {
            null
        }
    }
}
