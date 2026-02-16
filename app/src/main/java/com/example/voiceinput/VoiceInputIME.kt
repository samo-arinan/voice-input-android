package com.example.voiceinput

import android.inputmethodservice.InputMethodService
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import kotlinx.coroutines.*

class VoiceInputIME : InputMethodService() {

    private var processor: VoiceInputProcessor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var modeIconPager: ViewPager2? = null
    private var micIconView: ImageView? = null
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

        // Setup mode icon ViewPager2
        modeIconPager = view.findViewById(R.id.modeIconPager)
        val iconAdapter = ModeIconPagerAdapter()
        iconAdapter.onPageBound = { position, pageView ->
            if (position == ModeIconPagerAdapter.PAGE_MIC) {
                setupMicIcon(pageView)
            }
        }
        modeIconPager?.adapter = iconAdapter
        modeIconPager?.offscreenPageLimit = 1

        modeIconPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    ModeIconPagerAdapter.PAGE_MIC -> {
                        showVoiceModeContent()
                    }
                    ModeIconPagerAdapter.PAGE_BRAIN -> {
                        showLearningModeContent()
                    }
                    ModeIconPagerAdapter.PAGE_KEYBOARD -> {
                        showFlickKeyboardContent()
                    }
                }
            }
        })

        return view
    }

    private fun setupMicIcon(pageView: View) {
        micIconView = pageView.findViewById(R.id.micIcon)

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
                    showFlickKeyboard()
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        micIconView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
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
            micIconView?.alpha = 0.5f
        } else {
            replacementRange = null
            Toast.makeText(this, "録音を開始できません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMicReleased() {
        val proc = processor ?: return
        if (!proc.isRecording) return

        micIconView?.alpha = 1.0f
        val range = replacementRange
        replacementRange = null

        if (range != null) {
            onMicReleasedForReplacement(proc, range)
        } else {
            onMicReleasedForNewInput(proc)
        }
    }

    private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
        statusText?.text = "変換中..."

        serviceScope.launch {
            val corrections = correctionRepo?.getTopCorrections(20)
            val chunks = proc.stopAndProcess(corrections = corrections)
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
        modeIconPager?.setCurrentItem(ModeIconPagerAdapter.PAGE_KEYBOARD, true)
    }

    private fun showVoiceMode() {
        modeIconPager?.setCurrentItem(ModeIconPagerAdapter.PAGE_MIC, true)
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
        if (sampleIndex >= 3) return // max 3 samples

        if (sampleRecorder?.isRecording == true) {
            // Stop recording
            val wavFile = sampleRecorder?.stop() ?: return
            val targetFile = commandRepo?.getSampleFile(commandId, sampleIndex)
            if (targetFile != null) {
                wavFile.copyTo(targetFile, overwrite = true)
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
        serviceScope.cancel()
    }
}
