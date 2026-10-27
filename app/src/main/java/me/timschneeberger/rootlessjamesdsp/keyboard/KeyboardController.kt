package me.timschneeberger.rootlessjamesdsp.keyboard

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared state of the floating piano keyboard. Keys call [play]; the audio service is the
 * [backend] that glides the chosen pitch shifter to the interval. Main thread only.
 */
object KeyboardController {
    const val TARGET_STANDARD = "std"
    const val TARGET_HQ = "hq"
    const val DEFAULT_PORTAMENTO_MS = 150f
    const val MAX_PORTAMENTO_MS = 2000f

    fun interface Backend {
        fun playInterval(semitones: Int)
    }

    fun interface Listener {
        fun onIntervalChanged()
    }

    var backend: Backend? = null

    /** Last interval played (semitones), null if none yet */
    var currentInterval: Int? = null
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun play(semitones: Int) {
        currentInterval = semitones
        backend?.playInterval(semitones)
        listeners.forEach { it.onIntervalChanged() }
    }

    /** Shows the pitch shifter's current setting on the keys (e.g. when the keyboard opens) */
    fun setCurrentInterval(semitones: Int?) {
        currentInterval = semitones
        listeners.forEach { it.onIntervalChanged() }
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_KEYBOARD, Context.MODE_PRIVATE)

    fun isHqTarget(context: Context) =
        prefs(context).getString(context.getString(R.string.key_keyboard_target), TARGET_STANDARD) == TARGET_HQ

    fun portamentoMs(context: Context) =
        prefs(context).getFloat(context.getString(R.string.key_keyboard_portamento), DEFAULT_PORTAMENTO_MS)
            .coerceIn(0f, MAX_PORTAMENTO_MS)

    fun setPortamentoMs(context: Context, ms: Float) {
        prefs(context).edit().putFloat(context.getString(R.string.key_keyboard_portamento), ms).apply()
    }

    /** The floating keyboard is collapsed to a small square (kept across service restarts) */
    fun isMinimized(context: Context) = prefs(context).getBoolean(KEY_MINIMIZED, false)

    fun setMinimized(context: Context, minimized: Boolean) {
        if (isMinimized(context) != minimized)
            prefs(context).edit().putBoolean(KEY_MINIMIZED, minimized).apply()
    }

    private const val KEY_MINIMIZED = "keyboard_panel_minimized"
}
