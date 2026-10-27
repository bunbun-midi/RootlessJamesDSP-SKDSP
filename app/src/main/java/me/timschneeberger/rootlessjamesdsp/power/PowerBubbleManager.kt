package me.timschneeberger.rootlessjamesdsp.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.EngineUtils.toggleEnginePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Owns the floating power button. Unlike the other floating buttons it has to exist while audio
 * processing is off (to turn it on), so it lives with the app process instead of the audio
 * service; [init] is called from MainApplication.
 */
object PowerBubbleManager : KoinComponent {
    private val preferences: Preferences.App by inject()

    private var appContext: Context? = null
    private var bubble: PowerBubble? = null
    private val handler = Handler(Looper.getMainLooper())

    private val powerPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val ctx = appContext ?: return@OnSharedPreferenceChangeListener
        if (key == ctx.getString(R.string.key_power_bubble_enable))
            update()
    }

    // Root builds: the engine state is the "powered on" preference
    private val appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val ctx = appContext ?: return@OnSharedPreferenceChangeListener
        if (key == ctx.getString(R.string.key_powered_on))
            bubble?.refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SERVICE_STARTED, Constants.ACTION_SERVICE_STOPPED -> bubble?.refresh()
                Constants.ACTION_OVERLAYS_REFRESH -> update()
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null)
            return
        val ctx = context.applicationContext
        appContext = ctx
        prefs(ctx).registerOnSharedPreferenceChangeListener(powerPrefsListener)
        preferences.registerOnSharedPreferenceChangeListener(appPrefsListener)
        ctx.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_SERVICE_STARTED)
            addAction(Constants.ACTION_SERVICE_STOPPED)
            addAction(Constants.ACTION_OVERLAYS_REFRESH)
        })
        update()
    }

    /** Shows or hides the button according to the setting and the overlay permission */
    fun update() {
        val ctx = appContext ?: return
        val enabled = prefs(ctx).getBoolean(ctx.getString(R.string.key_power_bubble_enable), false)
        if (enabled && Settings.canDrawOverlays(ctx)) {
            if (bubble == null) {
                bubble = try {
                    PowerBubble(ctx).also { it.show() }
                } catch (ex: Exception) {
                    Timber.e("Failed to create power bubble")
                    Timber.e(ex)
                    null
                }
            }
        } else {
            bubble?.hide()
            bubble = null
        }
    }

    fun isEngineOn(): Boolean =
        (isRootless() && BaseAudioProcessorService.activeServices > 0) ||
            (!isRootless() && preferences.get<Boolean>(R.string.key_powered_on))

    fun toggle() {
        val ctx = appContext ?: return
        try {
            ctx.toggleEnginePower(!isEngineOn())
        } catch (ex: Exception) {
            Timber.e("Failed to toggle audio processing")
            Timber.e(ex)
        }
        // The service reports its state with a broadcast; refresh again in case none arrives
        handler.postDelayed({ bubble?.refresh() }, 1500)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_POWER, Context.MODE_PRIVATE)
}
