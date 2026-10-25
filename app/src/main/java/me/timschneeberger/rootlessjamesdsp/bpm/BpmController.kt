package me.timschneeberger.rootlessjamesdsp.bpm

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/**
 * Shared state of the BPM display. The audio thread of RootlessAudioProcessorService runs the
 * detector while [isEnabled] and publishes results; the floating display reads them.
 */
object BpmController {
    /** Set by the service while the BPM display is shown */
    @Volatile
    var isEnabled = false

    @Volatile
    var bpm = 0f
        private set

    @Volatile
    var confidence = 0f
        private set

    @Volatile
    private var resetRequested = false

    fun publish(bpm: Float, confidence: Float) {
        this.bpm = bpm
        this.confidence = confidence
    }

    /** Forget the current estimate (e.g. after switching songs) */
    fun requestReset() {
        resetRequested = true
        bpm = 0f
        confidence = 0f
    }

    /** Audio thread: returns true once per reset request */
    fun consumeResetRequest(): Boolean {
        if (!resetRequested)
            return false
        resetRequested = false
        return true
    }

    /**
     * Folds a tempo into the user's preferred one-octave range (doubling/halving), which resolves
     * half/double-tempo ambiguity for the user's kind of music. "auto" keeps the estimate.
     */
    fun fold(bpm: Float, range: String?): Float {
        val low = when (range) {
            "70" -> 70f
            "80" -> 80f
            "90" -> 90f
            else -> return bpm
        }
        if (bpm <= 0f)
            return bpm
        var value = bpm
        while (value < low) value *= 2f
        while (value >= 2f * low) value /= 2f
        return value
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_BPM, Context.MODE_PRIVATE)

    fun range(context: Context): String? =
        prefs(context).getString(context.getString(R.string.key_bpm_range), "auto")
}
