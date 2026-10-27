package me.timschneeberger.rootlessjamesdsp.utils

import android.media.audiofx.AudioEffect
import timber.log.Timber
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * An [AudioEffect] created through its hidden constructor, with the hidden parameter methods called
 * via reflection. Replaces the former compile-time "refined" AudioEffectHidden stub (Rikka Refine),
 * so the app no longer needs the Refine runtime or a hidden-API bypass library.
 *
 * Accessibility for apps without a bypass (Android 16 hidden-API lists):
 *  - AudioEffect(UUID, UUID, int, int)          @UnsupportedAppUsage  -> allowed
 *  - setParameter(int[], short[])               @UnsupportedAppUsage  -> allowed (up to 4 value bytes)
 *  - setParameter(byte[], byte[]) / getParameter(byte[], byte[])  @TestApi -> blocked unless the
 *    app exempts itself (root/plugin builds still do, see flavor/HiddenApiAccess)
 * Small values (up to 4 bytes) therefore always go through setParameter(int[], short[]), which sends
 * exactly the same bytes as setParameter(int, int/short) did.
 *
 * Exceptions thrown by the effect (IllegalArgumentException, UnsupportedOperationException,
 * IllegalStateException, ...) are passed on unchanged, as with direct calls.
 */
class HiddenAudioEffect(type: UUID, uuid: UUID, priority: Int, audioSession: Int) {

    /** The underlying effect (public AudioEffect API: enabled, listeners, release, ...) */
    val effect: AudioEffect = newEffect(type, uuid, priority, audioSession)

    var enabled: Boolean
        get() = effect.enabled
        set(value) { effect.enabled = value }

    fun release() = effect.release()

    /* ---- Parameters: integer parameter id, values of up to 4 bytes ---- */

    fun setParameter(param: Int, value: Int): Int = setParameterShorts(param, intToShorts(value))

    fun setParameter(param: Int, value: Short): Int = setParameterShorts(param, shortArrayOf(value))

    /* ---- Parameters: arbitrary byte arrays (blocked for apps without a hidden-API exemption) ---- */

    fun setParameter(param: Int, value: ByteArray): Int = setParameter(intToBytes(param), value)

    fun setParameter(param: ByteArray, value: ByteArray): Int =
        call(setBytes, "setParameter(byte[], byte[])", param, value)

    fun getParameter(param: Int, value: ByteArray): Int = getParameter(intToBytes(param), value)

    fun getParameter(param: ByteArray, value: ByteArray): Int =
        call(getBytes, "getParameter(byte[], byte[])", param, value)

    private fun setParameterShorts(param: Int, value: ShortArray): Int =
        call(setShorts, "setParameter(int[], short[])", intArrayOf(param), value)

    private fun call(method: Method?, name: String, vararg args: Any): Int {
        if (method == null) {
            Timber.e("AudioEffect.$name is not accessible")
            return AudioEffect.ERROR_INVALID_OPERATION
        }
        return try {
            method.invoke(effect, *args) as Int
        } catch (ex: InvocationTargetException) {
            throw ex.targetException ?: ex
        }
    }

    companion object {
        /** Null effect type/UUID (AudioEffect.EFFECT_TYPE_NULL, hidden) */
        val EFFECT_TYPE_NULL: UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")

        private val constructor: Constructor<AudioEffect>? by lazy {
            lookup {
                AudioEffect::class.java.getDeclaredConstructor(
                    UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
            }
        }
        private val setShorts: Method? by lazy {
            lookup { AudioEffect::class.java.getMethod("setParameter", IntArray::class.java, ShortArray::class.java) }
        }
        private val setBytes: Method? by lazy {
            lookup { AudioEffect::class.java.getMethod("setParameter", ByteArray::class.java, ByteArray::class.java) }
        }
        private val getBytes: Method? by lazy {
            lookup { AudioEffect::class.java.getMethod("getParameter", ByteArray::class.java, ByteArray::class.java) }
        }

        private fun <T> lookup(block: () -> T): T? = try {
            block()
        } catch (t: Throwable) {
            Timber.w("Hidden AudioEffect member not accessible: $t")
            null
        }

        private fun newEffect(type: UUID, uuid: UUID, priority: Int, audioSession: Int): AudioEffect {
            val ctor = constructor
                ?: throw UnsupportedOperationException("AudioEffect(UUID, UUID, int, int) is not accessible")
            return try {
                ctor.newInstance(type, uuid, priority, audioSession)
            } catch (ex: InvocationTargetException) {
                throw ex.targetException ?: ex
            }
        }

        /** Same as the hidden AudioEffect.isEffectTypeAvailable(), using the public queryEffects() */
        fun isEffectTypeAvailable(type: UUID): Boolean = try {
            AudioEffect.queryEffects()?.any { it.type == type } ?: false
        } catch (t: Throwable) {
            Timber.w(t)
            false
        }

        /* Same byte layout as AudioEffect.intToByteArray()/shortToByteArray() (native byte order) */
        private fun intToBytes(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()

        private fun intToShorts(value: Int): ShortArray {
            val buffer = ByteBuffer.wrap(intToBytes(value)).order(ByteOrder.nativeOrder())
            return shortArrayOf(buffer.short, buffer.short)
        }
    }
}
