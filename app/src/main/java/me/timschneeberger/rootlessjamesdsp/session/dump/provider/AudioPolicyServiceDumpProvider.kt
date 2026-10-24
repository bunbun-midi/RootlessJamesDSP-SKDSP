package me.timschneeberger.rootlessjamesdsp.session.dump.provider

import android.content.Context
import android.os.Process
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.session.dump.data.AudioPolicyServiceDump
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.DumpUtils
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import timber.log.Timber
import java.util.Locale

class AudioPolicyServiceDumpProvider : ISessionDumpProvider {

    override fun dump(context: Context): ISessionInfoDump? {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        dump ?: return null

        return process(context, dump)
    }

    private fun process(context: Context, dump: String): ISessionInfoDump {
        // API 29+
        val sessionRegex = """Session Id:\s*(\d+)\s+UID:\s*(\d+)[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)""".toRegex()
        // API 33+
        val sessionRegex33 = """Session ID:\s*(\d+);\s*uid \s*(\d+);[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)""".toRegex()
        // General
        val captureAllowedRegex = """allowPlaybackCapture=(\S+)(?:\s*,).+packageName=(\S+)""".toRegex()
        // API 33+: attribute flags of each active AudioTrack client
        val trackFlagsRegex33 = """Session ID:\s*\d+;\s*uid\s*(\d+);(?:[^\n]*\n){1,3}?\s*Attributes:[^\n]*?Flags:\s*0x([0-9a-fA-F]+)""".toRegex()

        val sessions = hashMapOf<Int, AudioSessionDumpEntry>()

        var matches = sessionRegex.findAll(dump)
        if(matches.count() <= 0)
        {
            matches = sessionRegex33.findAll(dump)
        }

        matches.forEach next@ {
            try {
                val sid = it.groups[1]?.value?.toInt()
                val uid = it.groups[2]?.value?.toInt()
                val content = it.groups[3]?.value ?: "CONTENT_TYPE_UNKNOWN"
                val usage = it.groups[4]?.value
                if(sid == null || uid == null || usage == null)
                {
                    Timber.e("Incomplete match at '${it.value}': uid=$uid; sid=$sid; content=$content; usage=$usage")
                    return@next
                }

                val pkg = context.getPackageNameFromUid(uid) ?: uid.toString()
                sessions[sid] = AudioSessionDumpEntry(uid, pkg, usage, content)
                Timber.v("Found session id $sid (uid $uid; usage $usage; content $content; pkg $pkg)")

            } catch (ex: NumberFormatException) {
                Timber.e("Failed to parse match")
                Timber.e(ex)
            }
        }

        // Parse capture allow log
        val captureAllowLog = hashMapOf<String, Boolean>()
        captureAllowedRegex.findAll(dump).forEach regexLoop@{ it2 ->
            val pkgName = it2.groups[2]?.value?.replace("shared:", "") ?: return@regexLoop
            val allowed = it2.groups[1]?.value?.trim()?.lowercase(Locale.ROOT) == "true"
            captureAllowLog[pkgName] = allowed
            if (!allowed && isRootless()) {
                Timber.v("Playback capture restricted by $pkgName")
            }
        }

        // Parse per-stream capture restrictions.
        // Apps can block playback capture for individual streams using
        // AudioAttributes.Builder.setAllowedCapturePolicy() or AAudioStreamBuilder_setAllowedCapturePolicy()
        // (e.g. Spotify). Unlike the manifest flag, these never show up in the capture permission log above.
        // If we don't detect them, the session gets muted although we can't capture it -> no audio at all.
        trackFlagsRegex33.findAll(dump).forEach regexLoop@{ match ->
            val uid = match.groups[1]?.value?.toIntOrNull() ?: return@regexLoop
            val flags = match.groups[2]?.value?.toIntOrNull(16) ?: return@regexLoop
            if (uid == Process.myUid() || (flags and AUDIO_FLAG_NO_MEDIA_PROJECTION) == 0)
                return@regexLoop

            val pkgName = context.getPackageNameFromUid(uid) ?: return@regexLoop
            if (captureAllowLog[pkgName] != false && isRootless()) {
                Timber.v("Playback capture restricted by $pkgName (stream flags: 0x${flags.toString(16)})")
            }
            captureAllowLog[pkgName] = false
        }

        Timber.d("Dump processed")
        return AudioPolicyServiceDump(sessions, captureAllowLog)
    }

    override fun dumpString(context: Context): String {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        val sb = StringBuilder("=====> $TARGET_SERVICE raw dump\n")
        sb.append(dump)
        sb.append("\n\n")
        sb.append("=====> $TARGET_SERVICE processed dump\n")
        sb.append(process(context, dump ?: ""))

        return sb.toString()
    }

    companion object
    {
        const val TARGET_SERVICE = "media.audio_policy"

        // system/media/audio/include/system/audio-hal-enums.h: AUDIO_FLAG_NO_MEDIA_PROJECTION
        private const val AUDIO_FLAG_NO_MEDIA_PROJECTION = 0x400
    }
}
