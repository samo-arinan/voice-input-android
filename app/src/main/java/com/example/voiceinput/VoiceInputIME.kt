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
        const val COLOR_GREEN = 0xFF4ADE80.toInt()
        const val COLOR_ORANGE = 0xFFFB923C.toInt()

        private val realtimeHttpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val undoManager = UndoManager()

    // INPUT tab
    private lateinit var inputController: RealtimeInputController
    private var rippleView: RippleAnimationView? = null
    private var undoStrip: View? = null
    private var undoPreviewText: TextView? = null
    private var undoButton: TextView? = null
    private var undoDismissRunnable: Runnable? = null
    private var amplitudePoller: java.util.Timer? = null
    private var voiceModeArea: LinearLayout? = null

    // COMMAND tab
    private var commandLearning: CommandLearningView? = null
    private var sampleRecorder: AudioRecorder? = null
    private var recordingCommandId: String? = null
    private var recordingSampleIndex: Int = 0
    private var commandRepo: VoiceCommandRepository? = null

    // TMUX tab
    private var tmuxView: TmuxView? = null
    private var contentFrame: FrameLayout? = null
    private var sshContextProvider: SshContextProvider? = null
    private var ntfyListener: NtfyListener? = null
    private var notificationDot: View? = null
    private var notificationAnimator: ObjectAnimator? = null
    private var correctionRepo: CorrectionRepository? = null

    // Tab bar
    private var tabVoice: TextView? = null
    private var tabCommand: TextView? = null
    private var tabInput: TextView? = null
    private lateinit var tabBarManager: TabBarManager

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_voice_input, null)

        refreshSshProvider()
        setupInputController()
        setupInputTab(view)
        setupCommandTab(view)
        setupTmuxTab(view)
        setupTabBar(view)
        refreshNtfyListener()

        return view
    }

    // --- INPUT tab setup ---

    private fun setupInputController() {
        inputController = RealtimeInputController(
            httpClient = realtimeHttpClient,
            postToMain = { mainHandler.post(it) }
        )
        inputController.callback = object : RealtimeInputController.Callback {
            override fun onStateChanged(state: RealtimeInputController.State) {
                when (state) {
                    RealtimeInputController.State.IDLE -> {
                        rippleView?.setState(RippleState.IDLE)
                        stopAmplitudePolling()
                    }
                    RealtimeInputController.State.CONNECTING -> {
                        rippleView?.setState(RippleState.PROCESSING)
                    }
                    RealtimeInputController.State.RECORDING -> {
                        rippleView?.setState(RippleState.RECORDING)
                        startAmplitudePolling()
                    }
                    RealtimeInputController.State.PROCESSING -> {
                        rippleView?.setState(RippleState.PROCESSING)
                        stopAmplitudePolling()
                    }
                }
            }

            override fun onComposingText(text: String) {
                currentInputConnection?.setComposingText(text, 1)
            }

            override fun onCommitText(text: String) {
                currentInputConnection?.commitText(text, 1)
                undoManager.recordCommit(text, text.length)
                showUndoStrip(text)
            }

            override fun onError(message: String) {
                currentInputConnection?.finishComposingText()
                Toast.makeText(this@VoiceInputIME, "エラー: $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupInputTab(view: View) {
        rippleView = view.findViewById(R.id.rippleView)
        undoStrip = view.findViewById(R.id.undoStrip)
        undoPreviewText = view.findViewById(R.id.undoPreviewText)
        undoButton = view.findViewById(R.id.undoButton)
        undoButton?.setOnClickListener { performUndo() }
        voiceModeArea = view.findViewById(R.id.voiceModeArea)

        rippleView?.setOnClickListener {
            when (inputController.state) {
                RealtimeInputController.State.IDLE -> startInput()
                RealtimeInputController.State.RECORDING -> inputController.stop()
                else -> {} // CONNECTING or PROCESSING - ignore
            }
        }
    }

    private fun startInput() {
        val prefs = PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }

        undoManager.clear()
        hideUndoStrip()

        serviceScope.launch {
            val tmuxContext = withContext(Dispatchers.IO) {
                try { sshContextProvider?.fetchContext() } catch (_: Exception) { null }
            }
            val gptContext = SshContextProvider.extractGptContext(tmuxContext)
            val corrections = correctionRepo?.getTopCorrections(20)
            val instructions = RealtimePromptBuilder.build(
                corrections = corrections,
                terminalContext = gptContext
            )
            inputController.start(apiKey, prefs.getRealtimeModel(), instructions)
        }
    }

    private fun startAmplitudePolling() {
        amplitudePoller = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val rms = inputController.amplitude
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

    // --- COMMAND tab ---

    private fun setupCommandTab(view: View) {
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
    }

    private fun recordCommandSample(commandId: String, sampleIndex: Int) {
        if (sampleIndex >= 5) return

        if (sampleRecorder?.isRecording == true) {
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

    // --- TMUX tab ---

    private fun setupTmuxTab(view: View) {
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
                    } catch (_: Exception) {}
                }
            }

            override fun onRequestRefresh(callback: (String?) -> Unit) {
                serviceScope.launch(Dispatchers.IO) {
                    val text = try { sshContextProvider?.fetchLines(20) } catch (_: Exception) { null }
                    withContext(Dispatchers.Main) { callback(text) }
                }
            }
        }
    }

    // --- Tab bar ---

    private fun setupTabBar(view: View) {
        tabVoice = view.findViewById(R.id.tabVoice)
        tabCommand = view.findViewById(R.id.tabCommand)
        tabInput = view.findViewById(R.id.tabInput)
        notificationDot = view.findViewById(R.id.tmuxNotificationDot)

        tabBarManager = TabBarManager { tab ->
            when (tab) {
                TabBarManager.TAB_VOICE -> showInputContent()
                TabBarManager.TAB_COMMAND -> showLearningModeContent()
                TabBarManager.TAB_TMUX -> showTmuxContent()
            }
        }

        tabVoice?.setOnClickListener { animateTabSelection(TabBarManager.TAB_VOICE) }
        tabVoice?.setOnLongClickListener { showInputContextDebug(); true }
        tabCommand?.setOnClickListener { animateTabSelection(TabBarManager.TAB_COMMAND) }
        tabInput?.setOnClickListener { animateTabSelection(TabBarManager.TAB_TMUX) }

        applyTabStyles()
        applySelectedStyle(tabVoice)
        applyUnselectedStyle(tabCommand)
        applyUnselectedStyle(tabInput)
    }

    private fun showInputContextDebug() {
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(500, 0)
        val icDebug = InputContextReader.formatContextDebug(before)

        serviceScope.launch {
            val sshText = withContext(Dispatchers.IO) { sshContextProvider?.fetchContext() }
            val sshDebug = if (sshText != null) "SSH[${sshText.length}]: OK" else "SSH: N/A"
            Toast.makeText(this@VoiceInputIME, "$icDebug | $sshDebug", Toast.LENGTH_LONG).show()
        }
    }

    private fun animateTabSelection(tab: Int) {
        if (tab == tabBarManager.currentTab) return
        val targetView = getTabView(tab) ?: return

        targetView.alpha = 0.5f
        targetView.postDelayed({ targetView.alpha = 1.0f }, TabBarManager.FLASH_DURATION_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            targetView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        tabBarManager.selectTab(tab)

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

    // --- Tab content switching ---

    private fun showInputContent() {
        tmuxView?.stopPolling()
        tmuxView?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        voiceModeArea?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0)
    }

    private fun showTmuxContent() {
        voiceModeArea?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        tmuxView?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0xFF111418.toInt())
        tmuxView?.startPolling()
        clearTmuxNotification()
    }

    private fun showLearningModeContent() {
        tmuxView?.stopPolling()
        voiceModeArea?.visibility = View.GONE
        tmuxView?.visibility = View.GONE
        commandLearning?.visibility = View.VISIBLE
        commandLearning?.refreshCommandList()
        contentFrame?.setBackgroundColor(0xFF111418.toInt())
    }

    // --- Notifications ---

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
                mainHandler.post { showTmuxNotification(color) }
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

    // --- Lifecycle ---

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        inputController.cleanup()
        currentInputConnection?.finishComposingText()
        ntfyListener?.stop()
        tmuxView?.stopPolling()
        sshContextProvider?.disconnect()
        serviceScope.cancel()
    }
}
