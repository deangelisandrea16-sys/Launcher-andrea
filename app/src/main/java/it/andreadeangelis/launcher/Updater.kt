package it.andreadeangelis.launcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val tag: String, val code: Int, val apkUrl: String)

/**
 * Controlla le Release pubbliche (solo APK, il codice resta privato).
 * Nessun token nell'app: la repo delle release è pubblica e in sola lettura.
 * Android installa l'aggiornamento solo se è firmato con la stessa chiave.
 */
object Updater {
    // Prova prima la repo del launcher (se pubblica), poi quella delle sole release.
    private val SOURCES = listOf(
        "https://api.github.com/repos/deangelisandrea16-sys/launcher-andrea/releases/latest",
        "https://api.github.com/repos/deangelisandrea16-sys/launcher-andrea-releases/releases/latest",
    )
    private const val CHANNEL = "updates"
    private const val EVERY_MS = 3 * 3600 * 1000L

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun currentCode(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()).toInt()
    } catch (e: Exception) {
        0
    }

    /** Da chiamare su un thread in background. Restituisce l'aggiornamento se più recente. */
    fun check(c: Context, force: Boolean = false): UpdateInfo? {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last", 0) < EVERY_MS) return cached(c)
        p.edit().putLong("last", now).apply()
        return try {
            var body: String? = null
            for (src in SOURCES) {
                val conn = (URL(src).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode == 200) {
                    body = conn.inputStream.bufferedReader().use { it.readText() }
                    break
                }
            }
            if (body == null) return cached(c)
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (url == null || !url.startsWith("https://github.com/")) return null
            p.edit().putString("tag", tag).putInt("code", code).putString("url", url).apply()
            cached(c)
        } catch (e: Exception) {
            cached(c)
        }
    }

    private fun cached(c: Context): UpdateInfo? {
        val p = prefs(c)
        val code = p.getInt("code", 0)
        val url = p.getString("url", null) ?: return null
        if (code <= currentCode(c)) return null
        return UpdateInfo(p.getString("tag", "") ?: "", code, url)
    }

    /** Notifica una sola volta per ogni versione nuova. */
    fun notifyOnce(c: Context, u: UpdateInfo) {
        val p = prefs(c)
        if (p.getInt("notified", 0) >= u.code) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aggiornamenti launcher", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Launcher Andrea: aggiornamento ${u.tag}")
            .setContentText("Tocca per installarlo")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
        p.edit().putInt("notified", u.code).apply()
    }

    /** Scarica l'APK (in background) e restituisce il file pronto. */
    fun download(c: Context, u: UpdateInfo, onProgress: (Float) -> Unit): File? {
        return try {
            val dir = File(c.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "launcher-${u.code}.apk")
            var conn = URL(u.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            // GitHub reindirizza al CDN su un altro host: seguiamo manualmente se serve.
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                if (!loc.startsWith("https://")) return null
                conn = URL(loc).openConnection() as HttpURLConnection
                hops++
            }
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            var read = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }
}
