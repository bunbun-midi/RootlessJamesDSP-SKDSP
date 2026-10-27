package me.timschneeberger.rootlessjamesdsp.interop

/**
 * JNI bindings for chain::SpeedShifter (libjamesdsp-wrapper/BungeeProcessors.cpp).
 *
 * Slows interleaved stereo audio down while keeping its pitch (Bungee time stretcher).
 * Audio that was captured but not played yet is kept in a backlog (max. 5 minutes).
 * [nativePrime]/[nativeProcess] must only be called from one thread; the other functions are thread-safe.
 */
object SpeedShifter {
    init {
        System.loadLibrary("jamesdsp-wrapper")
    }

    /** @return handle, or 0 on failure */
    @JvmStatic external fun nativeCreate(sampleRate: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetSpeed(handle: Long, speed: Float)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeBacklogFrames(handle: Long): Long
    /**
     * Call once when slow-down is switched on, before the first [nativeProcess]: [history] holds
     * [historyFrames] frames captured just before (oldest first), [current] the block about to be
     * processed. Pre-fills the stretcher so its output starts without a gap, aligned for a
     * dry -> shifted splice of [spliceFrames] frames. Returns the added alignment delay in frames.
     */
    @JvmStatic external fun nativePrime(
        handle: Long, history: FloatArray, historyFrames: Int,
        current: FloatArray, currentFrames: Int, spliceFrames: Int,
    ): Int
    /** Consumes [frames] captured frames from [input] and renders [frames] frames to [output] */
    @JvmStatic external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray, frames: Int)
}
