package me.timschneeberger.rootlessjamesdsp.flavor

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

object CrashlyticsImpl {
    /**
     * Null when Firebase isn't configured for this package (no matching entry in
     * google-services.json, e.g. net.skeyboard.dsp); every call is then a no-op.
     */
    private val crashlytics: FirebaseCrashlytics? by lazy {
        try {
            Firebase.crashlytics
        } catch (ex: IllegalStateException) {
            null
        }
    }

    fun setCollectionEnabled(on: Boolean) { crashlytics?.setCrashlyticsCollectionEnabled(on) }
    fun setCustomKey(key: String, value: String) { crashlytics?.setCustomKey(key, value) }
    fun setCustomKey(key: String, value: Int) { crashlytics?.setCustomKey(key, value) }
    fun log(msg: String) { crashlytics?.log(msg) }
    fun recordException(t: Throwable) { crashlytics?.recordException(t) }
    fun sendUnsentReports() { crashlytics?.sendUnsentReports() }
}
