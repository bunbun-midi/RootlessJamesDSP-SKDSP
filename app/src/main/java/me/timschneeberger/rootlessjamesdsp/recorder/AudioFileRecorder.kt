package me.timschneeberger.rootlessjamesdsp.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import me.timschneeberger.rootlessjamesdsp.interop.AudioEncoder
import me.timschneeberger.rootlessjamesdsp.interop.RecorderTap
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Records the audio delivered by the "Recorder" effect block into Music/SK DSP
 * (MediaStore, no storage permission needed) as WAV, MP3 (LAME) or Ogg Vorbis.
 *
 * The audio thread fills a native ring buffer ([RecorderTap]); a writer thread drains it every
 * [POLL_MS], encodes and appends to the file, so no encoding or disk I/O happens on the audio thread.
 *
 * @param variableBitrate MP3/Ogg: quality-based VBR (true) or fixed/average bitrate (false)
 * @param quality MP3/Ogg VBR quality, 0 (smallest) .. 10 (best)
 * @param bitrateKbps MP3 constant / Ogg average bitrate
 * @param requestStop called on the main thread if the recording has to end (size limit, I/O error);
 *        the owner then detaches the tap and calls [finish]
 * @param onFinished called on the main thread once the file is complete (or failed)
 *
 * Lifecycle (lets several recorders start/stop on exactly the same audio block):
 * [prepare] -> attach [tapHandle] to the effect chain -> [begin] -> detach -> [finish].
 * If attaching fails after [prepare], call [abort].
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioFileRecorder(
    private val context: Context,
    val sampleRate: Int,
    private val format: Format,
    private val variableBitrate: Boolean,
    private val quality: Int,
    private val bitrateKbps: Int,
    baseName: String,
    private val requestStop: () -> Unit,
    private val onFinished: (AudioFileRecorder, Result) -> Unit,
) {
    enum class Format(val value: String, val extension: String, val mimeType: String, val codec: Int) {
        WAV_PCM16("pcm16", "wav", "audio/x-wav", 0),
        WAV_FLOAT("float32", "wav", "audio/x-wav", 0),
        MP3("mp3", "mp3", "audio/mpeg", AudioEncoder.CODEC_MP3),
        OGG_VORBIS("ogg", "ogg", "audio/ogg", AudioEncoder.CODEC_VORBIS);

        val isWav get() = codec == 0

        companion object {
            fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: WAV_PCM16
        }
    }

    sealed class Result {
        data class Saved(val name: String, val droppedSeconds: Float) : Result()
        data class Failed(val message: String) : Result()
    }

    val fileName: String = baseName + "." + format.extension

    // WAV only
    private val floatWav = format == Format.WAV_FLOAT
    private val bytesPerSample = if (floatWav) 4 else 2
    private val frameBytes = 2 * bytesPerSample

    private var tap = 0L
    private var encoder = 0L
    private var uri: Uri? = null
    private var stream: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var channel: FileChannel? = null
    private var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    /** Native tap to attach to the effect chain (valid between [prepare] and the end of recording) */
    val tapHandle: Long
        get() = tap

    /** Creates the encoder, the tap and the file. @throws Exception on failure (nothing is left behind) */
    fun prepare() {
        try {
            if (!format.isWav) {
                encoder = AudioEncoder.nativeCreate(format.codec, sampleRate, variableBitrate, quality, bitrateKbps)
                if (encoder == 0L)
                    throw IOException("${format.extension.uppercase()} encoder doesn't support these settings ($sampleRate Hz)")
            }

            tap = RecorderTap.nativeCreate(sampleRate * TAP_SECONDS)
            if (tap == 0L)
                throw IOException("Out of memory")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newUri = resolver.insert(collection, values) ?: throw IOException("Cannot create $fileName")
            uri = newUri
            val pfd = resolver.openFileDescriptor(newUri, "rw") ?: throw IOException("Cannot open $fileName")
            stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            channel = stream!!.channel.also { if (format.isWav) writeFully(it, wavHeader(0), 0) }
        } catch (ex: Exception) {
            abort()
            throw ex
        }
    }

    /** Starts writing; call once the tap is attached */
    fun begin() {
        thread = Thread({ writerLoop() }, "AudioFileRecorder").also { it.start() }
    }

    /** Prepared but never started: deletes the file and frees everything */
    fun abort() {
        closeFile(success = false)
        releaseNative()
    }

    /**
     * Ends the recording; call only after the tap was detached from the effect chain (the audio
     * thread must no longer write into it). The file is completed asynchronously ([onFinished]).
     */
    fun finish() {
        stopRequested = true
    }

    /** Waits until the file is complete (service shutdown) */
    fun awaitFinished(timeoutMs: Long) {
        try {
            thread?.join(timeoutMs)
        } catch (ex: InterruptedException) {
            Timber.w(ex)
        }
    }

    private fun writerLoop() {
        val floats = FloatArray(BLOCK_FRAMES * 2)
        val wavBytes = ByteBuffer.allocate(BLOCK_FRAMES * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val channel = channel!!
        var position = if (format.isWav) wavHeader(0).capacity().toLong() else 0L
        var dataBytes = 0L
        var error: Exception? = null
        var stopAsked = false

        while (true) {
            // Read the flag before draining: after finish() the tap is detached, so one
            // complete drain afterwards collects everything that was recorded
            val finishing = stopRequested
            do {
                val frames = RecorderTap.nativeRead(tap, floats)
                // After an error the audio is still drained (and dropped) until the owner stops us
                if (frames > 0 && error == null) {
                    try {
                        val written = if (format.isWav) {
                            wavBytes.clear()
                            if (floatWav) {
                                for (i in 0 until frames * 2)
                                    wavBytes.putFloat(floats[i])
                            } else {
                                for (i in 0 until frames * 2)
                                    wavBytes.putShort((floats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            }
                            wavBytes.flip()
                            writeFully(channel, wavBytes, position)
                        } else {
                            val encoded = AudioEncoder.nativeEncode(encoder, floats, frames)
                                ?: throw IOException("Encoder error")
                            writeFully(channel, ByteBuffer.wrap(encoded), position)
                        }
                        position += written
                        dataBytes += written
                    } catch (ex: Exception) {
                        Timber.e("Recording failed")
                        Timber.e(ex)
                        error = ex
                    }
                }
            } while (frames == BLOCK_FRAMES)

            if (finishing)
                break

            // WAV files can't be larger than 4 GB; errors end the recording too
            if (!stopAsked && (error != null || (format.isWav && dataBytes >= MAX_WAV_DATA_BYTES))) {
                Timber.w("Recording has to end (error or WAV size limit)")
                stopAsked = true
                Handler(Looper.getMainLooper()).post { requestStop() }
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (ex: InterruptedException) {
                // keep going until finish() was called
            }
        }

        if (error == null) try {
            if (format.isWav) {
                writeFully(channel, wavHeader(dataBytes), 0)
            } else {
                val tail = AudioEncoder.nativeFinish(encoder) ?: throw IOException("Encoder error")
                position += writeFully(channel, ByteBuffer.wrap(tail), position)
                // MP3: final Xing/LAME tag (duration & seek table for VBR) replaces the first frame
                AudioEncoder.nativeHeaderUpdate(encoder)?.takeIf { it.isNotEmpty() }?.let {
                    writeFully(channel, ByteBuffer.wrap(it), 0)
                }
            }
        } catch (ex: Exception) {
            Timber.e("Finishing the recording failed")
            Timber.e(ex)
            error = ex
        }

        val dropped = if (tap != 0L) RecorderTap.nativeDroppedFrames(tap) else 0L
        releaseNative()

        val saved = closeFile(success = error == null)
        val result = if (saved)
            Result.Saved(fileName, dropped.toFloat() / sampleRate)
        else
            Result.Failed(error?.message ?: "I/O error")
        Handler(Looper.getMainLooper()).post { onFinished(this, result) }
    }

    /** Frees the tap (must be detached) and the encoder */
    private fun releaseNative() {
        if (tap != 0L) {
            RecorderTap.nativeDestroy(tap)
            tap = 0
        }
        if (encoder != 0L) {
            AudioEncoder.nativeDestroy(encoder)
            encoder = 0
        }
    }

    /** Closes the file and publishes it (or deletes it on failure). Returns true if published. */
    private fun closeFile(success: Boolean): Boolean {
        try {
            stream?.close()
        } catch (ex: Exception) {
            Timber.w(ex)
        }
        stream = null
        channel = null

        val target = uri ?: return false
        uri = null
        return try {
            if (success) {
                context.contentResolver.update(target, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                true
            } else {
                context.contentResolver.delete(target, null, null)
                false
            }
        } catch (ex: Exception) {
            Timber.e(ex)
            false
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Int {
        var written = 0
        while (buffer.hasRemaining())
            written += channel.write(buffer, position + written)
        return written
    }

    /** RIFF/WAVE header for [dataBytes] of audio (PCM 16-bit: 44 bytes; IEEE float: 58 bytes) */
    private fun wavHeader(dataBytes: Long): ByteBuffer {
        val fmtSize = if (floatWav) 18 else 16
        val factSize = if (floatWav) 12 else 0
        val headerSize = 12 + 8 + fmtSize + factSize + 8
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((headerSize - 8 + dataBytes).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(fmtSize)
        buffer.putShort((if (floatWav) 3 else 1).toShort())      // WAVE_FORMAT_IEEE_FLOAT / PCM
        buffer.putShort(2)                                       // channels
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * frameBytes)                   // byte rate
        buffer.putShort(frameBytes.toShort())                    // block align
        buffer.putShort((bytesPerSample * 8).toShort())          // bits per sample
        if (floatWav) {
            buffer.putShort(0)                                   // cbSize
            buffer.put("fact".toByteArray(Charsets.US_ASCII))
            buffer.putInt(4)
            buffer.putInt((dataBytes / frameBytes).toInt())      // sample frames
        }
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes.toInt())
        buffer.flip()
        return buffer
    }

    companion object {
        const val FOLDER = "SK DSP"
        private const val TAP_SECONDS = 4
        private const val BLOCK_FRAMES = 8192
        private const val POLL_MS = 50L
        private const val MAX_WAV_DATA_BYTES = 0xFFFFFFFFL - 64L * 1024 * 1024
    }
}
