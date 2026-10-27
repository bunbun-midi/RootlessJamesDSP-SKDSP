package me.timschneeberger.rootlessjamesdsp.speedshift

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingBubble

/**
 * Floating button that controls the speed shifter:
 *  - tap: toggle slow-down on/off
 *  - swipe up/down: change the speed by the configured step (the multiple is shown next to the button);
 *    in target tempo mode this ends target mode and continues manually from the current speed
 *  - long-press and drag: move the button
 */
class SpeedShiftBubble(context: Context) : FloatingBubble(
    context,
    SpeedShiftController.prefs(context),
    positionKey = "speed_shift_bubble",
    defaultYFraction = 0.35f
), SpeedShiftController.Listener {

    override val supportsVerticalSwipe = true

    private var swipeStartSpeed = SpeedShiftController.DEFAULT_SPEED
    private var lingerLabel = false

    private val hideLabelRunnable = Runnable {
        lingerLabel = false
        render()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            if (isShown && SpeedShiftController.isActive)
                handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun show() {
        super.show()
        if (!isShown)
            return
        SpeedShiftController.addListener(this)
        if (SpeedShiftController.isActive)
            handler.post(tickRunnable)
    }

    override fun hide() {
        SpeedShiftController.removeListener(this)
        super.hide()
    }

    override fun onSpeedShiftStateChanged() {
        render()
        handler.removeCallbacks(tickRunnable)
        if (isShown && SpeedShiftController.isActive)
            handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    override fun onTap() = SpeedShiftController.toggle()

    override fun onSwipeStart() {
        handler.removeCallbacks(hideLabelRunnable)
        if (SpeedShiftController.targetBpm > 0f) {
            // Take over manually from the speed target mode is using right now
            SpeedShiftController.setSpeed(context, SpeedShiftController.appliedSpeed)
            SpeedShiftController.setTargetBpm(context, 0f)
        }
        swipeStartSpeed = SpeedShiftController.speed
    }

    override fun onSwipe(steps: Int): Boolean =
        SpeedShiftController.setSpeed(context, swipeStartSpeed + steps * SpeedShiftController.speedStep)

    override fun onSwipeEnd() {
        // Keep showing the new multiple for a moment
        lingerLabel = true
        handler.removeCallbacks(hideLabelRunnable)
        handler.postDelayed(hideLabelRunnable, LABEL_LINGER_MS)
    }

    override fun render() {
        val active = SpeedShiftController.isActive
        // In target tempo mode the speed is chosen continuously by the audio thread
        val shown = if (active && SpeedShiftController.targetBpm > 0f) SpeedShiftController.appliedSpeed
            else SpeedShiftController.speed
        val speedText = SpeedShiftController.formatSpeed(shown)

        setAppearance(
            icon = R.drawable.ic_slow_motion_24dp,
            value = speedText,
            backgroundAttr = if (active) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorSecondaryContainer,
            foregroundAttr = if (active) com.google.android.material.R.attr.colorOnPrimary
                else com.google.android.material.R.attr.colorOnSecondaryContainer,
            active = active,
            alpha = if (active) 1f else 0.85f,
            contentDescription = if (active)
                context.getString(R.string.speed_shift_bubble_on_desc, speedText)
            else
                context.getString(R.string.speed_shift_bubble_off_desc, speedText)
        )

        // Side label: the multiple while swiping, otherwise how far playback lags behind
        val backlog = SpeedShiftController.backlogSeconds
        setLabel(when {
            isSwiping || lingerLabel -> speedText
            active && backlog >= 1f -> context.getString(R.string.speed_shift_behind,
                SpeedShiftController.formatDuration(backlog))
            else -> null
        })
    }

    companion object {
        private const val TICK_INTERVAL_MS = 500L
        private const val LABEL_LINGER_MS = 1200L
    }
}
