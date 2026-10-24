package me.timschneeberger.rootlessjamesdsp.utils.extensions

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import java.io.Serializable

@Suppress("DEPRECATION")
object CompatExtensions {
    /**
     * Audio capture needs a projection of the whole device. On Android 14+, the consent dialog
     * would otherwise also offer to share a single app, which is not useful for us.
     */
    fun MediaProjectionManager.createAudioCaptureIntent(): Intent {
        return sdkAbove(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        }.below {
            createScreenCaptureIntent()
        }
    }

    inline fun <reified T : Serializable> Bundle.getSerializableAs(key: String): T? {
        return sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            this.getSerializable(key, T::class.java)
        }.below {
            this.getSerializable(key) as? T
        }
    }

    inline fun <reified T : Parcelable> Bundle.getParcelableAs(key: String): T? {
        return sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            this.getParcelable(key, T::class.java)
        }.below {
            this.getParcelable(key)
        }
    }

    fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int = 0): PackageInfo {
        return sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        }.below {
            getPackageInfo(packageName, flags)
        }
    }

    fun PackageManager.getApplicationInfoCompat(packageName: String, flags: Int = 0): ApplicationInfo {
        return sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        }.below {
            getApplicationInfo(packageName, flags)
        }
    }

    fun PackageManager.getInstalledApplicationsCompat(flags: Int = 0): List<ApplicationInfo> {
        return sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        }.below {
            getInstalledApplications(flags)
        }
    }
}