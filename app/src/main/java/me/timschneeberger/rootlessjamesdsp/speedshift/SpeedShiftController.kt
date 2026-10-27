package me.timschneeberger.rootlessjamesdsp.speedshift

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Shared state of the speed shifter ("slow down what is playing right now").
 *
 * - [isActive] is toggled by tapping the floating button; it is intentionally not persisted
 *   (the service always starts with real-time playback).
 * - [speed] is persisted in the "speedshift" preferences, which are not part of presets.
 * - [targetBpm] (target tempo mode, set from the BPM display) makes the audio thread choose the
 *   speed itself from the detected tempo (see TempoMatcher); not persisted either.
 * - The audio thread of RootlessAudioProcessorService reads [isActive]/[speed]/[targetBpm] once
 *   per block and reports [backlogSeconds], [appliedSpeed], [playingBpm] and [targetLimited].
 */
object SpeedShiftController {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 1.0f
    const val DEFAULT_SPEED = 0.75f
    /** Speed values are kept on a 0.001 grid (the slider's resolution) */
    const val SPEED_RESOLUTION = 0.001f

    /** Swipe step = coarse step (0.00..0.25, by 0.01) + fine step (0.000..0.010, by 0.001) */
    const val MIN_STEP = 0f
    const val MAX_STEP = 0.25f
    const val STEP_RESOLUTION = 0.01f
    const val DEFAULT_STEP = 0.05f
    const val MAX_FINE_STEP = 0.01f
    const val FINE_STEP_RESOLUTION = 0.001f
    const val DEFAULT_FINE_STEP = 0f

    /** Target tempo range accepted from the BPM display */
    const val MIN_TARGET_BPM = 20f
    const val MAX_TARGET_BPM = 400f

    fun interface Listener {
        /** Always called on the main thread */
        fun onSpeedShiftStateChanged()
    }

    @Volatile
    var isActive = false
        private set

    @Volatile
    var speed = DEFAULT_SPEED
        private set

    /** Speed change per swipe step on the floating button (user setting, at least 0.001) */
    @Volatile
    var speedStep = DEFAULT_STEP
        private set

    /** Target tempo in BPM, 0 = off (manual [speed]) */
    @Volatile
    var targetBpm = 0f
        private set

    /** Speed the shifter is actually using (audio thread; differs from [speed] in target mode) */
    @Volatile
    var appliedSpeed = 1f

    /** Target mode: source tempo of the audio being played now, 0 if not known yet (audio thread) */
    @Volatile
    var playingBpm = 0f

    /** Target mode: the target is faster than the playing audio, so it plays at 1.00x (audio thread) */
    @Volatile
    var targetLimited = false

    /** Audio that has been captured but not played yet, in seconds (written by the audio thread) */
    @Volatile
    var backlogSeconds = 0f

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun setActive(active: Boolean) {
        if (isActive == active)
            return
        isActive = active
        if (!active) {
            backlogSeconds = 0f
            // Switching slow-down off also ends target tempo mode
            targetBpm = 0f
        }
        notifyListeners()
    }

    /**
     * Target tempo mode: plays the audio at [bpm] (speed = target / detected tempo, followed across
     * track changes). Switches the speed shifter on. 0 ends target mode (manual speed again).
     */
    fun setTargetBpm(context: Context, bpm: Float) {
        if (bpm <= 0f) {
            if (targetBpm == 0f)
                return
            targetBpm = 0f
            notifyListeners()
            return
        }
        val value = bpm.coerceIn(MIN_TARGET_BPM, MAX_TARGET_BPM)
        targetBpm = value
        prefs(context).edit().putFloat(KEY_LAST_TARGET, value).apply()
        if (!isActive)
            setActive(true)
        else
            notifyListeners()
    }

    /** Last target entered (for the input field), 0 if none */
    fun lastTargetBpm(context: Context) = prefs(context).getFloat(KEY_LAST_TARGET, 0f)

    fun toggle() = setActive(!isActive)

    /** Sets the speed (clamped, rounded to [SPEED_RESOLUTION]); returns true if it changed */
    fun setSpeed(context: Context, value: Float, persist: Boolean = true): Boolean {
        val quantized = quantize(value)
        if (quantized == speed)
            return false
        speed = quantized
        if (persist) {
            prefs(context).edit()
                .putFloat(context.getString(R.string.key_speed_shift_speed), quantized)
                .apply()
        }
        notifyListeners()
        return true
    }

    fun loadFromPreferences(context: Context) {
        val prefs = prefs(context)
        val coarse = prefs.getFloat(context.getString(R.string.key_speed_shift_step), DEFAULT_STEP).coerceIn(MIN_STEP, MAX_STEP)
        val fine = prefs.getFloat(context.getString(R.string.key_speed_shift_step_fine), DEFAULT_FINE_STEP)
            .coerceIn(0f, MAX_FINE_STEP)
        // Both at 0 would make swiping do nothing: use the smallest step then
        speedStep = roundToResolution(coarse + fine).coerceAtLeast(SPEED_RESOLUTION)
        val stored = prefs.getFloat(context.getString(R.string.key_speed_shift_speed), DEFAULT_SPEED)
        setSpeed(context, stored, persist = false)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_SPEED_SHIFT, Context.MODE_PRIVATE)

    fun quantize(value: Float): Float = roundToResolution(value.coerceIn(MIN_SPEED, MAX_SPEED))

    // Round to 3 decimals so that the stored value is exactly e.g. 0.355f
    private fun roundToResolution(value: Float): Float = (value / SPEED_RESOLUTION).roundToInt() / 1000f

    /** "0.75×", or "0.755×" when the third decimal is used */
    fun formatSpeed(value: Float): String {
        val thousandths = (value * 1000f).roundToInt()
        return if (thousandths % 10 == 0)
            String.format(Locale.getDefault(), "%.2f×", value)
        else
            String.format(Locale.getDefault(), "%.3f×", value)
    }

    fun formatDuration(seconds: Float): String {
        val total = seconds.roundToInt()
        return if (total < 60) "$total s"
        else String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    private const val KEY_LAST_TARGET = "speed_shift_last_target_bpm"

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper())
            listeners.forEach { it.onSpeedShiftStateChanged() }
        else
            mainHandler.post { listeners.forEach { it.onSpeedShiftStateChanged() } }
    }
}
