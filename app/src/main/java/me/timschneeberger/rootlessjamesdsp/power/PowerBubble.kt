package me.timschneeberger.rootlessjamesdsp.power

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingBubble

/**
 * Floating on/off button for audio processing (same as the power button in the app and the
 * quick settings tile). Tap toggles; long-press and drag moves it.
 */
class PowerBubble(context: Context) : FloatingBubble(
    context,
    PowerBubbleManager.prefs(context),
    positionKey = "power_bubble",
    defaultYFraction = 0.2f
) {
    override fun onTap() = PowerBubbleManager.toggle()

    override fun render() {
        val on = PowerBubbleManager.isEngineOn()
        setAppearance(
            icon = R.drawable.ic_twotone_power_settings_24dp,
            value = context.getString(if (on) R.string.power_bubble_on else R.string.power_bubble_off),
            backgroundAttr = if (on) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorSecondaryContainer,
            foregroundAttr = if (on) com.google.android.material.R.attr.colorOnPrimary
                else com.google.android.material.R.attr.colorOnSecondaryContainer,
            active = on,
            alpha = if (on) 1f else 0.85f,
            contentDescription = context.getString(
                if (on) R.string.power_bubble_on_desc else R.string.power_bubble_off_desc
            )
        )
        setLabel(null)
    }

    /** Re-reads the engine state */
    fun refresh() {
        if (isShown)
            render()
    }
}
