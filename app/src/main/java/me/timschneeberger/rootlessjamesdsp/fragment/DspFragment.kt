package me.timschneeberger.rootlessjamesdsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDspBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(layoutInflater, container, false)

        binding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        binding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/rootlessjamesdsp")))
            hideTranslationNotice()
        }

        binding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        binding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        binding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        binding.updateNotice.isVisible = false

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        binding.cardContainer.layoutTransition = transition

        childFragmentManager.beginTransaction()
            .replace(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
            .replace(
                R.id.card_output_control, PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT,
                    R.xml.dsp_output_control_preferences
                ))
            .replace(
                R.id.card_effect_order, PreferenceGroupFragment.newInstance(Constants.PREF_EFFECT_ORDER,
                    R.xml.dsp_effect_order_preferences
                ))
            .replace(
                R.id.card_pitch, PreferenceGroupFragment.newInstance(Constants.PREF_PITCH,
                    R.xml.dsp_pitch_preferences
                ))
            .replace(
                R.id.card_pitch_hq, PreferenceGroupFragment.newInstance(Constants.PREF_PITCH_HQ,
                    R.xml.dsp_pitch_hq_preferences
                ))
            .replace(
                R.id.card_power_bubble, PreferenceGroupFragment.newInstance(Constants.PREF_POWER,
                    R.xml.power_preferences
                ))
            .replace(
                R.id.card_speed_shift, PreferenceGroupFragment.newInstance(Constants.PREF_SPEED_SHIFT,
                    R.xml.speed_shift_preferences
                ))
            .replace(
                R.id.card_recorder, PreferenceGroupFragment.newInstance(Constants.PREF_RECORDER,
                    R.xml.recorder_preferences
                ))
            .replace(
                R.id.card_recorder_b, PreferenceGroupFragment.newInstance(Constants.PREF_RECORDER_B,
                    R.xml.recorder_preferences
                ))
            .replace(
                R.id.card_bpm, PreferenceGroupFragment.newInstance(Constants.PREF_BPM,
                    R.xml.bpm_preferences
                ))
            .replace(
                R.id.card_keyboard, PreferenceGroupFragment.newInstance(Constants.PREF_KEYBOARD,
                    R.xml.keyboard_preferences
                ))
            .replace(
                R.id.card_compressor, PreferenceGroupFragment.newInstance(Constants.PREF_COMPANDER,
                    R.xml.dsp_compander_preferences
                ))
            .replace(
                R.id.card_dyn_compressor, PreferenceGroupFragment.newInstance(Constants.PREF_COMPRESSOR,
                    R.xml.dsp_compressor_preferences
                ))
            .replace(
                R.id.card_bass, PreferenceGroupFragment.newInstance(Constants.PREF_BASS,
                    R.xml.dsp_bass_preferences
                ))
            .replace(
                R.id.card_eq, PreferenceGroupFragment.newInstance(Constants.PREF_EQ,
                    R.xml.dsp_equalizer_preferences
                ))
            .replace(
                R.id.card_peq, PreferenceGroupFragment.newInstance(Constants.PREF_PEQ,
                    R.xml.dsp_parametriceq_preferences
                ))
            .replace(
                R.id.card_geq, PreferenceGroupFragment.newInstance(Constants.PREF_GEQ,
                    R.xml.dsp_graphiceq_preferences
                ))
            .replace(
                R.id.card_ddc, PreferenceGroupFragment.newInstance(Constants.PREF_DDC,
                    R.xml.dsp_ddc_preferences
                ))
            .replace(
                R.id.card_convolver, PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER,
                    R.xml.dsp_convolver_preferences
                ))
            .replace(
                R.id.card_liveprog, PreferenceGroupFragment.newInstance(Constants.PREF_LIVEPROG,
                    R.xml.dsp_liveprog_preferences
                ))
            .replace(
                R.id.card_tube, PreferenceGroupFragment.newInstance(Constants.PREF_TUBE,
                    R.xml.dsp_tube_preferences
                ))
            .replace(
                R.id.card_stereowide, PreferenceGroupFragment.newInstance(Constants.PREF_STEREOWIDE,
                    R.xml.dsp_stereowide_preferences
                ))
            .replace(
                R.id.card_crossfeed, PreferenceGroupFragment.newInstance(Constants.PREF_CROSSFEED,
                    R.xml.dsp_crossfeed_preferences
                ))
            .replace(
                R.id.card_reverb, PreferenceGroupFragment.newInstance(Constants.PREF_REVERB,
                    R.xml.dsp_reverb_preferences
                ))
            .replace(
                R.id.card_page_options, PreferenceGroupFragment.newInstance(Constants.PREF_APP,
                    R.xml.dsp_page_options_preferences
                ))
            .commit()

        // Effect order, compressor, pitch shifter and convolver wet/dry are implemented in the rootless engine only
        binding.cardEffectOrderWrapper.isVisible = isRootless()
        binding.cardDynCompressorWrapper.isVisible = isRootless()
        binding.cardPitchWrapper.isVisible = isRootless()
        binding.cardPitchHqWrapper.isVisible = isRootless()
        binding.cardSpeedShiftWrapper.isVisible = isRootless()
        binding.cardRecorderWrapper.isVisible = isRootless()
        binding.cardRecorderBWrapper.isVisible = isRootless()
        binding.cardBpmWrapper.isVisible = isRootless()
        binding.cardKeyboardWrapper.isVisible = isRootless()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }

        return binding.root
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                (binding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun hideTranslationNotice() {
        binding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    fun setUpdateCardVisible(visible: Boolean) {
        binding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        binding.updateNotice.titleText = title
    }

    fun setUpdateCardOnClick(onClick: () -> Unit) {
        updateNoticeOnClick = onClick
    }

    fun setUpdateCardOnCloseClick(onClick: () -> Unit) {
        updateNoticeOnCloseClick = onClick
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                childFragmentManager.beginTransaction()
                    .replace(id, newFragment)
                    .commitAllowingStateLoss()
            }
            catch(ex: IllegalStateException) {
                Timber.e("Failed to restart fragment")
                Timber.i(ex)
            }
        }
    }

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}