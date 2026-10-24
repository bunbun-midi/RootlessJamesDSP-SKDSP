package me.timschneeberger.rootlessjamesdsp.activity

import android.app.ActivityManager
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegateImpl
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

abstract class BaseActivity :
    AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    KoinComponent,
    ThemingDelegate by ThemingDelegateImpl() {

    /* Preferences */
    protected val prefsApp: Preferences.App by inject()
    protected val prefsVar: Preferences.Var by inject()

    protected open val disableAppTheme = false

    /**
     * Set to true if the activity applies window insets to its own layout.
     * Otherwise, the content view is padded so that it never draws behind the system bars.
     */
    protected open val handlesWindowInsets = false

    protected val app
        get() = application as MainApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        if(!disableAppTheme)
            applyAppTheme(this)
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if(!handlesWindowInsets)
            applySystemBarInsets()
    }

    /**
     * Apps targeting Android 15+ are always drawn edge-to-edge, and the opt-out flag is ignored
     * when targeting Android 16+. Our layouts were designed for the old behaviour, so we pad the
     * content root by the system bar & display cutout insets, which reproduces the previous look.
     * On older Android versions the decor view already consumes these insets, so this is a no-op.
     *
     * Edge-to-edge windows also no longer resize for the IME automatically, so activities using
     * adjustResize additionally get padded by the keyboard height.
     */
    private fun applySystemBarInsets() {
        val content = findViewById<View>(android.R.id.content) ?: return
        val adjustResize = (window.attributes.softInputMode and
                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) ==
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = if (adjustResize) maxOf(bars.bottom, ime.bottom) else bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        // Exclude from recents if enabled
        getSystemService<ActivityManager>()?.appTasks?.takeIf { it.isNotEmpty() }?.forEach {
            it.setExcludeFromRecents(prefsApp.get<Boolean>(R.string.key_exclude_app_from_recents))
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if(key == getString(R.string.key_appearance_pure_black) ||
            key == getString(R.string.key_appearance_app_theme)) {
            if(!disableAppTheme)
                ActivityCompat.recreate(this)
        }
    }
}
