package it.andreadeangelis.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

data class NextEvent(val title: String, val begin: Long, val location: String)

object CalendarRepo {
    fun granted(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun next(c: Context): NextEvent? {
        if (!granted(c)) return null
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 15 * 60 * 1000L)
        ContentUris.appendId(builder, now + 36 * 3600 * 1000L)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        return try {
            c.contentResolver.query(builder.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                var found: NextEvent? = null
                while (found == null && cur.moveToNext()) {
                    val allDay = cur.getInt(2) == 1
                    if (!allDay) found = NextEvent(cur.getString(0) ?: "Evento", cur.getLong(1), cur.getString(3) ?: "")
                }
                found
            }
        } catch (e: Exception) {
            null
        }
    }
}
