package me.timschneeberger.rootlessjamesdsp.recorder

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared state of the "Recorder" effect block. The floating button calls [toggle]; the audio
 * service registers itself as [backend] and reports back via [onStarted]/[onStopped].
 * All functions are called on the main thread.
 */
object RecorderController {
    fun interface Listener {
        fun onRecorderStateChanged()
    }

    interface Backend {
        fun startRecording()
        fun stopRecording()
    }

    var backend: Backend? = null

    var isRecording = false
        private set

    private var startedAt = 0L

    /** Display name of the last file saved in this session (null after a failure) */
    var lastSavedName: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun toggle() {
        if (isRecording) backend?.stopRecording() else backend?.startRecording()
    }

    fun onStarted() {
        isRecording = true
        startedAt = SystemClock.elapsedRealtime()
        notifyListeners()
    }

    fun onStopped(savedName: String?) {
        isRecording = false
        lastSavedName = savedName
        notifyListeners()
    }

    val elapsedSeconds: Long
        get() = if (isRecording) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0

    fun formatElapsed(seconds: Long): String =
        if (seconds < 3600) String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
        else String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)

    /** Recorder A settings (and the floating button's position) */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_RECORDER, Context.MODE_PRIVATE)

    /** Recorder B settings (same keys as A) */
    fun prefsB(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_RECORDER_B, Context.MODE_PRIVATE)

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper())
            listeners.forEach { it.onRecorderStateChanged() }
        else
            mainHandler.post { listeners.forEach { it.onRecorderStateChanged() } }
    }
}
