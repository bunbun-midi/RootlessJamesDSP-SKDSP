package me.timschneeberger.rootlessjamesdsp.speedshift

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Target tempo mode: chooses the speed shifter's speed so that the audio being played has the
 * target tempo, and follows tempo changes (new tracks) without jumps. Audio thread only.
 *
 * Positions are capture positions in frames (a counter of all captured audio). The tempo
 * detector runs on the captured audio, which is ahead of what is being played by the speed
 * shifter's backlog. Each detected tempo is therefore entered into a small tempo map at the
 * capture position where it most likely started ([DETECTION_DELAY_SECONDS] before it was
 * reported: the detector confirms a new tempo about 6-7 s into a new track), and the speed follows
 * the tempo of the position being *played*. So after a track change:
 *  - with a backlog of more than ~6.5 s, the new speed starts right where the new track starts;
 *  - with less backlog, the old speed continues until the new tempo has been detected.
 * Every speed change is a glide (linear in log speed over [GLIDE_SECONDS]); the speed shifter
 * itself handles gradual speed changes without artifacts.
 *
 * Tempos are compared modulo octaves (a track detected at 64 BPM matches a target of 128 like
 * 128 BPM would). The speed shifter can only slow down, so a target above the playing tempo
 * plays at 1.00x and [isLimited] is set.
 */
class TempoMatcher(private val sampleRate: Int) {
    private val keys = LongArray(CAPACITY)
    private val tempos = FloatArray(CAPACITY)
    private var count = 0
    private var lastReported = 0f

    /* glide state (log speed) */
    private var current = 0.0
    private var from = 0.0
    private var to = 0.0
    private var glidePos = 0L
    private var glideLength = 0L

    /** Speed returned by the last [next] */
    val speed: Float get() = exp(current).toFloat()

    /** Source tempo of the audio being played (0 if not known yet) */
    var playingTempo = 0f
        private set

    /** The target is faster than the playing audio (speed limited to 1.0) */
    var isLimited = false
        private set

    /**
     * Starts target mode at the current speed [speed]. [currentTempo] is the tempo detected now
     * (0 if unknown); it applies to what is playing right now.
     */
    fun start(speed: Float, currentTempo: Float) {
        count = 0
        lastReported = 0f
        current = ln(speed.coerceIn(MIN_SPEED, MAX_SPEED).toDouble())
        from = current
        to = current
        glidePos = 0
        glideLength = 0
        playingTempo = 0f
        isLimited = false
        if (currentTempo > 0f) {
            keys[0] = Long.MIN_VALUE
            tempos[0] = currentTempo
            count = 1
            lastReported = currentTempo
        }
    }

    /** The detector's current estimate after the block ending at capture position [capturePos] */
    fun onDetected(tempo: Float, capturePos: Long) {
        // 0 = silence / not known: keep the tempo we have (e.g. the gap between two tracks)
        if (tempo <= 0f || tempo == lastReported)
            return
        lastReported = tempo
        val key = capturePos - (DETECTION_DELAY_SECONDS * sampleRate).toLong()
        // A newer estimate replaces everything it would start at or before
        while (count > 0 && keys[count - 1] >= key)
            count--
        if (count == CAPACITY) {
            // Very long backlog with many updates: merge the two oldest pending entries
            System.arraycopy(keys, 2, keys, 1, count - 2)
            System.arraycopy(tempos, 2, tempos, 1, count - 2)
            count--
        }
        keys[count] = key
        tempos[count] = tempo
        count++
    }

    /**
     * Speed for the next [frames] output frames, the first of which plays capture position
     * [playPos], for target tempo [target].
     */
    fun next(playPos: Long, target: Float, frames: Int): Float {
        // Tempo of the audio being played: last entry that starts at or before playPos
        var idx = -1
        for (i in 0 until count) {
            if (keys[i] <= playPos) idx = i else break
        }
        if (idx > 0) {
            // entries before it are no longer needed
            System.arraycopy(keys, idx, keys, 0, count - idx)
            System.arraycopy(tempos, idx, tempos, 0, count - idx)
            count -= idx
            idx = 0
        }

        if (idx == 0) {
            playingTempo = tempos[0]
            val source = foldToTarget(playingTempo, target)
            val desired = target / source
            isLimited = desired > MAX_SPEED + 1e-6f
            val logDesired = ln(desired.coerceIn(MIN_SPEED, MAX_SPEED).toDouble())
            if (abs(logDesired - to) > 1e-7) {
                from = current
                to = logDesired
                glidePos = 0
                glideLength = (GLIDE_SECONDS * sampleRate).toLong().coerceAtLeast(1)
            }
        }
        // else: nothing known yet, keep the current speed

        advance(frames)
        return speed
    }

    /**
     * Manual speed (target mode off): glides from the current speed if a glide is needed (e.g.
     * right after target mode ended), otherwise follows [manual] immediately.
     */
    fun nextManual(manual: Float, frames: Int, glide: Boolean): Float {
        val logManual = ln(manual.coerceIn(MIN_SPEED, MAX_SPEED).toDouble())
        if (!glide) {
            current = logManual
            from = logManual
            to = logManual
            glidePos = 0
            glideLength = 0
            return speed
        }
        if (abs(logManual - to) > 1e-7) {
            from = current
            to = logManual
            glidePos = 0
            glideLength = (GLIDE_SECONDS * sampleRate).toLong().coerceAtLeast(1)
        }
        advance(frames)
        return speed
    }

    /** Moves the glide on by one block; the block's speed is the glide's value at its middle */
    private fun advance(frames: Int) {
        if (glidePos < glideLength) {
            val mid = (glidePos + frames / 2).coerceAtMost(glideLength)
            current = from + (to - from) * mid.toDouble() / glideLength
            glidePos += frames
        } else {
            current = to
        }
    }

    /** True while a glide is still running */
    val isGliding: Boolean get() = glidePos < glideLength

    companion object {
        const val DETECTION_DELAY_SECONDS = 6.5
        const val GLIDE_SECONDS = 0.75
        // Same limits as SpeedShiftController (kept here so this class has no Android dependencies)
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 1.0f
        private const val CAPACITY = 512
        private val SQRT2 = sqrt(2.0).toFloat()

        /** [tempo] doubled/halved into [target / sqrt 2, target * sqrt 2) */
        fun foldToTarget(tempo: Float, target: Float): Float {
            if (tempo <= 0f || target <= 0f)
                return tempo
            var t = tempo
            while (t < target / SQRT2) t *= 2f
            while (t >= target * SQRT2) t /= 2f
            return t
        }
    }
}
