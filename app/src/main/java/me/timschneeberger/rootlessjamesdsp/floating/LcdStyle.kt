package me.timschneeberger.rootlessjamesdsp.floating

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.bpm.BpmController

/**
 * Retro LCD palette of the BPM display: light greyish-blue segments on dark blue-green-black glass.
 * With "LCD-style floating buttons" (BPM display settings) the floating buttons use it too.
 */
object LcdStyle {
    const val BACKGROUND_TOP = 0xFF0F2A2E.toInt()
    const val BACKGROUND_BOTTOM = 0xFF061518.toInt()
    const val BEZEL = 0xFF29474E.toInt()
    /** Bezel of a button that is on (active, recording) */
    const val BEZEL_ACTIVE = 0xFF5E8291.toInt()
    const val TEXT = 0xFFB4C8D8.toInt()
    const val DIM = 0xFF6E8A99.toInt()
    const val GLOW = 0x6690B4CC

    /** Whether the floating buttons should use this palette (stored with the BPM display settings) */
    fun buttonsEnabled(context: Context): Boolean =
        BpmController.prefs(context).getBoolean(context.getString(R.string.key_bpm_lcd_buttons), false)
}
