package com.example.voiceinput

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

class VoiceInputIME : InputMethodService() {

    companion object {
        const val COLOR_GREEN = 0xFF4ADE80.toInt()   // Notification (approval waiting)
        const val COLOR_ORANGE = 0xFFFB923C.toInt()   // Stop (processing finished)

        private val realtimeHttpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var realtimeClient: RealtimeClient? = null
    private var audioStreamer: RealtimeAudioStreamer? = null
    private var rippleView: RippleAnimationView? = null
    private var undoStrip: View? = null
    private var undoPreviewText: TextView? = null
    private var undoButton: TextView? = null
    private val undoManager = UndoManager()
    private var composingText = StringBuilder()
    private var isRealtimeRecording = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var undoDismissRunnable: Runnable? = null
    private var amplitudePoller: java.util.Timer? = null
    private var voiceModeArea: LinearLayout? = null
    private var tmuxView: TmuxView? = null
    private var correctionRepo: CorrectionRepository? = null
    private var commandLearning: CommandLearningView? = null
    private var sampleRecorder: AudioRecorder? = null
    private var recordingCommandId: String? = null
    private var recordingSampleIndex: Int = 0
    private var commandRepo: VoiceCommandRepository? = null
    private var contentFrame: FrameLayout? = null
    private var sshContextProvider: SshContextProvider? = null
    private var ntfyListener: NtfyListener? = null
    private var notificationDot: View? = null
    private var notificationAnimator: ObjectAnimator? = null

    private var tabVoice: TextView? = null
    private var tabCommand: TextView? = null
    private var tabInput: TextView? = null
    private lateinit var tabBarManager: TabBarManager

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_voice_input, null)

        refreshSshProvider()

        rippleView = view.findViewById(R.id.rippleView)
        undoStrip = view.findViewById(R.id.undoStrip)
        undoPreviewText = view.findViewById(R.id.undoPreviewText)
        undoButton = view.findViewById(R.id.undoButton)
        undoButton?.setOnClickListener { performUndo() }

        voiceModeArea = view.findViewById(R.id.voiceModeArea)
        tmuxView = view.findViewById(R.id.tmuxView)
        contentFrame = view.findViewById(R.id.contentFrame)

