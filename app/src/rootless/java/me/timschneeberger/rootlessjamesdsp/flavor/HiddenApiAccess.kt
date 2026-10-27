package me.timschneeberger.rootlessjamesdsp.flavor

/**
 * Rootless builds (SK DSP, Google Play) don't exempt themselves from Android's hidden-API
 * restrictions and don't ship a bypass library. The few hidden members they use are accessible to
 * regular apps (see utils/HiddenAudioEffect and hidden-api-impl/ShizukuSystemServerApi).
 */
object HiddenApiAccess {
    fun enable() {}
}
