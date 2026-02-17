package com.example.voiceinput

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import kotlinx.coroutines.*

class VoiceInputIME : InputMethodService() {

    private var processor: VoiceInputProcessor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var candidateArea: LinearLayout? = null
    private var candidateText: TextView? = null
    private var candidateButton: Button? = null
    private var committedTextLength = 0
    private var currentFullText: String? = null
    private var replacementRange: Pair<Int, Int>? = null
    private var isToggleRecording = false
    private var isFlickMode = false
    private var voiceModeArea: LinearLayout? = null
    private var flickKeyboard: FlickKeyboardView? = null
    private var correctionRepo: CorrectionRepository? = null
    private var commandLearning: CommandLearningView? = null
    private var sampleRecorder: AudioRecorder? = null
    private var recordingCommandId: String? = null
    private var recordingSampleIndex: Int = 0
    private var commandRepo: VoiceCommandRepository? = null
    private var composingBuffer = StringBuilder()
    private var contentFrame: FrameLayout? = null
    private var sshContextProvider: SshContextProvider? = null

    private var tabVoice: TextView? = null
    private var tabCommand: TextView? = null
    private var tabInput: TextView? = null
    private lateinit var tabBarManager: TabBarManager

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_voice_input, null)

        refreshProcessor()

        statusText = view.findViewById(R.id.imeStatusText)
        candidateArea = view.findViewById(R.id.candidateArea)
        candidateText = view.findViewById(R.id.candidateText)
        candidateButton = view.findViewById(R.id.candidateButton)
        candidateButton?.setOnClickListener { onCandidateButtonTap() }

        voiceModeArea = view.findViewById(R.id.voiceModeArea)
        flickKeyboard = view.findViewById(R.id.flickKeyboard)
        contentFrame = view.findViewById(R.id.contentFrame)

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

        flickKeyboard?.listener = object : FlickKeyboardListener {
            override fun onCharacterInput(char: String) {
                composingBuffer.append(char)
                currentInputConnection?.setComposingText(composingBuffer.toString(), 1)
            }

            override fun onBackspace() {
                if (composingBuffer.isNotEmpty()) {
                    composingBuffer.deleteCharAt(composingBuffer.length - 1)
                    if (composingBuffer.isEmpty()) {
                        currentInputConnection?.finishComposingText()
                    } else {
                        currentInputConnection?.setComposingText(composingBuffer.toString(), 1)
                    }
                } else {
                    currentInputConnection?.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                    )
                    currentInputConnection?.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                    )
                }
            }

            override fun onConvert() {
                if (composingBuffer.isEmpty()) return
                val hiragana = composingBuffer.toString()
                serviceScope.launch {
                    val prefsManager = PreferencesManager(
                        getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
                    )
                    val apiKey = prefsManager.getApiKey() ?: return@launch
                    val proc = processor
                    val converter = if (proc != null) {
                        proc.gptConverter
                    } else {
                        GptConverter(apiKey)
                    }
                    val candidates = withContext(Dispatchers.IO) {
                        converter.convertHiraganaToKanji(hiragana)
                    }
                    if (candidates.isNotEmpty()) {
                        showKanjiCandidatePopup(candidates, hiragana)
                    }
                }
            }

            override fun onConfirm() {
                if (composingBuffer.isNotEmpty()) {
                    currentInputConnection?.finishComposingText()
                    composingBuffer.clear()
                }
            }
        }

        val noopActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = true
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.clear()
                return true
            }
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        candidateText?.customSelectionActionModeCallback = noopActionModeCallback
        candidateText?.customInsertionActionModeCallback = noopActionModeCallback

        // Setup tab bar
        tabVoice = view.findViewById(R.id.tabVoice)
        tabCommand = view.findViewById(R.id.tabCommand)
        tabInput = view.findViewById(R.id.tabInput)

        tabBarManager = TabBarManager { tab ->
            when (tab) {
                TabBarManager.TAB_VOICE -> showVoiceModeContent()
                TabBarManager.TAB_COMMAND -> showLearningModeContent()
                TabBarManager.TAB_INPUT -> showFlickKeyboardContent()
            }
        }

        tabVoice?.setOnClickListener { animateTabSelection(TabBarManager.TAB_VOICE) }
        tabVoice?.setOnLongClickListener { showInputContextDebug(); true }
        tabCommand?.setOnClickListener { animateTabSelection(TabBarManager.TAB_COMMAND) }
        tabInput?.setOnClickListener { animateTabSelection(TabBarManager.TAB_INPUT) }

        // Apply initial tab style (VOICE selected)
        applyTabStyles()
        // Force initial selected style on VOICE tab
        applySelectedStyle(tabVoice)
        applyUnselectedStyle(tabCommand)
        applyUnselectedStyle(tabInput)

        // Setup mic double-tap gesture on voiceModeArea
        setupVoiceAreaGesture()

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
            statusText?.text = "$icDebug | $sshDebug"
        }
    }

    private fun setupVoiceAreaGesture() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isToggleRecording) {
                    isToggleRecording = true
                    onMicPressed()
                } else {
                    isToggleRecording = false
                    onMicReleased()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 30) {
                    animateTabSelection(TabBarManager.TAB_INPUT)
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        voiceModeArea?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
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
        TabBarManager.TAB_INPUT -> tabInput
        else -> null
    }

    private fun refreshProcessor() {
        val prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            processor = null
        } else {
            val whisperModel = prefsManager.getWhisperModel()
            processor = VoiceInputProcessor(
                AudioRecorder(cacheDir),
                WhisperClient(apiKey, model = whisperModel),
                GptConverter(apiKey)
            )
        }
        refreshSshProvider()
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

    private fun onMicPressed() {
        refreshProcessor()
        val proc = processor ?: run {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if text is selected in candidate area for replacement
        val tv = candidateText
        if (tv != null && currentFullText != null) {
            val start = tv.selectionStart
            val end = tv.selectionEnd
            if (start >= 0 && end >= 0 && start != end) {
                replacementRange = Pair(start, end)
            } else {
                replacementRange = null
            }
        } else {
            replacementRange = null
        }

        val started = proc.startRecording()
        if (started) {
            if (replacementRange != null) {
                statusText?.text = "録音中（選択範囲を置換）..."
            } else {
                statusText?.text = "録音中..."
                hideCandidateArea()
            }
        } else {
            replacementRange = null
            Toast.makeText(this, "録音を開始できません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMicReleased() {
        val proc = processor ?: return
        if (!proc.isRecording) return

        val range = replacementRange
        replacementRange = null

        if (range != null) {
            onMicReleasedForReplacement(proc, range)
        } else {
            onMicReleasedForNewInput(proc)
        }
    }

    private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
        statusText?.text = "処理中..."

        serviceScope.launch {
            val audioFile = proc.stopRecording() ?: run {
                statusText?.text = "録音に失敗しました"
                return@launch
            }

            try {
                // Try command matching first
                val matched = tryMatchCommand(audioFile)
                if (matched) return@launch

                // No command match — Whisper→GPT
                statusText?.text = "変換中..."

                // Fetch terminal context via SSH (if configured)
                val tmuxContext = withContext(Dispatchers.IO) {
                    sshContextProvider?.fetchContext()
                }
                val whisperPrompt = SshContextProvider.extractWhisperContext(tmuxContext)
                val gptContext = SshContextProvider.extractGptContext(tmuxContext)

                val corrections = correctionRepo?.getTopCorrections(20)
                val chunks = proc.processAudioFile(
                    audioFile,
                    context = whisperPrompt,
                    terminalContext = gptContext,
                    corrections = corrections
                )
                if (chunks != null) {
                    val fullText = chunks.joinToString("") { it.displayText }
                    committedTextLength = fullText.length
                    currentFullText = fullText
                    currentInputConnection?.commitText(fullText, 1)
                    showCandidateArea(fullText)
                    statusText?.text = "完了（テキスト選択→候補）"
                } else {
                    statusText?.text = "変換に失敗しました"
                }
                delay(5000)
                statusText?.text = "ダブルタップで音声入力"
            } catch (e: Exception) {
                audioFile.delete()
                statusText?.text = "エラーが発生しました"
                delay(5000)
                statusText?.text = "ダブルタップで音声入力"
            }
        }
    }

    private fun onMicReleasedForReplacement(proc: VoiceInputProcessor, range: Pair<Int, Int>) {
        statusText?.text = "音声認識中..."

        serviceScope.launch {
            val rawText = proc.stopAndTranscribeOnly()
            if (rawText != null) {
                replaceRange(range.first, range.second, rawText)
                statusText?.text = "置換完了（テキスト選択→候補）"
            } else {
                statusText?.text = "音声認識に失敗しました"
            }
            delay(5000)
            statusText?.text = "ダブルタップで音声入力"
        }
    }

    private suspend fun tryMatchCommand(audioFile: java.io.File): Boolean {
        val commands = commandRepo?.getCommands()
            ?.filter { it.enabled && it.sampleCount > 0 }
            ?.map { if (it.threshold < 1f) it.copy(threshold = 30.0f) else it }
        if (commands.isNullOrEmpty()) return false

        val mfccSamples = withContext(Dispatchers.IO) {
            commandRepo?.loadAllMfccs() ?: emptyMap()
        }
        if (mfccSamples.isEmpty()) return false

        val inputMfcc = withContext(Dispatchers.IO) {
            MfccExtractor.extract(audioFile.readBytes())
        }

        // Calculate distances for all commands (for diagnostics)
        val distances = commands.mapNotNull { cmd ->
            val samples = mfccSamples[cmd.id] ?: return@mapNotNull null
            if (samples.isEmpty()) return@mapNotNull null
            val dist = samples.minOf { DtwMatcher.dtwDistance(inputMfcc, it) }
            Triple(cmd, dist, cmd.threshold)
        }.sortedBy { it.second }

        if (distances.isEmpty()) return false

        val best = distances.first()
        val matched = best.second < best.third

        // Always show distance on status for tuning
        val distStr = "%.1f".format(best.second)
        if (matched) {
            audioFile.delete()
            val ic = currentInputConnection ?: return false
            CommandExecutor.execute(best.first.text, ic)
            statusText?.text = "CMD: ${best.first.label} (dist=$distStr)"
            delay(5000)
            statusText?.text = "ダブルタップで音声入力"
            return true
        } else {
            statusText?.text = "非CMD: ${best.first.label} (dist=$distStr)"
            return false
        }
    }

    // --- Candidate area ---

    private fun showCandidateArea(text: String) {
        candidateText?.text = text
        candidateArea?.visibility = View.VISIBLE
    }

    private fun hideCandidateArea() {
        candidateArea?.visibility = View.GONE
        candidateText?.text = ""
        currentFullText = null
    }

    private fun onCandidateButtonTap() {
        val tv = candidateText ?: return
        val start = tv.selectionStart
        val end = tv.selectionEnd

        if (start < 0 || end < 0 || start == end) {
            Toast.makeText(this, "テキストを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedText = tv.text.substring(start, end)
        fetchAiCandidates(selectedText, start, end)
    }

    private fun fetchAiCandidates(selectedText: String, selStart: Int, selEnd: Int) {
        statusText?.text = "候補取得中..."

        serviceScope.launch {
            val prefsManager = PreferencesManager(
                getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
            )
            val apiKey = prefsManager.getApiKey() ?: return@launch
            val converter = GptConverter(apiKey)
            val candidates = withContext(Dispatchers.IO) {
                converter.getCandidates(selectedText)
            }

            if (candidates.isNotEmpty()) {
                showAiCandidatePopup(candidates, selStart, selEnd)
            } else {
                Toast.makeText(this@VoiceInputIME, "候補を取得できませんでした", Toast.LENGTH_SHORT).show()
            }
            statusText?.text = "ダブルタップで音声入力"
        }
    }

    private fun showAiCandidatePopup(candidates: List<String>, selStart: Int, selEnd: Int) {
        val anchor = candidateButton ?: return
        val popup = PopupMenu(this, anchor)

        candidates.forEachIndexed { i, candidate ->
            popup.menu.add(0, i, i, candidate)
        }

        popup.setOnMenuItemClickListener { item ->
            val selected = candidates[item.itemId]
            replaceRange(selStart, selEnd, selected)
            true
        }
        popup.show()
    }

    private fun replaceRange(selStart: Int, selEnd: Int, replacement: String) {
        val ic = currentInputConnection ?: return
        val fullText = currentFullText ?: return

        // Delete all committed text
        for (i in 0 until committedTextLength) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }

        // Build new text
        val newText = fullText.substring(0, selStart) + replacement + fullText.substring(selEnd)
        ic.commitText(newText, 1)
        committedTextLength = newText.length
        currentFullText = newText
        showCandidateArea(newText)

        // Auto-learn the correction
        val originalFragment = fullText.substring(selStart, selEnd)
        if (originalFragment != replacement) {
            serviceScope.launch(Dispatchers.IO) {
                correctionRepo?.save(originalFragment, replacement)
            }
        }
    }

    private fun showFlickKeyboardContent() {
        isFlickMode = true
        voiceModeArea?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        flickKeyboard?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0)
    }

    private fun showVoiceModeContent() {
        isFlickMode = false
        if (composingBuffer.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composingBuffer.clear()
        }
        flickKeyboard?.visibility = View.GONE
        commandLearning?.visibility = View.GONE
        voiceModeArea?.visibility = View.VISIBLE
        contentFrame?.setBackgroundColor(0)
    }

    private fun showLearningModeContent() {
        isFlickMode = false
        voiceModeArea?.visibility = View.GONE
        flickKeyboard?.visibility = View.GONE
        commandLearning?.visibility = View.VISIBLE
        commandLearning?.refreshCommandList()
        contentFrame?.setBackgroundColor(0xFF111418.toInt())
    }

    private fun showFlickKeyboard() {
        animateTabSelection(TabBarManager.TAB_INPUT)
    }

    private fun showVoiceMode() {
        animateTabSelection(TabBarManager.TAB_VOICE)
    }

    private fun showKanjiCandidatePopup(candidates: List<String>, hiragana: String) {
        val anchor = flickKeyboard ?: return
        val popup = PopupMenu(this, anchor)

        candidates.forEachIndexed { i, candidate ->
            popup.menu.add(0, i, i, candidate)
        }

        popup.setOnMenuItemClickListener { item ->
            val selected = candidates[item.itemId]
            composingBuffer.clear()
            currentInputConnection?.commitText(selected, 1)
            if (hiragana != selected) {
                serviceScope.launch(Dispatchers.IO) {
                    correctionRepo?.save(hiragana, selected)
                }
            }
            true
        }
        popup.show()
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
        composingBuffer.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        sshContextProvider?.disconnect()
        serviceScope.cancel()
    }
}
