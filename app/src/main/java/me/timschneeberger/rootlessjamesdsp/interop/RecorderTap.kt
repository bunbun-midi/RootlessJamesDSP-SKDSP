package me.timschneeberger.rootlessjamesdsp.interop

/**
 * JNI bindings for chain::RecorderTap (libjamesdsp-wrapper/RecorderTap.cpp): a lock-free ring buffer
 * filled by the "Recorder" effect block on the audio thread and drained by the WAV writer thread.
 */
object RecorderTap {
    init {
        System.loadLibrary("jamesdsp-wrapper")
    }

    /** @return handle, or 0 on failure */
    @JvmStatic external fun nativeCreate(capacityFrames: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    /** Reads interleaved stereo frames into [output]; returns the number of frames */
    @JvmStatic external fun nativeRead(handle: Long, output: FloatArray): Int
    @JvmStatic external fun nativeDroppedFrames(handle: Long): Long
}
