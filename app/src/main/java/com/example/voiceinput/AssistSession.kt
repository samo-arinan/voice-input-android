package com.example.voiceinput

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private var screenshot: Bitmap? = null
    private var audioRecorder: AudioRecorder? = null
    private var touchCanvas: TouchCanvas? = null
    private var traceRect: BoundingRect? = null
    private var isProcessing = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoStopDelay = 1500L

    // UI references
    private var recordingIndicator: LinearLayout? = null
    private var responseContainer: ScrollView? = null
    private var responseText: TextView? = null
    private var statusText: TextView? = null
    private var screenshotBackground: ImageView? = null

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.assist_overlay, null)

        screenshotBackground = view.findViewById(R.id.screenshotBackground)
        recordingIndicator = view.findViewById(R.id.recordingIndicator)
        responseContainer = view.findViewById(R.id.responseContainer)
        responseText = view.findViewById(R.id.responseText)
        statusText = view.findViewById(R.id.statusText)

        val container = view.findViewById<FrameLayout>(R.id.touchCanvasContainer)
        touchCanvas = TouchCanvas(context).apply {
            onTraceComplete = { rect ->
                traceRect = rect
                handler.postDelayed({ stopAndProcess() }, autoStopDelay)
            }
        }
        container.addView(touchCanvas)

        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startRecording()
    }

    override fun onHandleScreenshot(bmp: Bitmap?) {
        screenshot = bmp
        handler.post {
            if (bmp != null) {
                screenshotBackground?.setImageBitmap(bmp)
            }
        }
    }

    override fun onHide() {
        super.onHide()
        cleanup()
    }

    private fun startRecording() {
        val cacheDir = context.cacheDir
        audioRecorder = AudioRecorder(cacheDir)
        audioRecorder?.start()
    }

    private fun stopAndProcess() {
        if (isProcessing) return
        isProcessing = true

        handler.post {
            recordingIndicator?.visibility = View.GONE
            statusText?.visibility = View.VISIBLE
            statusText?.text = "処理中..."
        }

        Thread {
            try {
                val audioFile = audioRecorder?.stop()
                val bmp = screenshot
                val rect = traceRect

                val prefs = PreferencesManager(
                    context.getSharedPreferences("voice_input_prefs", Context.MODE_PRIVATE)
                )
                val apiKey = prefs.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    showError("APIキーが設定されていません")
                    return@Thread
                }

                // Transcribe audio
                var question: String? = null
                if (audioFile != null && audioFile.exists()) {
                    val whisper = WhisperClient(apiKey)
                    question = whisper.transcribe(audioFile)
                    audioFile.delete()
                }

                // Crop screenshot
                val imageBase64: String? = if (bmp != null && rect != null) {
                    ScreenCropper.cropAndEncode(bmp, rect)
                } else if (bmp != null) {
                    ScreenCropper.cropAndEncode(
                        bmp, BoundingRect(0, 0, bmp.width, bmp.height)
                    )
                } else {
                    null
                }

                if (imageBase64 == null) {
                    showError("画面をキャプチャできませんでした")
                    return@Thread
                }

                // Ask GPT-4o Vision
                val vision = VisionClient(apiKey)
                val answer = vision.ask(imageBase64, question)

                if (answer != null) {
                    showResponse(answer)
                } else {
                    showError("回答を取得できませんでした")
                }
            } catch (e: Exception) {
                showError("エラー: ${e.message}")
            }
        }.start()
    }

    private fun showResponse(text: String) {
        handler.post {
            statusText?.visibility = View.GONE
            responseContainer?.visibility = View.VISIBLE
            responseText?.text = text
            touchCanvas?.setOnTouchListener { _, _ ->
                hide()
                true
            }
        }
    }

    private fun showError(message: String) {
        handler.post {
            statusText?.text = message
            statusText?.visibility = View.VISIBLE
            responseContainer?.visibility = View.GONE
            touchCanvas?.setOnTouchListener { _, _ ->
                hide()
                true
            }
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (audioRecorder?.isRecording == true) {
            audioRecorder?.stop()?.delete()
        }
        audioRecorder = null
        screenshot = null
        traceRect = null
        isProcessing = false
    }
}
