package it.andreadeangelis.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Serve solo a leggere cosa sta suonando (permesso "Accesso alle notifiche"). */
class NotifListener : NotificationListenerService()

data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val pkg: String)

object Media {
    fun hasAccess(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun current(c: Context): NowPlaying? {
        if (!hasAccess(c)) return null
        return try {
            val msm = c.getSystemService(MediaSessionManager::class.java) ?: return null
            val sessions = msm.getActiveSessions(ComponentName(c, NotifListener::class.java))
            val ctrl = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return null
            val md = ctrl.metadata
            NowPlaying(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING,
                pkg = ctrl.packageName,
            )
        } catch (e: Exception) {
            null
        }
    }
}
