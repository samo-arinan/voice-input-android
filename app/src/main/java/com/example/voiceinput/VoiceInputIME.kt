package com.example.voiceinput

import android.inputmethodservice.InputMethodService
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class VoiceInputIME : InputMethodService() {

    private var processor: VoiceInputProcessor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var micButton: ImageView? = null

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_voice_input, null)

        val prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_LONG).show()
        } else {
            processor = VoiceInputProcessor(
                AudioRecorder(cacheDir),
                WhisperClient(apiKey),
                GptConverter(apiKey)
            )
        }

        statusText = view.findViewById(R.id.imeStatusText)
        micButton = view.findViewById(R.id.imeMicButton)
        val switchButton = view.findViewById<ImageButton>(R.id.imeSwitchButton)

        micButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onMicPressed()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onMicReleased()
                    true
                }
                else -> false
            }
        }

        switchButton?.setOnClickListener {
            switchToNextInputMethod(false)
        }

        return view
    }

    private fun onMicPressed() {
        val proc = processor ?: run {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }
        val started = proc.startRecording()
        if (started) {
            statusText?.text = "録音中..."
            micButton?.alpha = 0.5f
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
            val text = proc.stopAndProcess()
            if (text != null) {
                currentInputConnection?.commitText(text, 1)
                statusText?.text = "完了"
            } else {
                statusText?.text = "変換に失敗しました"
            }
            delay(2000)
            statusText?.text = "長押しで音声入力"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
