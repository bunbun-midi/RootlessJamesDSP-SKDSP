package me.timschneeberger.rootlessjamesdsp.interop

/**
 * JNI bindings for chain::SpeedShifter (libjamesdsp-wrapper/BungeeProcessors.cpp).
 *
 * Slows interleaved stereo audio down while keeping its pitch (Bungee time stretcher).
 * Audio that was captured but not played yet is kept in a backlog (max. 5 minutes).
 * [nativeStart]/[nativeProcess] must only be called from one thread; the other functions are thread-safe.
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
     * [historyFrames] frames captured just before (oldest first; may be 0). Slow-down then starts
     * seamlessly from the live audio (the output continues the waveform, no jump or crossfade).
     */
    @JvmStatic external fun nativeStart(handle: Long, history: FloatArray, historyFrames: Int)
    /** Consumes [frames] captured frames from [input] and renders [frames] frames to [output] */
    @JvmStatic external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray, frames: Int)
}
