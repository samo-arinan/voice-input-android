package com.example.voiceinput

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

class VoiceInputIME : InputMethodService() {

    private var processor: VoiceInputProcessor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var micButton: ImageView? = null
    private var candidateBar: LinearLayout? = null
    private var candidateScroll: HorizontalScrollView? = null
    private var currentChunks: List<ConversionChunk>? = null
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
        candidateBar = view.findViewById(R.id.imeCandidateBar)
        candidateScroll = view.findViewById(R.id.imeCandidateScroll)

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
        val started = proc.startRecording()
        if (started) {
            statusText?.text = "録音中..."
            micButton?.alpha = 0.5f
            clearCandidateBar()
        } else {
            Toast.makeText(this, "録音を開始できません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMicReleased() {
        val proc = processor ?: return
        if (!proc.isRecording) return

        micButton?.alpha = 1.0f
        statusText?.text = "変換中..."

        serviceScope.launch {
            val chunks = proc.stopAndProcess()
            if (chunks != null) {
                currentChunks = chunks
                val fullText = chunks.joinToString("") { it.displayText }
                currentInputConnection?.commitText(fullText, 1)
                showCandidateBar(chunks)
                statusText?.text = "完了"
            } else {
                statusText?.text = "変換に失敗しました"
            }
            delay(5000)
            statusText?.text = "長押し/ダブルタップで音声入力"
        }
    }

    private fun showCandidateBar(chunks: List<ConversionChunk>) {
        val bar = candidateBar ?: return
        bar.removeAllViews()

        val hasDifference = chunks.any { it.isDifferent }
        if (!hasDifference) return

        candidateScroll?.visibility = View.VISIBLE

        chunks.forEachIndexed { index, chunk ->
            val button = TextView(this).apply {
                text = chunk.displayText
                textSize = 14f
                setBackgroundResource(
                    if (chunk.isDifferent) R.drawable.chunk_highlight_bg
                    else R.drawable.chunk_normal_bg
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 4
                layoutParams = params

                if (chunk.isDifferent) {
                    setOnClickListener { showChunkPopup(index, this) }
                }
            }
            bar.addView(button)
        }
    }

    private fun showChunkPopup(chunkIndex: Int, anchorView: View) {
        val chunks = currentChunks ?: return
        val chunk = chunks[chunkIndex]

        val popup = PopupMenu(this, anchorView)
        popup.menu.add(0, 0, 0, chunk.converted)
        popup.menu.add(0, 1, 1, chunk.raw)

        popup.setOnMenuItemClickListener { item ->
            val useRaw = item.itemId == 1
            if (chunk.useRaw != useRaw) {
                val oldText = chunk.displayText
                chunk.useRaw = useRaw
                val newText = chunk.displayText
                replaceChunkInInput(chunkIndex, oldText, newText)
                refreshCandidateBar()
            }
            true
        }
        popup.show()
    }

    private fun replaceChunkInInput(chunkIndex: Int, oldText: String, newText: String) {
        val ic = currentInputConnection ?: return
        val chunks = currentChunks ?: return

        val charsAfter = chunks.drop(chunkIndex + 1).sumOf { it.displayText.length }
        val deleteCount = charsAfter + oldText.length

        for (i in 0 until deleteCount) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }

        ic.commitText(newText, 1)
        val afterText = chunks.drop(chunkIndex + 1).joinToString("") { it.displayText }
        if (afterText.isNotEmpty()) {
            ic.commitText(afterText, 1)
        }
    }

    private fun refreshCandidateBar() {
        val chunks = currentChunks ?: return
        showCandidateBar(chunks)
    }

    private fun clearCandidateBar() {
        candidateBar?.removeAllViews()
        candidateScroll?.visibility = View.GONE
        currentChunks = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
