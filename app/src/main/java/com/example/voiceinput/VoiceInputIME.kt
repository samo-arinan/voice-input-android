package com.example.voiceinput

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

class VoiceInputIME : InputMethodService() {

    private var processor: VoiceInputProcessor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var micButton: ImageView? = null
    private var candidateArea: LinearLayout? = null
    private var candidateText: TextView? = null
    private var candidateButton: Button? = null
    private var committedTextLength = 0
    private var currentFullText: String? = null
    private var replacementRange: Pair<Int, Int>? = null
    private var isToggleRecording = false
    private var isHoldRecording = false
    private var longPressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private companion object {
        const val LONG_PRESS_DELAY = 500L
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_voice_input, null)

        refreshProcessor()

        statusText = view.findViewById(R.id.imeStatusText)
        micButton = view.findViewById(R.id.imeMicButton)
        candidateArea = view.findViewById(R.id.candidateArea)
        candidateText = view.findViewById(R.id.candidateText)
        candidateButton = view.findViewById(R.id.candidateButton)

        candidateButton?.setOnClickListener { onCandidateButtonTap() }

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

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null
                if (!isToggleRecording && !isHoldRecording) {
                    isToggleRecording = true
                    onMicPressed()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isToggleRecording) {
                    isToggleRecording = false
                    onMicReleased()
                }
                return true
            }
        })

        micButton?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isToggleRecording) {
                        longPressRunnable = Runnable {
                            isHoldRecording = true
                            onMicPressed()
                        }
                        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DELAY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                    if (isHoldRecording) {
                        isHoldRecording = false
                        onMicReleased()
                    }
                    true
                }
                else -> false
            }
        }

        return view
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
            micButton?.alpha = 0.5f
        } else {
            replacementRange = null
            Toast.makeText(this, "録音を開始できません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMicReleased() {
        val proc = processor ?: return
        if (!proc.isRecording) return

        micButton?.alpha = 1.0f
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
            val chunks = proc.stopAndProcess()
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
            statusText?.text = "長押し/ダブルタップで音声入力"
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
            statusText?.text = "長押し/ダブルタップで音声入力"
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
            statusText?.text = "長押し/ダブルタップで音声入力"
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
    }

    private fun hideCandidateAreaDelayed() {
        candidateArea?.visibility = View.GONE
        currentFullText = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
