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
import me.timschneeberger.rootlessjamesdsp.interop.RecorderTap
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the audio delivered by the "Recorder" effect block into a WAV file in Music/SK DSP
 * (MediaStore, no storage permission needed).
 *
 * The audio thread fills a native ring buffer ([RecorderTap]); a writer thread drains it every
 * [POLL_MS] and appends to the file, so no disk I/O ever happens on the audio thread.
 *
 * @param attachTap attaches (handle) or detaches (0) the tap to/from the effect chain
 * @param onFinished called on the main thread once the file is complete (or failed)
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WavRecorder(
    private val context: Context,
    val sampleRate: Int,
    private val floatFormat: Boolean,
    private val attachTap: (Long) -> Boolean,
    private val onFinished: (WavRecorder, Result) -> Unit,
) {
    sealed class Result {
        data class Saved(val name: String, val droppedSeconds: Float) : Result()
        data class Failed(val message: String) : Result()
    }

    val fileName: String = "SK DSP " +
            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date()) + ".wav"

    private val bytesPerSample = if (floatFormat) 4 else 2
    private val frameBytes = 2 * bytesPerSample

    private var tap = 0L
    private var uri: Uri? = null
    private var stream: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var channel: FileChannel? = null
    private var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    /** @throws Exception if the file or the tap could not be created */
    fun start() {
        tap = RecorderTap.nativeCreate(sampleRate * TAP_SECONDS)
        if (tap == 0L)
            throw IOException("Out of memory")

        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newUri = resolver.insert(collection, values) ?: throw IOException("Cannot create $fileName")
            uri = newUri
            val pfd = resolver.openFileDescriptor(newUri, "rw") ?: throw IOException("Cannot open $fileName")
            stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            channel = stream!!.channel.also { writeFully(it, header(0), 0) }

            if (!attachTap(tap))
                throw IOException("Audio engine not ready")
        } catch (ex: Exception) {
            closeFile(success = false)
            RecorderTap.nativeDestroy(tap)
            tap = 0
            throw ex
        }

        thread = Thread({ writerLoop() }, "WavRecorder").also { it.start() }
    }

    /** Stops recording; the file is completed asynchronously ([onFinished]) */
    fun stop() {
        if (stopRequested)
            return
        // Detach first: once this returns, the audio thread no longer writes into the tap
        attachTap(0)
        stopRequested = true
    }

    /** Stops and waits until the file is complete (service shutdown) */
    fun stopBlocking(timeoutMs: Long) {
        stop()
        try {
            thread?.join(timeoutMs)
        } catch (ex: InterruptedException) {
            Timber.w(ex)
        }
    }

    private fun writerLoop() {
        val floats = FloatArray(BLOCK_FRAMES * 2)
        val bytes = ByteBuffer.allocate(BLOCK_FRAMES * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val channel = channel!!
        var dataBytes = 0L
        var error: Exception? = null

        try {
            var position = header(0).capacity().toLong()
            while (true) {
                // Read the flag before draining: after stop() the tap is detached, so one
                // complete drain afterwards collects everything that was recorded
                val finishing = stopRequested
                do {
                    val frames = RecorderTap.nativeRead(tap, floats)
                    if (frames > 0) {
                        bytes.clear()
                        if (floatFormat) {
                            for (i in 0 until frames * 2)
                                bytes.putFloat(floats[i])
                        } else {
                            for (i in 0 until frames * 2)
                                bytes.putShort((floats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                        }
                        bytes.flip()
                        position += writeFully(channel, bytes, position)
                        dataBytes += frames.toLong() * frameBytes
                    }
                } while (frames == BLOCK_FRAMES)

                if (finishing)
                    break

                // WAV files can't be larger than 4 GB
                if (dataBytes >= MAX_DATA_BYTES) {
                    Timber.w("WAV size limit reached; stopping")
                    stop()
                    continue
                }

                try {
                    Thread.sleep(POLL_MS)
                } catch (ex: InterruptedException) {
                    // keep going until stop() was called
                }
            }

            writeFully(channel, header(dataBytes), 0)
        } catch (ex: Exception) {
            Timber.e("Recording failed")
            Timber.e(ex)
            error = ex
            attachTap(0)
            stopRequested = true
        }

        val dropped = RecorderTap.nativeDroppedFrames(tap)
        RecorderTap.nativeDestroy(tap)
        tap = 0

        val saved = closeFile(success = error == null)
        val result = if (saved)
            Result.Saved(fileName, dropped.toFloat() / sampleRate)
        else
            Result.Failed(error?.message ?: "I/O error")
        Handler(Looper.getMainLooper()).post { onFinished(this, result) }
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
    private fun header(dataBytes: Long): ByteBuffer {
        val fmtSize = if (floatFormat) 18 else 16
        val factSize = if (floatFormat) 12 else 0
        val headerSize = 12 + 8 + fmtSize + factSize + 8
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((headerSize - 8 + dataBytes).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(fmtSize)
        buffer.putShort((if (floatFormat) 3 else 1).toShort()) // WAVE_FORMAT_IEEE_FLOAT / PCM
        buffer.putShort(2)                                       // channels
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * frameBytes)                   // byte rate
        buffer.putShort(frameBytes.toShort())                    // block align
        buffer.putShort((bytesPerSample * 8).toShort())          // bits per sample
        if (floatFormat) {
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
        private const val MAX_DATA_BYTES = 0xFFFFFFFFL - 64L * 1024 * 1024
    }
}
