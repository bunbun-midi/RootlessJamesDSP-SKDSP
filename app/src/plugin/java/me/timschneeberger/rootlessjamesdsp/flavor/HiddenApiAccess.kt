package me.timschneeberger.rootlessjamesdsp.flavor

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Root/plugin builds talk to the JamesDSP audio effect through hidden AudioEffect methods that are
 * blocked for regular apps (setParameter/getParameter with byte arrays), so they keep exempting
 * themselves from the hidden-API restrictions.
 */
object HiddenApiAccess {
    fun enable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            HiddenApiBypass.addHiddenApiExemptions("L")
    }
}
