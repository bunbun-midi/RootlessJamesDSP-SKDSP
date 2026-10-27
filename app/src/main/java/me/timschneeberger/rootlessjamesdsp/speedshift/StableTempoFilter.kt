package me.timschneeberger.rootlessjamesdsp.speedshift

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "Stable" target tempo mode (the default): decides which detected tempos are real tempo changes
 * and which are misreadings within a song, so the speed does not jump in the middle of a track.
 * Audio thread only; positions are capture positions in frames (as in [TempoMatcher]).
 *
 * Within a song the detector can lock onto another metrical level for a while, e.g. in half-time
 * sections, breakdowns or syncopated parts: half/double (handled by octave folding anyway) and
 * 3:4 / 4:3 of the real tempo, or nothing consistent at all. The rules:
 *  - One tempo per track. Confident readings within [SAME] of the tempo it was locked at
 *    (octave-folded) refine it; measuring against the locked tempo, not the latest refinement,
 *    keeps stray readings from walking it away step by step.
 *  - Readings at 3:4 or 4:3 of it (octave-folded) are misreadings, unless they persist for
 *    [RATIO_HOLD_SECONDS] (a real change to such a tempo without a detectable track change).
 *  - Readings with confidence below [MIN_CONFIDENCE] are ignored.
 *  - Any other tempo must be reported steadily for [HOLD_SECONDS] before it is accepted; it then
 *    applies from where it started (its first report minus the detection delay).
 *  - At a track change ([onBoundary]: new track info from the playing app, or a gap of silence),
 *    the next tempo is taken as is and applies from the boundary. During the first
 *    [SETTLE_SECONDS] of a track, corrections (also 3:4 / 4:3) are accepted after
 *    [SETTLE_HOLD_SECONDS] and replace the track's tempo from its start (e.g. a half-time intro).
 */
class StableTempoFilter(private val sampleRate: Int, private val delayFrames: Long) {

    /** Accepted tempo with the capture position it applies from */
    data class Accepted(val tempo: Float, val from: Long)

    private var locked = 0f            // tempo of the current track (0 = none yet)
    private var anchor = 0f            // tempo when it was locked (refinements stay near it)
    private var trackStart = Long.MIN_VALUE   // where the current track's tempo applies from
    private var settleStart = Long.MIN_VALUE  // start of the settling period (track or target mode start)
    private var awaitingFirst = true   // after a boundary / at start: take the next tempo as is
    private var lastReported = 0f

    private var candidate = 0f
    private var candidateSince = 0L    // capture position of its first report
    private var candidateIsRatio = false

    /**
     * Starts with [currentTempo] (0 if unknown) for what is playing now, at capture position [pos].
     * The first [SETTLE_SECONDS] count as settling (the current reading may be a misreading).
     */
    fun start(currentTempo: Float, pos: Long) {
        locked = currentTempo.coerceAtLeast(0f)
        anchor = locked
        trackStart = Long.MIN_VALUE
        settleStart = pos
        awaitingFirst = locked <= 0f
        lastReported = locked
        candidate = 0f
    }

    /** A new track starts at capture position [pos] (the detector is reset there too) */
    fun onBoundary(pos: Long) {
        trackStart = pos
        settleStart = pos
        awaitingFirst = true
        lastReported = 0f
        candidate = 0f
    }

    /** The detector's estimate after the block ending at [pos]; returns a tempo to enter, if any */
    fun onDetected(tempo: Float, confidence: Float, pos: Long): Accepted? {
        if (tempo <= 0f || tempo == lastReported)
            return null
        lastReported = tempo

        if (awaitingFirst) {
            // First estimate of a new track (the detector was reset at the boundary): it applies
            // from the track's start; later corrections follow the settling rules
            awaitingFirst = false
            locked = tempo
            anchor = tempo
            candidate = 0f
            return Accepted(tempo, trackStart)
        }

        if (confidence < MIN_CONFIDENCE)
            return null

        val q = foldedRatio(tempo, anchor)
        if (abs(q - 1f) < SAME) {
            // Refinement of the track's tempo
            locked = tempo
            candidate = 0f
            return Accepted(tempo, pos - delayFrames)
        }

        val isRatio = near(q, 4f / 3f) || near(q, 3f / 4f)
        val settling = settleStart != Long.MIN_VALUE && pos - settleStart < SETTLE_SECONDS * sampleRate

        if (candidate > 0f && abs(foldedRatio(tempo, candidate) - 1f) < SAME) {
            candidate = tempo
            val held = (pos - candidateSince).toDouble() / sampleRate
            val needed = when {
                settling -> SETTLE_HOLD_SECONDS
                candidateIsRatio -> RATIO_HOLD_SECONDS
                else -> HOLD_SECONDS
            }
            if (held >= needed) {
                locked = tempo
                anchor = tempo
                candidate = 0f
                // A correction early in a track applies to the whole track
                val from = if (settling) trackStart else candidateSince - delayFrames
                return Accepted(tempo, from)
            }
        } else {
            candidate = tempo
            candidateSince = pos
            candidateIsRatio = isRatio
        }
        return null
    }

    /** Tempo of the current track (0 if none yet) */
    val trackTempo: Float get() = locked

    companion object {
        const val SAME = 0.015f
        const val RATIO_TOLERANCE = 0.025f
        const val MIN_CONFIDENCE = 0.25f
        const val HOLD_SECONDS = 16.0
        const val RATIO_HOLD_SECONDS = 60.0
        const val SETTLE_SECONDS = 30.0
        const val SETTLE_HOLD_SECONDS = 6.0
        private val SQRT2 = sqrt(2.0).toFloat()

        /** tempo / reference, doubled/halved into [1/sqrt 2, sqrt 2) */
        fun foldedRatio(tempo: Float, reference: Float): Float {
            if (tempo <= 0f || reference <= 0f)
                return 1f
            var q = tempo / reference
            while (q < 1f / SQRT2) q *= 2f
            while (q >= SQRT2) q /= 2f
            return q
        }

        private fun near(q: Float, ratio: Float) = abs(q / ratio - 1f) < RATIO_TOLERANCE
    }
}
