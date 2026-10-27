package me.timschneeberger.rootlessjamesdsp.speedshift

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.service.NotificationListenerService
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices when the playing app moves to another track (its media session's title/artist/album
 * change while it is playing), for the stable target tempo mode. Only works if the app
 * has notification access (Settings > Troubleshooting); without it, track changes are recognized
 * from gaps of silence only. Main thread; [consume] may be called from any thread.
 */
class TrackChangeWatcher(private val context: Context) : MediaSessionManager.OnActiveSessionsChangedListener {
    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService<MediaSessionManager>()
    private val component = ComponentName(context, NotificationListenerService::class.java)
    private val changed = AtomicBoolean(false)
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false

    fun start() {
        if (started || sessionManager == null)
            return
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            Timber.d("TrackChangeWatcher: no notification access, using silence gaps only")
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(this, component, handler)
            onActiveSessionsChanged(sessionManager.getActiveSessions(component))
            started = true
        } catch (ex: SecurityException) {
            Timber.w("TrackChangeWatcher: $ex")
        }
    }

    fun stop() {
        if (!started)
            return
        started = false
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(this)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
        unregisterAll()
    }

    /** True once after the playing app changed tracks */
    fun consume(): Boolean = changed.getAndSet(false)

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        unregisterAll()
        controllers?.forEach { controller ->
            val callback = object : MediaController.Callback() {
                private var lastKey: String? = trackKey(controller.metadata)

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    val key = trackKey(metadata) ?: return
                    val previous = lastKey
                    lastKey = key
                    if (previous != null && key != previous &&
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        Timber.d("TrackChangeWatcher: new track in ${controller.packageName}")
                        changed.set(true)
                    }
                }
            }
            try {
                controller.registerCallback(callback, handler)
                callbacks[controller] = callback
            } catch (ex: Exception) {
                Timber.w(ex)
            }
        }
    }

    private fun unregisterAll() {
        callbacks.forEach { (controller, callback) ->
            try {
                controller.unregisterCallback(callback)
            } catch (ex: Exception) {
                Timber.w(ex)
            }
        }
        callbacks.clear()
    }

    private fun trackKey(metadata: MediaMetadata?): String? {
        metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        return listOf(
            title,
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            // (not the duration: some apps fill it in later during the same track)
        ).joinToString("\u0000")
    }
}
