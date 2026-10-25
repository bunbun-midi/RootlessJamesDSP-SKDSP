package me.timschneeberger.rootlessjamesdsp.interop

/**
 * JNI bindings for chain::BpmDetector (libjamesdsp-wrapper/BpmDetector.cpp): tempo estimation from
 * interleaved stereo float audio. [nativeProcess] must only be called from one thread (the audio thread).
 */
object BpmDetector {
    init {
        System.loadLibrary("jamesdsp-wrapper")
    }

    /** @return handle, or 0 on failure */
    @JvmStatic external fun nativeCreate(sampleRate: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeProcess(handle: Long, input: FloatArray, frames: Int)
    /** Current estimate in BPM, 0 if unknown */
    @JvmStatic external fun nativeBpm(handle: Long): Float
    /** 0..1 */
    @JvmStatic external fun nativeConfidence(handle: Long): Float
}
