package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.interop.SpeedShifter
import me.timschneeberger.rootlessjamesdsp.interop.BpmDetector
import me.timschneeberger.rootlessjamesdsp.bpm.BpmController
import me.timschneeberger.rootlessjamesdsp.bpm.BpmDisplay
import me.timschneeberger.rootlessjamesdsp.keyboard.KeyboardController
import me.timschneeberger.rootlessjamesdsp.keyboard.KeyboardPanel
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.recorder.RecorderBubble
import me.timschneeberger.rootlessjamesdsp.recorder.RecorderController
import me.timschneeberger.rootlessjamesdsp.recorder.AudioFileRecorder
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftBubble
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftController
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin


@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    // System services
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Media projection token
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    // Processing
    @Volatile
    private var recreateRecorderRequested = false
    private var recorderThread: Thread? = null
    private lateinit var engine: JamesDspLocalEngine
    private val isRunning: Boolean
        get() = recorderThread != null

    // Session management
    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0
    @Volatile
    private var notificationSessions: Array<IEffectSession> = emptyArray()

    // Idle detection
    @Volatile
    private var isProcessorIdle = false
    @Volatile
    private var suspendOnIdle = false

    // Exclude restricted apps flag
    private var excludeRestrictedSessions = false

    // Termination flags
    @Volatile
    private var isProcessorDisposing = false
    @Volatile
    private var isServiceDisposing = false
    // Identifies the current audio thread. A thread whose number is no longer current must stop,
    // even if it outlived stopRecording()'s join timeout and the flags above were reset by a restart.
    @Volatile
    private var processorGeneration = 0

    // Speed shift (floating button)
    private var speedShiftBubble: SpeedShiftBubble? = null

    // Recorder effect block (floating button)
    private var recorderBubble: RecorderBubble? = null
    // Running recording session: Recorder A and/or B, started & stopped on the same audio block
    private var recordingSession: List<AudioFileRecorder> = emptyList()
    private var sessionStopping = false
    private val sessionResults = mutableListOf<AudioFileRecorder.Result>()

    // Floating BPM display and piano keyboard
    private var bpmDisplay: BpmDisplay? = null
    private var keyboardPanel: KeyboardPanel? = null

    // Shared preferences
    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    override fun onCreate() {
        super.onCreate()

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup session manager
        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        // Setup core engine
        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()

        // Setup general-purpose broadcast receiver
        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SPEED_SHIFT_REFRESH)
        filter.addAction(Constants.ACTION_RECORDER_REFRESH)
        filter.addAction(Constants.ACTION_OVERLAYS_REFRESH)
        registerLocalReceiver(broadcastReceiver, filter)

        // Setup shared preferences
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        // Speed shift: always start in real time; show the floating button if enabled
        SpeedShiftController.setActive(false)
        SpeedShiftController.loadFromPreferences(this)
        SpeedShiftController.prefs(this).registerOnSharedPreferenceChangeListener(speedShiftPreferencesListener)
        updateSpeedShiftBubble()

        // Recorder: floating record button if the block is enabled
        RecorderController.backend = recorderBackend
        RecorderController.prefs(this).registerOnSharedPreferenceChangeListener(recorderPreferencesListener)
        RecorderController.prefsB(this).registerOnSharedPreferenceChangeListener(recorderPreferencesListener)
        updateRecorderBubble()

        // BPM display & piano keyboard
        KeyboardController.backend = keyboardBackend
        BpmController.prefs(this).registerOnSharedPreferenceChangeListener(overlayPreferencesListener)
        KeyboardController.prefs(this).registerOnSharedPreferenceChangeListener(overlayPreferencesListener)
        updateBpmDisplay()
        updateKeyboardPanel()

        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)

        // No need to recreate in this stage
        recreateRecorderRequested = false

        // Launch foreground service
        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        Timber.d("onStartCommand")

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START -> {
                Timber.d("Starting service")
            }
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) {
            return START_NOT_STICKY
        }

        // Cancel outdated notifications
        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        // Setup media projection
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)

        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                mediaProjectionStartIntent!!
            )
        }
        catch (ex: Exception) {
            Timber.e("Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            Timber.e(ex)
            null
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true

        // Finish a running file recording before the engine (which feeds it) goes away
        RecorderController.prefs(this).unregisterOnSharedPreferenceChangeListener(recorderPreferencesListener)
        RecorderController.prefsB(this).unregisterOnSharedPreferenceChangeListener(recorderPreferencesListener)
        BpmController.prefs(this).unregisterOnSharedPreferenceChangeListener(overlayPreferencesListener)
        KeyboardController.prefs(this).unregisterOnSharedPreferenceChangeListener(overlayPreferencesListener)
        if (KeyboardController.backend === keyboardBackend)
            KeyboardController.backend = null
        bpmDisplay?.hide()
        bpmDisplay = null
        BpmController.isEnabled = false
        keyboardPanel?.hide()
        keyboardPanel = null
        if (RecorderController.backend === recorderBackend)
            RecorderController.backend = null
        recorderBubble?.hide()
        recorderBubble = null
        val session = recordingSession
        stopFileRecording()
        session.forEach { it.awaitFinished(2000) }

        // Stop recording and release engine
        stopRecording()
        engine.close()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Notify app about service termination
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Unregister receivers and release resources
        unregisterLocalReceiver(broadcastReceiver)
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection = null

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        SpeedShiftController.prefs(this).unregisterOnSharedPreferenceChangeListener(speedShiftPreferencesListener)
        speedShiftBubble?.hide()
        speedShiftBubble = null
        SpeedShiftController.setActive(false)

        stopSelf()
        super.onDestroy()
    }

    // Preferences listener
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        loadFromPreferences(key)
    }

    // Speed shift preferences listener (kept as a field: SharedPreferences only holds weak references)
    private val speedShiftPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        when (key) {
            getString(R.string.key_speed_shift_bubble) -> updateSpeedShiftBubble()
            getString(R.string.key_speed_shift_speed),
            getString(R.string.key_speed_shift_step) -> SpeedShiftController.loadFromPreferences(this)
        }
    }

    // Show or hide the floating speed shift button
    private fun updateSpeedShiftBubble() {
        if (isServiceDisposing)
            return

        val enabled = SpeedShiftController.prefs(this)
            .getBoolean(getString(R.string.key_speed_shift_bubble), false)
        if (enabled && Settings.canDrawOverlays(this)) {
            if (speedShiftBubble == null) {
                speedShiftBubble = try {
                    SpeedShiftBubble(this).also { it.show() }
                } catch (ex: Exception) {
                    Timber.e("Failed to create speed shift bubble")
                    Timber.e(ex)
                    null
                }
            }
        } else {
            speedShiftBubble?.hide()
            speedShiftBubble = null
            // Without the button there is no way to turn it off again
            SpeedShiftController.setActive(false)
        }
    }

    // BPM display / keyboard preferences listener (kept as a field: weak references in SharedPreferences)
    private val overlayPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        when (key) {
            getString(R.string.key_bpm_enable) -> updateBpmDisplay()
            getString(R.string.key_keyboard_enable) -> updateKeyboardPanel()
            getString(R.string.key_keyboard_target) -> KeyboardController.setCurrentInterval(currentKeyboardTargetInterval())
        }
    }

    private fun updateBpmDisplay() {
        if (isServiceDisposing)
            return
        val enabled = BpmController.prefs(this).getBoolean(getString(R.string.key_bpm_enable), false)
        if (enabled && Settings.canDrawOverlays(this)) {
            if (bpmDisplay == null)
                bpmDisplay = BpmDisplay(this).also { it.show() }
        } else {
            bpmDisplay?.hide()
            bpmDisplay = null
        }
        // The detector only runs while the display is visible
        BpmController.isEnabled = bpmDisplay?.isShown == true
    }

    private fun updateKeyboardPanel() {
        if (isServiceDisposing)
            return
        val enabled = KeyboardController.prefs(this).getBoolean(getString(R.string.key_keyboard_enable), false)
        if (enabled && Settings.canDrawOverlays(this)) {
            if (keyboardPanel == null) {
                KeyboardController.setCurrentInterval(currentKeyboardTargetInterval())
                keyboardPanel = KeyboardPanel(this).also { it.show() }
            }
        } else {
            keyboardPanel?.hide()
            keyboardPanel = null
        }
    }

    /** Interval of the pitch shifter the keyboard controls (null if that shifter is off) */
    private fun currentKeyboardTargetInterval(): Int? {
        val hq = KeyboardController.isHqTarget(this)
        val prefs = getSharedPreferences(if (hq) Constants.PREF_PITCH_HQ else Constants.PREF_PITCH, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(getString(if (hq) R.string.key_pitch_hq_enable else R.string.key_pitch_enable), false)
        if (!enabled)
            return null
        return prefs.getFloat(getString(if (hq) R.string.key_pitch_hq_semitones else R.string.key_pitch_semitones), 0f)
            .let { kotlin.math.round(it).toInt() }
    }

    private val keyboardBackend = KeyboardController.Backend { semitones ->
        val hq = KeyboardController.isHqTarget(this)
        engine.setPitchShiftGlide(hq, semitones.toFloat(), KeyboardController.portamentoMs(this))
        // Store it like the DSP screen does, so the pitch card and presets show the played interval.
        // (The engine then sees the same value and lets the glide finish.)
        getSharedPreferences(if (hq) Constants.PREF_PITCH_HQ else Constants.PREF_PITCH, Context.MODE_PRIVATE).edit()
            .putBoolean(getString(if (hq) R.string.key_pitch_hq_enable else R.string.key_pitch_enable), true)
            .putFloat(getString(if (hq) R.string.key_pitch_hq_semitones else R.string.key_pitch_semitones), semitones.toFloat())
            .apply()
    }

    // Recorder preferences listener (kept as a field: SharedPreferences only holds weak references)
    private val recorderPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        if (key == getString(R.string.key_recorder_enable))
            updateRecorderBubble()
    }

    private val recorderBackend = object : RecorderController.Backend {
        override fun startRecording() = startFileRecording()
        override fun stopRecording() = stopFileRecording()
    }

    // Show or hide the floating record button (shared by Recorder A and B)
    private fun updateRecorderBubble() {
        if (isServiceDisposing)
            return

        val key = getString(R.string.key_recorder_enable)
        val enabledA = RecorderController.prefs(this).getBoolean(key, false)
        val enabledB = RecorderController.prefsB(this).getBoolean(key, false)
        if ((enabledA || enabledB) && Settings.canDrawOverlays(this)) {
            if (recorderBubble == null) {
                recorderBubble = try {
                    RecorderBubble(this).also { it.show() }
                } catch (ex: Exception) {
                    Timber.e("Failed to create recorder bubble")
                    Timber.e(ex)
                    null
                }
            }
        } else {
            recorderBubble?.hide()
            recorderBubble = null
        }

        // Switching both recorder blocks off ends a running recording
        if (!enabledA && !enabledB)
            stopFileRecording()
    }

    private fun createFileRecorder(prefs: SharedPreferences, baseName: String) = AudioFileRecorder(
        this,
        engine.sampleRate.toInt(),
        AudioFileRecorder.Format.fromValue(prefs.getString(getString(R.string.key_recorder_format), null)),
        variableBitrate = prefs.getString(getString(R.string.key_recorder_bitrate_mode), "vbr") != "cbr",
        quality = prefs.getFloat(getString(R.string.key_recorder_quality), 7f).toInt(),
        bitrateKbps = prefs.getFloat(getString(R.string.key_recorder_bitrate), 192f).toInt(),
        baseName = baseName,
        requestStop = { stopFileRecording() },
        onFinished = { finished, result -> onFileRecordingFinished(finished, result) }
    )

    /** Starts every enabled recorder (A and/or B) on the same audio block */
    private fun startFileRecording() {
        if (recordingSession.isNotEmpty() || isServiceDisposing)
            return

        val key = getString(R.string.key_recorder_enable)
        val prefsA = RecorderController.prefs(this)
        val prefsB = RecorderController.prefsB(this)
        val stamp = "SK DSP " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date())
        val recorderA = if (prefsA.getBoolean(key, false)) createFileRecorder(prefsA, "$stamp A") else null
        val recorderB = if (prefsB.getBoolean(key, false)) createFileRecorder(prefsB, "$stamp B") else null
        val recorders = listOfNotNull(recorderA, recorderB)
        if (recorders.isEmpty())
            return

        val prepared = mutableListOf<AudioFileRecorder>()
        try {
            recorders.forEach {
                it.prepare()
                prepared += it
            }
            // One call attaches both taps, so both files begin with the same audio block
            if (!engine.setRecorderTaps(recorderA?.tapHandle ?: 0L, recorderB?.tapHandle ?: 0L))
                throw java.io.IOException("Audio engine not ready")
        } catch (ex: Exception) {
            Timber.e("Failed to start recording")
            Timber.e(ex)
            prepared.forEach { it.abort() }
            toast(getString(R.string.recorder_failed_toast, ex.message ?: ex.javaClass.simpleName))
            return
        }

        recorders.forEach { it.begin() }
        recordingSession = recorders
        sessionStopping = false
        sessionResults.clear()
        RecorderController.onStarted()
    }

    /** Stops all recorders of the session on the same audio block; files complete asynchronously */
    private fun stopFileRecording() {
        if (recordingSession.isEmpty() || sessionStopping)
            return
        sessionStopping = true
        // Detach first: after this returns the audio thread no longer writes into the taps
        engine.setRecorderTaps(0L, 0L)
        recordingSession.forEach { it.finish() }
    }

    private fun onFileRecordingFinished(recorder: AudioFileRecorder, result: AudioFileRecorder.Result) {
        if (recorder !in recordingSession)
            return
        sessionResults += result
        if (sessionResults.size < recordingSession.size)
            return

        // All files of the session are complete
        val results = sessionResults.toList()
        recordingSession = emptyList()
        sessionStopping = false
        sessionResults.clear()

        val saved = results.filterIsInstance<AudioFileRecorder.Result.Saved>()
        val failed = results.filterIsInstance<AudioFileRecorder.Result.Failed>()
        RecorderController.onStopped(saved.firstOrNull()?.name)

        failed.firstOrNull()?.let {
            toast(getString(R.string.recorder_failed_toast, it.message))
        }
        if (saved.isNotEmpty()) {
            val dropped = saved.maxOf { it.droppedSeconds }
            if (dropped >= 0.05f)
                toast(getString(R.string.recorder_dropped_toast,
                    String.format(java.util.Locale.getDefault(), "%.1f s", dropped)))
            else
                toast(getString(R.string.recorder_saved_toast, saved.joinToString(", ") { it.name }), false)
        }
    }

    // Projection termination callback
    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing) {
                // Planned shutdown
                return
            }

            if(preferencesVar.get<Boolean>(R.string.key_is_activity_active)) {
                // Activity in foreground, toast too disruptive
                return
            }

            Timber.w("Capture permission revoked. Stopping service.")

            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> {
                    engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                    // A WAV file has one sample rate; finish the current file if it changed
                    if (recordingSession.any { it.sampleRate != engine.sampleRate.toInt() })
                        stopFileRecording()
                }
                ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences()
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
                Constants.ACTION_SPEED_SHIFT_REFRESH -> updateSpeedShiftBubble()
                Constants.ACTION_RECORDER_REFRESH -> updateRecorderBubble()
                Constants.ACTION_OVERLAYS_REFRESH -> {
                    updateBpmDisplay()
                    updateKeyboardPanel()
                }
            }
        }
    }

    // Session loss listener
    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                // Check if retry count exceeded
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    // Retry
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopSelf()
            }
        }
    }

    // Session change listener
    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.size == 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")

            notificationSessions = sessionList.values.toTypedArray()

            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                notificationSessions
            )
        }
    }

    // App problem listener
    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                // Determine if we should redirect instantly, or push a non-intrusive notification
                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else
                    ServiceNotificationHelper.pushAppIssueNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent, uid)

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopSelf()
            }
        }
    }

    // Session policy change listener
    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(sessionList: HashMap<String, SessionRecordingPolicyEntry>, isMinorUpdate: Boolean) {
            if(!this@RootlessAudioProcessorService.excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")

                requestAudioRecordRecreation()
            }
        }
    }

    // Request recreation of the AudioRecord object to update AudioPlaybackRecordingConfiguration
    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }

        recreateRecorderRequested = true
    }

    // Start recording thread
    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        // Sanity check
        if (!hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        // Load preferences
        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
        )
        val bufferSize = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt()
        val bufferSizeBytes = when (encoding) {
            AudioEncoding.PcmFloat -> bufferSize * Float.SIZE_BYTES
            else -> bufferSize * Short.SIZE_BYTES
        }
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val sampleRate = clamp(determineSamplingRate(), 44100, 48000)

        Timber.i("Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                "Buffer size: $bufferSize; Buffer size (bytes): $bufferSizeBytes ; " +
                "HAL buffer size (bytes): ${determineBufferSize()}")

        // Buffer layout
        //  - The DSP always processes blocks of exactly `bufferSize` samples (interleaved stereo).
        //    libjamesdsp rebuilds filters/convolutions whenever the block size changes, so we
        //    never pass partial blocks to it.
        //  - The capture and playback buffers hold several blocks. With only one block of
        //    headroom (old behaviour), the AudioTrack ran dry while the next block was being
        //    captured & processed, causing periodic underruns (clicks/pops).
        //  - After (re)starting playback we pre-fill the track with a little silence so that
        //    processing time and scheduling jitter never empty the playback queue.
        val frameSizeBytes = 2 /* channels */ * (bufferSizeBytes / bufferSize)
        val blockFrames = bufferSize / 2
        val primeFrames = maxOf(blockFrames / 2, sampleRate / 50 /* 20 ms */)
        val recordBufferBytes = maxOf(
            bufferSizeBytes * RECORD_BUFFER_BLOCKS,
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encodingFormat)
        )
        val trackBufferBytes = maxOf(
            (primeFrames + blockFrames * TRACK_BUFFER_BLOCKS) * frameSizeBytes,
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encodingFormat)
        )

        Timber.i("Block: $blockFrames frames; record buffer: $recordBufferBytes bytes; " +
                "track buffer: $trackBufferBytes bytes; pre-fill: $primeFrames frames")

        // Create recorder and track
        var recorder: AudioRecord
        var track: AudioTrack
        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, recordBufferBytes)
            track = buildAudioTrack(encodingFormat, sampleRate, trackBufferBytes)
        }
        catch(ex: Exception) {
            Timber.e("Failed to create initial audio record/track")
            Timber.e(ex)
            stopSelf()
            return
        }

        if(engine.sampleRate.toInt() != sampleRate) {
            Timber.d("Sampling rate changed to ${sampleRate}Hz")
            engine.sampleRate = sampleRate.toFloat()
        }

        // TODO Move all audio-related code to C++
        val generation = ++processorGeneration
        recorderThread = Thread {
            // Audio threads must not be preempted by regular app/background work
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            catch (ex: Exception) {
                Timber.w("Failed to raise audio thread priority")
                Timber.w(ex)
            }

            // Speed shift: the shifter is created when slow-down is switched on and destroyed
            // when it is switched off (this also frees the backlog of captured audio)
            var speedShifter = 0L
            // BPM display: tempo detector on the captured audio (created while the display is shown)
            var bpmDetector = 0L

            try {
                // Rebuilding the processing pipeline must not overwrite the current
                // session-aware notification with a false "idle" state.
                ServiceNotificationHelper.pushServiceNotification(
                    applicationContext,
                    notificationSessions,
                )

                val floatBuffer = FloatArray(bufferSize)
                val floatOutBuffer = FloatArray(bufferSize)
                val shortBuffer = ShortArray(bufferSize)
                val shortOutBuffer = ShortArray(bufferSize)
                val floatSilence = FloatArray(primeFrames * 2)
                val shortSilence = ShortArray(primeFrames * 2)
                var lastUnderrunCount = 0

                var speedShiftWasActive = false
                val shiftInBuffer = FloatArray(bufferSize)
                // The last captured audio (whole blocks, ring buffer). When slow-down is switched
                // on, it primes the stretcher so its output starts without a gap.
                val historyBlocks = (SPEED_SHIFT_PRIME_FRAMES + blockFrames - 1) / blockFrames
                val historyRing = FloatArray(historyBlocks * bufferSize)
                val historyLinear = FloatArray(historyBlocks * bufferSize)
                var historyNext = 0   // block index written next
                var historyCount = 0  // blocks filled so far
                // Switching on: short splice (the stretcher output is aligned to the dry signal)
                val spliceFrames = minOf(blockFrames, sampleRate / 100 /* 10 ms */)
                val bpmBuffer = FloatArray(bufferSize)
                val shiftOutBuffer = FloatArray(bufferSize)
                // While idle, keep playing until the slowed-down backlog has been played (System.nanoTime)
                var idleSuspendAt = 0L

                fun releaseSpeedShifter() {
                    if (speedShifter != 0L) {
                        SpeedShifter.nativeDestroy(speedShifter)
                        speedShifter = 0L
                    }
                    speedShiftWasActive = false
                    SpeedShiftController.backlogSeconds = 0f
                }

                fun recordHistory(dry: FloatArray) {
                    System.arraycopy(dry, 0, historyRing, historyNext * bufferSize, bufferSize)
                    historyNext = (historyNext + 1) % historyBlocks
                    if (historyCount < historyBlocks)
                        historyCount++
                }

                /** Copies the recorded history, oldest first, to historyLinear; returns its length in frames */
                fun linearizeHistory(): Int {
                    val oldest = (historyNext - historyCount + historyBlocks) % historyBlocks
                    for (b in 0 until historyCount) {
                        val src = ((oldest + b) % historyBlocks) * bufferSize
                        System.arraycopy(historyRing, src, historyLinear, b * bufferSize, bufferSize)
                    }
                    return historyCount * blockFrames
                }

                /**
                 * Replaces the captured block with the speed-shifted signal while slow-down is on.
                 * Switching on splices to the (pre-filled, aligned) stretcher output over 10 ms;
                 * switching off crossfades back to real time over one block (equal power).
                 */
                fun applySpeedShift() {
                    val active = SpeedShiftController.isActive

                    // Captured (dry) block as float
                    val dry = if (encoding == AudioEncoding.PcmShort) {
                        for (i in 0 until bufferSize)
                            shiftInBuffer[i] = shortBuffer[i] * (1f / 32768f)
                        shiftInBuffer
                    } else floatBuffer

                    if (!active && !speedShiftWasActive) {
                        recordHistory(dry)
                        return
                    }

                    val switchingOn = active && speedShifter == 0L
                    if (switchingOn) {
                        speedShifter = SpeedShifter.nativeCreate(sampleRate)
                        if (speedShifter == 0L) {
                            Timber.e("Failed to create speed shifter")
                            SpeedShiftController.setActive(false)
                            recordHistory(dry)
                            return
                        }
                        SpeedShifter.nativeSetSpeed(speedShifter, SpeedShiftController.speed)
                        val historyFrames = linearizeHistory()
                        if (historyFrames > 0)
                            SpeedShifter.nativePrime(speedShifter, historyLinear, historyFrames, dry, blockFrames, spliceFrames)
                    }
                    recordHistory(dry)
                    if (speedShifter == 0L)
                        return

                    SpeedShifter.nativeSetSpeed(speedShifter, SpeedShiftController.speed)
                    SpeedShifter.nativeProcess(speedShifter, dry, shiftOutBuffer, blockFrames)

                    if (switchingOn) {
                        // Equal-gain splice: the shifted audio is aligned to the dry signal
                        for (frame in 0 until spliceFrames) {
                            val g = 0.5f - 0.5f * cos((frame + 0.5f) / spliceFrames * PI.toFloat())
                            for (ch in 0..1) {
                                val i = frame * 2 + ch
                                shiftOutBuffer[i] = shiftOutBuffer[i] * g + dry[i] * (1f - g)
                            }
                        }
                    } else if (active != speedShiftWasActive) {
                        for (frame in 0 until blockFrames) {
                            val angle = (frame + 0.5f) / blockFrames * (PI.toFloat() / 2f)
                            val rising = sin(angle)
                            val falling = cos(angle)
                            val wet = if (active) rising else falling
                            val dryGain = if (active) falling else rising
                            for (ch in 0..1) {
                                val i = frame * 2 + ch
                                shiftOutBuffer[i] = shiftOutBuffer[i] * wet + dry[i] * dryGain
                            }
                        }
                    }

                    if (encoding == AudioEncoding.PcmShort) {
                        for (i in 0 until bufferSize)
                            shortBuffer[i] = (shiftOutBuffer[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                    } else {
                        System.arraycopy(shiftOutBuffer, 0, floatBuffer, 0, bufferSize)
                    }

                    if (active) {
                        SpeedShiftController.backlogSeconds =
                            SpeedShifter.nativeBacklogFrames(speedShifter).toFloat() / sampleRate
                        speedShiftWasActive = true
                    } else {
                        // Crossfade back to real time done; drop the backlog
                        releaseSpeedShifter()
                    }
                }

                fun startPlayback() {
                    track.play()
                    // Pre-fill to absorb processing time & scheduling jitter
                    if(encoding == AudioEncoding.PcmShort)
                        track.write(shortSilence, 0, shortSilence.size, AudioTrack.WRITE_BLOCKING)
                    else
                        track.write(floatSilence, 0, floatSilence.size, AudioTrack.WRITE_BLOCKING)
                    lastUnderrunCount = track.underrunCount
                }

                while (!isProcessorDisposing && generation == processorGeneration) {
                    if(recreateRecorderRequested) {
                        recreateRecorderRequested = false
                        Timber.d("Recreating recorder without stopping thread...")

                        // Suspend track, release recorder
                        recorder.stop()
                        track.stop()
                        recorder.release()


                        if (mediaProjection == null) {
                            Timber.e("Media projection handle is null, stopping service")
                            stopSelf()
                            return@Thread
                        }

                        // Recreate recorder with new AudioPlaybackRecordingConfiguration
                        recorder = buildAudioRecord(encodingFormat, sampleRate, recordBufferBytes)
                        historyCount = 0
                        Timber.d("Recorder recreated")
                    }

                    // Suspend core while idle
                    // (with slow-down on: only after the slowed-down audio still pending has been played)
                    val suspendNow = if(isProcessorIdle && suspendOnIdle) {
                        val now = System.nanoTime()
                        if(idleSuspendAt == 0L) {
                            val drainSeconds = if(speedShifter != 0L)
                                SpeedShiftController.backlogSeconds / SpeedShiftController.speed + 0.5f
                            else 0f
                            idleSuspendAt = now + (drainSeconds * 1e9f).toLong() + 1
                        }
                        now >= idleSuspendAt
                    } else {
                        idleSuspendAt = 0L
                        false
                    }

                    if(suspendNow)
                    {
                        // Pending slowed-down audio is stale once playback stopped
                        releaseSpeedShifter()
                        historyCount = 0

                        if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            recorder.stop()
                        if(track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState != AudioTrack.PLAYSTATE_STOPPED)
                            track.stop()

                        try {
                            Thread.sleep(50)
                        }
                        catch(e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    // Resume recorder if suspended
                    if(recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                        recorder.startRecording()
                    }
                    // Resume track if suspended
                    if(track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        startPlayback()
                    }

                    // Capture exactly one full block, then process it
                    val readResult = if(encoding == AudioEncoding.PcmShort)
                        readFully(recorder, shortBuffer)
                    else
                        readFully(recorder, floatBuffer)

                    if(readResult < 0) {
                        // Don't process & replay stale data; rebuild the recorder instead
                        Timber.e("AudioRecord.read failed ($readResult); recreating recorder")
                        recreateRecorderRequested = true
                        continue
                    }
                    if(readResult < bufferSize) {
                        // Recording was stopped while reading (e.g. recorder recreation)
                        continue
                    }

                    // Tempo detection on the original (not slowed down) audio
                    if (BpmController.isEnabled) {
                        if (bpmDetector == 0L)
                            bpmDetector = BpmDetector.nativeCreate(sampleRate)
                        if (bpmDetector != 0L) {
                            if (BpmController.consumeResetRequest())
                                BpmDetector.nativeReset(bpmDetector)
                            val bpmInput = if (encoding == AudioEncoding.PcmShort) {
                                for (i in 0 until bufferSize)
                                    bpmBuffer[i] = shortBuffer[i] * (1f / 32768f)
                                bpmBuffer
                            } else floatBuffer
                            BpmDetector.nativeProcess(bpmDetector, bpmInput, blockFrames)
                            BpmController.publish(BpmDetector.nativeBpm(bpmDetector), BpmDetector.nativeConfidence(bpmDetector))
                        }
                    } else if (bpmDetector != 0L) {
                        BpmDetector.nativeDestroy(bpmDetector)
                        bpmDetector = 0L
                        BpmController.publish(0f, 0f)
                    }

                    // Slow down the captured audio before the effect chain, if enabled
                    applySpeedShift()

                    val writeResult = if(encoding == AudioEncoding.PcmShort) {
                        engine.processInt16(shortBuffer, shortOutBuffer)
                        track.write(shortOutBuffer, 0, shortOutBuffer.size, AudioTrack.WRITE_BLOCKING)
                    }
                    else {
                        engine.processFloat(floatBuffer, floatOutBuffer)
                        track.write(floatOutBuffer, 0, floatOutBuffer.size, AudioTrack.WRITE_BLOCKING)
                    }

                    if(writeResult == AudioTrack.ERROR_DEAD_OBJECT) {
                        // The output was invalidated (e.g. routing change); create a new track
                        Timber.w("AudioTrack is dead; recreating track")
                        track.release()
                        track = buildAudioTrack(encodingFormat, sampleRate, trackBufferBytes)
                        continue
                    }
                    else if(writeResult < 0) {
                        Timber.e("AudioTrack.write failed ($writeResult)")
                    }

                    // Diagnostics: report playback underruns (audible as clicks/dropouts)
                    val underruns = track.underrunCount
                    if(underruns > lastUnderrunCount) {
                        Timber.w("Playback underrun detected (total: $underruns)")
                        lastUnderrunCount = underruns
                    }
                }
            } catch (e: IOException) {
                Timber.w(e)
                // ignore
            } catch (e: Exception) {
                Timber.e("Exception in recorderThread raised")
                Timber.e(e)
                stopSelf()
            } finally {
                if (bpmDetector != 0L)
                    BpmDetector.nativeDestroy(bpmDetector)
                if (speedShifter != 0L) {
                    SpeedShifter.nativeDestroy(speedShifter)
                    SpeedShiftController.backlogSeconds = 0f
                }

                // Clean up recorder and track
                if(recorder.state != AudioRecord.STATE_UNINITIALIZED) {
                    recorder.stop()
                }
                if(track.state != AudioTrack.STATE_UNINITIALIZED) {
                    track.stop()
                }

                recorder.release()
                track.release()
            }
        }
        recorderThread!!.start()
    }

    /**
     * Reads until [buffer] is completely filled.
     * @return number of samples read, which is less than the buffer size if recording stopped,
     *         or a negative error code.
     */
    private fun readFully(recorder: AudioRecord, buffer: FloatArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val result = recorder.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
            if (result < 0)
                return result
            if (result == 0 && recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                break
            offset += result
        }
        return offset
    }

    private fun readFully(recorder: AudioRecord, buffer: ShortArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val result = recorder.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
            if (result < 0)
                return result
            if (result == 0 && recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                break
            offset += result
        }
        return offset
    }

    // Terminate recording thread
    fun stopRecording() {
        val thread = recorderThread ?: return
        isProcessorDisposing = true
        // Retire this thread for good: a restart resets isProcessorDisposing, and a thread that
        // was still blocked in read()/write() would otherwise keep running next to the new one
        // (two copies of the audio playing slightly apart = echo).
        processorGeneration++
        thread.interrupt()
        thread.join(2000)
        if (thread.isAlive)
            Timber.w("stopRecording: audio thread did not stop within 2 s; it exits after its current block")
        recorderThread = null
    }

    // Hard restart recording thread
    fun restartRecording() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("restartRecording: service or processor already disposing")
            return
        }

        stopRecording()
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        val frameSizeInBytes: Int = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            2 /* channels */ * 2 /* bytes */
        } else {
            2 /* channels */ * 4 /* bytes */
        }

        val bufferSize = if (((bufferSizeBytes % frameSizeInBytes) != 0 || bufferSizeBytes < 1)) {
            Timber.e("Invalid audio buffer size $bufferSizeBytes")
            128 * (bufferSizeBytes / 128)
        }
        else bufferSizeBytes

        Timber.d("Using buffer size $bufferSize")

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission()) {
            Timber.e("buildAudioRecord: RECORD_AUDIO not granted")
            throw RuntimeException("RECORD_AUDIO not granted")
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        val excluded = (if(excludeRestrictedSessions)
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
        else {
            sessionManager.pollOnce(false)
            emptyList()
        }).toMutableList()

        blockedApps.value?.map { it.uid }?.let {
            excluded += it
        }
        excluded += Process.myUid()

        excluded.forEach { configBuilder.excludeUid(it) }
        sessionManager.sessionDatabase.setExcludedUids(excluded.toTypedArray())
        sessionManager.pollOnce(false)

        Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.joinToString("; ")}")

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    // Determine HAL sampling rate
    private fun determineSamplingRate(): Int {
        val sampleRateStr: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val srate = sampleRateStr?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 48000
        Timber.i("Real HAL sampling rate is $srate")
        return srate
    }

    // Determine HAL buffer size
    private fun determineBufferSize(): Int {
        val framesPerBuffer: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 256
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 1
        /** Captured audio kept for priming the speed shifter (SpeedShifter::kPrimeFrames) */
        const val SPEED_SHIFT_PRIME_FRAMES = 8192

        // Capacity of the capture/playback buffers in DSP blocks (excluding the pre-fill)
        private const val RECORD_BUFFER_BLOCKS = 3
        private const val TRACK_BUFFER_BLOCKS = 2

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                // Use stopService instead of startForegroundService(ACTION_STOP):
                // If the service isn't running, starting it as a mediaProjection foreground service
                // without a fresh projection token throws a SecurityException on Android 14+.
                // stopService is also allowed from the background, unlike startForegroundService.
                context.stopService(Intent(context, RootlessAudioProcessorService::class.java))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
