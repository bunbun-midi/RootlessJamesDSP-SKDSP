package me.timschneeberger.rootlessjamesdsp.model

import android.content.Context
import androidx.annotation.StringRes
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/**
 * Effects whose processing order can be changed by the user.
 * IDs must match chain::EffectId in ChainProcessor.h and are persisted; never renumber.
 */
object EffectChain {
    enum class Effect(
        val id: Int,
        @StringRes val title: Int,
        private val enableKeys: List<Pair<String, Int>>,
    ) {
        TUBE(0, R.string.tube_enable, listOf(Constants.PREF_TUBE to R.string.key_tube_enable)),
        COMPANDER(1, R.string.compander_enable, listOf(Constants.PREF_COMPANDER to R.string.key_compander_enable)),
        BASS_BOOST(2, R.string.bass_enable, listOf(Constants.PREF_BASS to R.string.key_bass_enable)),
        MULTI_EQ(3, R.string.eq_enable, listOf(Constants.PREF_EQ to R.string.key_eq_enable)),
        ARBITRARY_EQ(4, R.string.effect_order_geq_peq, listOf(
            Constants.PREF_GEQ to R.string.key_geq_enable,
            Constants.PREF_PEQ to R.string.key_peq_enable
        )),
        CONVOLVER(5, R.string.convolver_enable, listOf(Constants.PREF_CONVOLVER to R.string.key_convolver_enable)),
        DDC(6, R.string.ddc_enable, listOf(Constants.PREF_DDC to R.string.key_ddc_enable)),
        LIVEPROG(7, R.string.liveprog_enable, listOf(Constants.PREF_LIVEPROG to R.string.key_liveprog_enable)),
        CROSSFEED(8, R.string.crossfeed_enable, listOf(Constants.PREF_CROSSFEED to R.string.key_crossfeed_enable)),
        STEREO_WIDE(9, R.string.stereowide_enable, listOf(Constants.PREF_STEREOWIDE to R.string.key_stereowide_enable)),
        REVERB(10, R.string.reverb_enable, listOf(Constants.PREF_REVERB to R.string.key_reverb_enable)),
        COMPRESSOR(11, R.string.compressor_enable, listOf(Constants.PREF_COMPRESSOR to R.string.key_compressor_enable)),
        PITCH_SHIFT(12, R.string.pitch_enable, listOf(Constants.PREF_PITCH to R.string.key_pitch_enable)),
        PITCH_SHIFT_HQ(13, R.string.pitch_hq_enable, listOf(Constants.PREF_PITCH_HQ to R.string.key_pitch_hq_enable)),
        RECORDER(14, R.string.recorder_title, listOf(Constants.PREF_RECORDER to R.string.key_recorder_enable));

        /** Preference namespaces holding this effect's enable switch */
        val namespaces: List<String>
            get() = enableKeys.map { it.first }

        fun isEnabled(context: Context): Boolean = enableKeys.any { (namespace, key) ->
            PreferenceCache.uncachedGet(context, namespace, key, false)
        }

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id }
        }
    }

    /** libjamesdsp's built-in order, plus the pitch shifters first, the compressor after the compander and the recorder last */
    val DEFAULT_ORDER = listOf(
        Effect.PITCH_SHIFT, Effect.PITCH_SHIFT_HQ, Effect.TUBE, Effect.COMPANDER, Effect.COMPRESSOR, Effect.BASS_BOOST, Effect.MULTI_EQ,
        Effect.ARBITRARY_EQ, Effect.CONVOLVER, Effect.DDC, Effect.LIVEPROG,
        Effect.CROSSFEED, Effect.STEREO_WIDE, Effect.REVERB, Effect.RECORDER
    )

    val DEFAULT_ORDER_STRING = serialize(DEFAULT_ORDER)

    fun serialize(order: List<Effect>) = order.joinToString(",") { it.id.toString() }

    /**
     * Parses a stored order. Unknown or duplicate entries are dropped. Missing effects (e.g. ones
     * added in a newer version) are inserted right after their nearest default-order predecessor
     * (or, if none is present, before their nearest successor). Same rules as the native side, so
     * the result always contains every effect exactly once and the user's arrangement is kept.
     */
    fun parse(value: String?): List<Effect> {
        val result = value
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull()?.let(Effect::fromId) }
            ?.distinct()
            ?.toMutableList()
            ?: mutableListOf()
        DEFAULT_ORDER.forEachIndexed { index, effect ->
            if (effect in result)
                return@forEachIndexed
            // Right after the nearest default-order predecessor that is present...
            val predecessor = DEFAULT_ORDER.subList(0, index).lastOrNull { it in result }
            val position = if (predecessor != null) {
                result.indexOf(predecessor) + 1
            } else {
                // ...otherwise before the first effect that follows it in the default order
                val followers = DEFAULT_ORDER.subList(index + 1, DEFAULT_ORDER.size)
                result.indexOfFirst { it in followers }.takeIf { it >= 0 } ?: result.size
            }
            result.add(position, effect)
        }
        return result
    }
}
