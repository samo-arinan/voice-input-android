package com.example.voiceinput.poc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.voiceinput.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TeethClickPocActivity : AppCompatActivity() {

    private lateinit var waveformView: WaveformView
    private lateinit var rmsGraphView: RmsGraphView
    private lateinit var peakLog: TextView
    private lateinit var scoStatus: TextView
    private lateinit var logScrollView: ScrollView

    private val rmsCalculator = RmsCalculator()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var capturing = false

    private lateinit var audioManager: AudioManager

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            runOnUiThread {
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        scoStatus.text = "Bluetooth SCO: connected"
                        startCapture()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        scoStatus.text = "Bluetooth SCO: disconnected"
                    }
                    else -> {
                        scoStatus.text = "Bluetooth SCO: state=$state"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teeth_click_poc)

        waveformView = findViewById(R.id.waveformView)
        rmsGraphView = findViewById(R.id.rmsGraphView)
        peakLog = findViewById(R.id.peakLog)
        scoStatus = findViewById(R.id.scoStatus)
        logScrollView = peakLog.parent as ScrollView

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        if (checkPermissions()) {
            startBluetoothSco()
        }
    }

    private fun checkPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBluetoothSco()
        } else {
            scoStatus.text = "Permission denied"
        }
    }

    private fun startBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
        }
        scoStatus.text = "Bluetooth SCO: connecting..."
        audioManager.startBluetoothSco()
    }

    private fun startCapture() {
        if (capturing) return

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            scoStatus.text = "AudioRecord buffer error"
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            scoStatus.text = "SecurityException: ${e.message}"
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            scoStatus.text = "AudioRecord init failed"
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        capturing = true

        captureThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (capturing) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    processAudioChunk(chunk)
                }
            }
        }.also { it.start() }
    }

    private fun processAudioChunk(pcmData: ByteArray) {
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        waveformView.pushSamples(samples)

        val rms = rmsCalculator.calculateRms(pcmData)
        val isPeak = rmsCalculator.detectPeak(rms)
        rmsCalculator.addRms(rms)

        rmsGraphView.addRms(rms, isPeak)

        if (isPeak) {
            val timestamp = System.currentTimeMillis()
            runOnUiThread {
                peakLog.append("[%tT.%tL] PEAK RMS=%.0f\n".format(timestamp, timestamp, rms))
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun stopCapture() {
        capturing = false
        captureThread?.join(2000)
        captureThread = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }

    override fun onDestroy() {
        stopCapture()
        try {
            audioManager.stopBluetoothSco()
            unregisterReceiver(scoReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