        tmuxView?.listener = object : TmuxViewListener {
            override fun onSendKeys(key: String) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        sshContextProvider?.sendKeys(key)
                        delay(100)
                        val text = sshContextProvider?.fetchLines(20)
                        withContext(Dispatchers.Main) {
                            tmuxView?.updateOutput(text)
                        }
                    } catch (e: Exception) {
                        // Silently ignore SSH errors
                    }
                }
            }

            override fun onRequestRefresh(callback: (String?) -> Unit) {
                serviceScope.launch(Dispatchers.IO) {
                    val text = try {
                        sshContextProvider?.fetchLines(20)
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        callback(text)
                    }
                }
            }
        }

        // Initialize correction repository
        val correctionsFile = File(filesDir, "corrections.json")
        correctionRepo = CorrectionRepository(correctionsFile)

        val commandsFile = File(filesDir, "voice_commands.json")
        val samplesDir = File(filesDir, "voice_samples")
        commandRepo = VoiceCommandRepository(commandsFile, samplesDir)

        commandLearning = view.findViewById(R.id.commandLearning)
        commandLearning?.setRepository(commandRepo!!)
        commandLearning?.listener = object : CommandLearningListener {
            override fun onRecordSample(commandId: String, sampleIndex: Int) {
                recordCommandSample(commandId, sampleIndex)
            }
            override fun onDeleteCommand(commandId: String) {
                commandRepo?.deleteCommand(commandId)
                commandLearning?.refreshCommandList()
            }
        }

        // Setup tab bar
        tabVoice = view.findViewById(R.id.tabVoice)
        tabCommand = view.findViewById(R.id.tabCommand)
        tabInput = view.findViewById(R.id.tabInput)
        notificationDot = view.findViewById(R.id.tmuxNotificationDot)

        tabBarManager = TabBarManager { tab ->
            when (tab) {
                TabBarManager.TAB_VOICE -> showVoiceModeContent()
                TabBarManager.TAB_COMMAND -> showLearningModeContent()
                TabBarManager.TAB_TMUX -> showTmuxContent()
            }
        }

        tabVoice?.setOnClickListener { animateTabSelection(TabBarManager.TAB_VOICE) }
        tabVoice?.setOnLongClickListener { showInputContextDebug(); true }
        tabCommand?.setOnClickListener { animateTabSelection(TabBarManager.TAB_COMMAND) }
        tabInput?.setOnClickListener { animateTabSelection(TabBarManager.TAB_TMUX) }

        // Apply initial tab style (VOICE selected)
        applyTabStyles()
        // Force initial selected style on VOICE tab
        applySelectedStyle(tabVoice)
        applyUnselectedStyle(tabCommand)
        applyUnselectedStyle(tabInput)

        // Setup ripple tap gesture
        rippleView?.setOnClickListener {
            if (!isRealtimeRecording) {
                startRealtimeRecording()
            } else {
                stopRealtimeRecording()
            }
        }

        refreshNtfyListener()

        return view
    }

    private fun showInputContextDebug() {
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(500, 0)
        val icDebug = InputContextReader.formatContextDebug(before)

        serviceScope.launch {
            val sshText = withContext(Dispatchers.IO) {
                sshContextProvider?.fetchContext()
            }
            val sshDebug = if (sshText != null) "SSH[${sshText.length}]: OK" else "SSH: N/A"
            Toast.makeText(this@VoiceInputIME, "$icDebug | $sshDebug", Toast.LENGTH_LONG).show()
        }
    }

    private fun animateTabSelection(tab: Int) {
        if (tab == tabBarManager.currentTab) return

        val targetView = getTabView(tab) ?: return

        // Flash dark (20ms)
        targetView.alpha = 0.5f
        targetView.postDelayed({ targetView.alpha = 1.0f }, TabBarManager.FLASH_DURATION_MS)

        // Haptic feedback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            targetView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Update state
        tabBarManager.selectTab(tab)

        // Animate all tabs
        val tabs = listOf(tabVoice, tabCommand, tabInput)
        tabs.forEachIndexed { index, tv ->
            if (tv == null) return@forEachIndexed
            val style = tabBarManager.getTabStyle(index)
            tv.animate()
                .translationY(style.translationY * resources.displayMetrics.density)
                .setDuration(TabBarManager.ANIM_DURATION_MS)
                .start()
            tv.elevation = style.elevation * resources.displayMetrics.density
            tv.setTextColor(style.textColor)
            if (style.isSelected) {
                tv.setBackgroundResource(R.drawable.bg_tab_selected)
            } else {
                tv.setBackgroundResource(R.drawable.bg_tab_unselected)
            }
        }
    }

    private fun applySelectedStyle(tab: TextView?) {
        tab ?: return
        val density = resources.displayMetrics.density
        tab.translationY = 2f * density
        tab.elevation = 1f * density
        tab.setTextColor(0xFFE0E6ED.toInt())
        tab.setBackgroundResource(R.drawable.bg_tab_selected)
    }

    private fun applyUnselectedStyle(tab: TextView?) {
        tab ?: return
        val density = resources.displayMetrics.density
        tab.translationY = 0f
        tab.elevation = 6f * density
        tab.setTextColor(0xFF8B949E.toInt())
        tab.setBackgroundResource(R.drawable.bg_tab_unselected)
    }

    private fun applyTabStyles() {
        val tabs = listOf(tabVoice, tabCommand, tabInput)
        tabs.forEachIndexed { index, tv ->
            if (tv == null) return@forEachIndexed
            val style = tabBarManager.getTabStyle(index)
            val density = resources.displayMetrics.density
            tv.translationY = style.translationY * density
            tv.elevation = style.elevation * density
            tv.setTextColor(style.textColor)
            if (style.isSelected) {
                tv.setBackgroundResource(R.drawable.bg_tab_selected)
            } else {
                tv.setBackgroundResource(R.drawable.bg_tab_unselected)
            }
        }
    }

    private fun getTabView(tab: Int): TextView? = when (tab) {
        TabBarManager.TAB_VOICE -> tabVoice
        TabBarManager.TAB_COMMAND -> tabCommand
        TabBarManager.TAB_TMUX -> tabInput
        else -> null
    }

    private fun refreshSshProvider() {
        val prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )
        if (prefsManager.isSshConfigured()) {
            sshContextProvider = SshContextProvider(
                host = prefsManager.getSshHost()!!,
                port = prefsManager.getSshPort(),
                username = prefsManager.getSshUsername()!!,
                privateKey = prefsManager.getSshPrivateKey()!!,
                tmuxSession = prefsManager.getSshTmuxSession()
            )
        } else {
            sshContextProvider?.disconnect()
            sshContextProvider = null
        }
    }

    private fun refreshNtfyListener() {
        ntfyListener?.stop()
        val topic = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        ).getNtfyTopic()
        if (topic.isNotBlank()) {
            ntfyListener = NtfyListener(topic, onNotification = { data ->
                val color = parseNotificationColor(data)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showTmuxNotification(color)
                }
            })
            ntfyListener?.start()
        }
    }

    private fun parseNotificationColor(data: String): Int {
        return try {
            val msg = JSONObject(data).optString("message", "")
            if (msg.contains("finished")) COLOR_ORANGE else COLOR_GREEN
        } catch (_: Exception) {
            COLOR_GREEN
        }
    }

    private fun showTmuxNotification(color: Int = COLOR_GREEN) {
        notificationDot?.visibility = View.VISIBLE
        notificationDot?.backgroundTintList = ColorStateList.valueOf(color)
        notificationAnimator?.cancel()
        notificationAnimator = ObjectAnimator.ofFloat(notificationDot, "alpha", 0f, 1f).apply {
            duration = 500
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun clearTmuxNotification() {
        notificationAnimator?.cancel()
        notificationDot?.alpha = 1f
        notificationDot?.visibility = View.GONE
    }

    // --- Realtime API methods ---

    private fun startRealtimeRecording() {
        if (realtimeClient != null) return  // still processing previous response
        val prefs = PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }

        undoManager.clear()
        hideUndoStrip()
        composingText.clear()
        rippleView?.setState(RippleState.PROCESSING) // connecting...

        serviceScope.launch {
            // Fetch terminal context async
            val tmuxContext = withContext(Dispatchers.IO) {
                try { sshContextProvider?.fetchContext() } catch (_: Exception) { null }
            }
            val gptContext = SshContextProvider.extractGptContext(tmuxContext)
            val corrections = correctionRepo?.getTopCorrections(20)
            val instructions = RealtimePromptBuilder.build(
                corrections = corrections,
                terminalContext = gptContext
            )

            connectRealtime(apiKey, prefs.getRealtimeModel(), instructions)
        }
    }

    private fun connectRealtime(apiKey: String, model: String, instructions: String) {
        audioStreamer = RealtimeAudioStreamer { pcmChunk ->
            realtimeClient?.sendAudio(pcmChunk)
        }

        val client = RealtimeClient(
            realtimeHttpClient,
            object : RealtimeClient.Listener {
                override fun onSessionReady() {
                    mainHandler.post {
                        val started = audioStreamer?.start() ?: false
                        if (started) {
                            isRealtimeRecording = true
                            rippleView?.setState(RippleState.RECORDING)
                            startAmplitudePolling()
                        } else {
                            Toast.makeText(this@VoiceInputIME, "録音を開始できません", Toast.LENGTH_SHORT).show()
                            cleanupRealtime()
                        }
                    }
                }

                override fun onTextDelta(text: String) {
                    mainHandler.post {
                        composingText.append(text)
                        currentInputConnection?.setComposingText(composingText.toString(), 1)
                    }
                }

                override fun onTextDone(text: String) {
                    mainHandler.post {
                        currentInputConnection?.commitText(text, 1)
                        composingText.clear()
                        undoManager.recordCommit(text, text.length)
                        showUndoStrip(text)
                        rippleView?.setState(RippleState.IDLE)
                    }
                }

                override fun onSpeechStarted() {
                    // VAD detected speech start - visual feedback already active
                }

                override fun onSpeechStopped() {
                    mainHandler.post {
                        audioStreamer?.stop()
                        isRealtimeRecording = false
                        rippleView?.setState(RippleState.PROCESSING)
                        stopAmplitudePolling()
                    }
                }

                override fun onTranscriptionCompleted(transcript: String) {
                    // Raw transcription for potential correction learning
                }

                override fun onResponseDone() {
                    mainHandler.post {
                        realtimeClient?.disconnect()
                        realtimeClient = null
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        Toast.makeText(this@VoiceInputIME, "エラー: $message", Toast.LENGTH_SHORT).show()
                        cleanupRealtime()
                    }
                }
            }
        )
        client.setInstructions(instructions)
        client.connect(apiKey, model)
        realtimeClient = client
    }

    private fun stopRealtimeRecording() {
        audioStreamer?.stop()
        isRealtimeRecording = false
        rippleView?.setState(RippleState.PROCESSING)
        stopAmplitudePolling()
        // Don't disconnect — wait for response.done via onResponseDone
    }

    private fun startAmplitudePolling() {
        amplitudePoller = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val rms = audioStreamer?.currentRms ?: 0f
                    mainHandler.post { rippleView?.setAmplitude(rms) }
                }
            }, 0, 50)
        }
    }

    private fun stopAmplitudePolling() {
        amplitudePoller?.cancel()
        amplitudePoller = null
    }

    private fun performUndo() {
        val length = undoManager.undo()
        if (length > 0) {
            currentInputConnection?.deleteSurroundingText(length, 0)
            hideUndoStrip()
        }
    }

    private fun showUndoStrip(text: String) {
        undoPreviewText?.text = text
        undoStrip?.visibility = View.VISIBLE
        undoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        undoDismissRunnable = Runnable { hideUndoStrip() }
        mainHandler.postDelayed(undoDismissRunnable!!, 5000)
    }

    private fun hideUndoStrip() {
        undoStrip?.visibility = View.GONE
        undoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cleanupRealtime() {
        audioStreamer?.stop()
        audioStreamer = null
        realtimeClient?.disconnect()
        realtimeClient = null
        isRealtimeRecording = false
        currentInputConnection?.finishComposingText()
        composingText.clear()
        stopAmplitudePolling()
        rippleView?.setState(RippleState.IDLE)
    }

    private fun showTmuxContent() {
        voiceModeArea?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        tmuxView?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0xFF111418.toInt())
        tmuxView?.startPolling()
        clearTmuxNotification()
    }

    private fun showVoiceModeContent() {
        tmuxView?.stopPolling()
        tmuxView?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        voiceModeArea?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0)
    }

    private fun showLearningModeContent() {
        tmuxView?.stopPolling()
        voiceModeArea?.visibility = View.GONE
        tmuxView?.visibility = View.GONE
        commandLearning?.visibility = View.VISIBLE
        commandLearning?.refreshCommandList()
        contentFrame?.setBackgroundColor(0xFF111418.toInt())
    }

    private fun showVoiceMode() {
        animateTabSelection(TabBarManager.TAB_VOICE)
    }

    private fun recordCommandSample(commandId: String, sampleIndex: Int) {
        if (sampleIndex >= 5) return // max 5 samples

        if (sampleRecorder?.isRecording == true) {
            // Stop recording
            val wavFile = sampleRecorder?.stop() ?: return
            val targetFile = commandRepo?.getSampleFile(commandId, sampleIndex)
            if (targetFile != null) {
                wavFile.copyTo(targetFile, overwrite = true)
                try {
                    val mfcc = MfccExtractor.extract(targetFile.readBytes())
                    commandRepo?.saveMfccCache(commandId, sampleIndex, mfcc)
                } catch (_: Exception) {}
                wavFile.delete()
                commandRepo?.updateSampleCount(commandId, sampleIndex + 1)
                commandLearning?.refreshCommandList()
            }
            sampleRecorder = null
            recordingCommandId = null
            return
        }

        // Start recording (auto-stop after 2 seconds)
        sampleRecorder = AudioRecorder(cacheDir)
        recordingCommandId = commandId
        recordingSampleIndex = sampleIndex
        val started = sampleRecorder?.start() ?: false
        if (started) {
            serviceScope.launch {
                delay(2000)
                if (sampleRecorder?.isRecording == true) {
                    recordCommandSample(commandId, sampleIndex)
                }
            }
        } else {
            Toast.makeText(this, "録音を開始できません", Toast.LENGTH_SHORT).show()
            sampleRecorder = null
            recordingCommandId = null
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupRealtime()
        ntfyListener?.stop()
        tmuxView?.stopPolling()
        sshContextProvider?.disconnect()
        serviceScope.cancel()
    }
}
