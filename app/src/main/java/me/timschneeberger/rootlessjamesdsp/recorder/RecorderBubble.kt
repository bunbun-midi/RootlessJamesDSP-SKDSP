package me.timschneeberger.rootlessjamesdsp.recorder

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingBubble

/**
 * Floating record button: tap starts/stops recording (elapsed time is shown on the button),
 * long-press and drag moves it.
 */
class RecorderBubble(context: Context) : FloatingBubble(
    context,
    RecorderController.prefs(context),
    positionKey = "recorder_bubble",
    defaultYFraction = 0.5f
), RecorderController.Listener {

    private var showSaved = false

    private val hideSavedRunnable = Runnable {
        showSaved = false
        render()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            if (isShown && RecorderController.isRecording)
                handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun show() {
        super.show()
        if (!isShown)
            return
        RecorderController.addListener(this)
        if (RecorderController.isRecording)
            handler.post(tickRunnable)
    }

    override fun hide() {
        RecorderController.removeListener(this)
        super.hide()
    }

    override fun onRecorderStateChanged() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(hideSavedRunnable)
        if (RecorderController.isRecording) {
            showSaved = false
            handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        } else if (RecorderController.lastSavedName != null) {
            showSaved = true
            handler.postDelayed(hideSavedRunnable, SAVED_LINGER_MS)
        }
        render()
    }

    override fun onTap() = RecorderController.toggle()

    override fun render() {
        val recording = RecorderController.isRecording
        val elapsed = RecorderController.formatElapsed(RecorderController.elapsedSeconds)

        if (recording) {
            setAppearance(
                icon = R.drawable.ic_stop_24dp,
                value = elapsed,
                backgroundAttr = com.google.android.material.R.attr.colorError,
                foregroundAttr = com.google.android.material.R.attr.colorOnError,
                active = true,
                contentDescription = context.getString(R.string.recorder_bubble_recording_desc, elapsed)
            )
        } else {
            setAppearance(
                icon = R.drawable.ic_record_24dp,
                value = context.getString(R.string.recorder_idle_value),
                backgroundAttr = com.google.android.material.R.attr.colorSecondaryContainer,
                foregroundAttr = com.google.android.material.R.attr.colorOnSecondaryContainer,
                active = false,
                alpha = 0.85f,
                contentDescription = context.getString(R.string.recorder_bubble_idle_desc)
            )
        }

        setLabel(if (!recording && showSaved) context.getString(R.string.recorder_saved) else null)
    }

    companion object {
        private const val TICK_INTERVAL_MS = 500L
        private const val SAVED_LINGER_MS = 2500L
    }
}
