package me.timschneeberger.rootlessjamesdsp.interop

/**
 * JNI bindings for recorder::AudioEncoder (libjamesdsp-wrapper/AudioEncoder.cpp):
 * MP3 (LAME) and Ogg Vorbis (libvorbis) encoders for interleaved stereo float audio.
 * One instance must only be used from one thread at a time.
 */
object AudioEncoder {
    const val CODEC_MP3 = 1
    const val CODEC_VORBIS = 2

    init {
        System.loadLibrary("jamesdsp-wrapper")
    }

    /**
     * @param variableBitrate quality-based VBR (true) or constant (MP3) / average (Ogg) bitrate
     * @param quality 0 (smallest) .. 10 (best); used for VBR
     * @param bitrateKbps used when [variableBitrate] is false
     * @return handle, or 0 if the settings are not supported
     */
    @JvmStatic external fun nativeCreate(codec: Int, sampleRate: Int, variableBitrate: Boolean, quality: Int, bitrateKbps: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    /** Encodes [frames] interleaved stereo frames; returns the encoded bytes (may be empty), null on error */
    @JvmStatic external fun nativeEncode(handle: Long, input: FloatArray, frames: Int): ByteArray?
    /** Flushes the encoder; returns the remaining bytes, null on error */
    @JvmStatic external fun nativeFinish(handle: Long): ByteArray?
    /** MP3: final LAME/Xing tag frame to write over the start of the file (empty otherwise) */
    @JvmStatic external fun nativeHeaderUpdate(handle: Long): ByteArray?
}
