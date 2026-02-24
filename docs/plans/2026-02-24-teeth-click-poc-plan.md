# 歯カチカチ検出 PoC 実装計画

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Shokzの骨伝導マイクで歯カチカチ音がリアルタイムで検出可能か可視化するPoC

**Architecture:** ForegroundServiceは使わない（PoCなので）。単純なActivityでBluetooth SCO接続→AudioRecordでキャプチャ→リアルタイムでWaveformViewとRmsGraphViewに描画。RMS計算とピーク検出のロジックはテスト可能なクラスに分離。

**Tech Stack:** Kotlin, AudioRecord, Bluetooth SCO, Canvas描画, JUnit 4

---

### Task 1: RmsCalculator — ウィンドウRMS計算とピーク検出

PoCの中核ロジック。PCMバッファからRMS値を計算し、スパイク（歯カチカチ）を検出する。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/poc/RmsCalculator.kt`
- Create: `app/src/test/java/com/example/voiceinput/poc/RmsCalculatorTest.kt`

**Step 1: Write the failing test — RMS計算**

```kotlin
// app/src/test/java/com/example/voiceinput/poc/RmsCalculatorTest.kt
package com.example.voiceinput.poc

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RmsCalculatorTest {

    @Test
    fun `calculateRms returns zero for silence`() {
        val calc = RmsCalculator()
        val pcm = ByteArray(320) // 10ms at 16kHz, 16bit
        val rms = calc.calculateRms(pcm)
        assertEquals(0.0, rms, 0.01)
    }

    @Test
    fun `calculateRms returns correct value for known signal`() {
        val calc = RmsCalculator()
        // All samples = 1000 → RMS = 1000
        val samples = ShortArray(160) { 1000 }
        val pcm = shortsToBytes(samples)
        val rms = calc.calculateRms(pcm)
        assertEquals(1000.0, rms, 1.0)
    }

    @Test
    fun `calculateRms returns correct value for sine wave`() {
        val calc = RmsCalculator()
        val amplitude = 10000
        val samples = ShortArray(160) { i ->
            (amplitude * kotlin.math.sin(2.0 * Math.PI * i / 160)).toInt().toShort()
        }
        val pcm = shortsToBytes(samples)
        val rms = calc.calculateRms(pcm)
        // RMS of sine wave = amplitude / sqrt(2) ≈ 7071
        assertEquals(amplitude / kotlin.math.sqrt(2.0), rms, 200.0)
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return bytes
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.poc.RmsCalculatorTest" 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/example/voiceinput/poc/RmsCalculator.kt
package com.example.voiceinput.poc

import java.nio.ByteBuffer
import java.nio.ByteOrder

class RmsCalculator {

    fun calculateRms(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.poc.RmsCalculatorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Add peak detection tests**

```kotlin
// Append to RmsCalculatorTest.kt

    @Test
    fun `detectPeak returns true when rms exceeds threshold times average`() {
        val calc = RmsCalculator()
        // Feed quiet history
        repeat(10) { calc.addRms(100.0) }
        // Spike
        assertTrue(calc.detectPeak(500.0))
    }

    @Test
    fun `detectPeak returns false for normal values`() {
        val calc = RmsCalculator()
        repeat(10) { calc.addRms(100.0) }
        assertFalse(calc.detectPeak(120.0))
    }

    @Test
    fun `detectPeak returns false when no history`() {
        val calc = RmsCalculator()
        assertFalse(calc.detectPeak(500.0))
    }

    @Test
    fun `addRms maintains sliding window`() {
        val calc = RmsCalculator(historySize = 5)
        repeat(10) { calc.addRms(100.0) }
        // Should only keep last 5
        assertEquals(5, calc.historySize())
    }
```

**Step 6: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.poc.RmsCalculatorTest" 2>&1 | tail -20`
Expected: FAIL — addRms/detectPeak not found

**Step 7: Implement peak detection**

```kotlin
// Update RmsCalculator.kt
package com.example.voiceinput.poc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList

class RmsCalculator(
    private val maxHistorySize: Int = 50,
    private val peakThresholdMultiplier: Double = 3.0
) {
    private val rmsHistory = LinkedList<Double>()

    fun calculateRms(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }

    fun addRms(rms: Double) {
        rmsHistory.addLast(rms)
        if (rmsHistory.size > maxHistorySize) {
            rmsHistory.removeFirst()
        }
    }

    fun detectPeak(currentRms: Double): Boolean {
        if (rmsHistory.size < 3) return false
        val average = rmsHistory.average()
        if (average < 1.0) return false
        return currentRms > average * peakThresholdMultiplier
    }

    fun historySize(): Int = rmsHistory.size
}
```

**Step 8: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.poc.RmsCalculatorTest" 2>&1 | tail -20`
Expected: PASS

**Step 9: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/poc/RmsCalculator.kt app/src/test/java/com/example/voiceinput/poc/RmsCalculatorTest.kt
git commit -m "feat(poc): add RmsCalculator with windowed peak detection"
```

---

### Task 2: WaveformView — リアルタイム波形描画

PCM振幅をリアルタイムで横スクロール表示するカスタムView。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/poc/WaveformView.kt`

**Step 1: Implement WaveformView**

```kotlin
// app/src/main/java/com/example/voiceinput/poc/WaveformView.kt
package com.example.voiceinput.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val centerLinePaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
    }

    private val maxSamples = 3 * 16000 // 3 seconds at 16kHz
    private val samples = ShortArray(maxSamples)
    private var writeIndex = 0
    private var sampleCount = 0

    fun pushSamples(newSamples: ShortArray) {
        for (s in newSamples) {
            samples[writeIndex] = s
            writeIndex = (writeIndex + 1) % maxSamples
            if (sampleCount < maxSamples) sampleCount++
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        canvas.drawColor(Color.parseColor("#1A1A1A"))
        canvas.drawLine(0f, centerY, w, centerY, centerLinePaint)

        if (sampleCount == 0) return

        val displayCount = sampleCount.coerceAtMost(maxSamples)
        val startIndex = if (sampleCount >= maxSamples) writeIndex else 0
        val samplesPerPixel = (displayCount / w).toInt().coerceAtLeast(1)

        for (x in 0 until w.toInt()) {
            val sampleOffset = (x * displayCount / w).toInt()
            val idx = (startIndex + sampleOffset) % maxSamples

            var min = samples[idx]
            var max = samples[idx]
            for (j in 1 until samplesPerPixel.coerceAtMost(displayCount - sampleOffset)) {
                val s = samples[(idx + j) % maxSamples]
                if (s < min) min = s
                if (s > max) max = s
            }

            val yMin = centerY - (max.toFloat() / 32768f) * centerY
            val yMax = centerY - (min.toFloat() / 32768f) * centerY
            canvas.drawLine(x.toFloat(), yMin, x.toFloat(), yMax, waveformPaint)
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/poc/WaveformView.kt
git commit -m "feat(poc): add WaveformView for real-time PCM waveform display"
```

---

### Task 3: RmsGraphView — RMS時系列グラフ

RMS値を折れ線グラフで表示するカスタムView。ピーク検出時に赤マーカーを表示。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/poc/RmsGraphView.kt`

**Step 1: Implement RmsGraphView**

```kotlin
// app/src/main/java/com/example/voiceinput/poc/RmsGraphView.kt
package com.example.voiceinput.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RmsGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val peakPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        strokeWidth = 6f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    data class RmsEntry(val rms: Double, val isPeak: Boolean, val timestamp: Long)

    private val maxEntries = 300 // ~6 seconds at 50Hz (20ms window)
    private val entries = ArrayDeque<RmsEntry>(maxEntries)
    private var maxRms = 1000.0

    fun addRms(rms: Double, isPeak: Boolean) {
        entries.addLast(RmsEntry(rms, isPeak, System.currentTimeMillis()))
        if (entries.size > maxEntries) entries.removeFirst()
        if (rms > maxRms) maxRms = rms
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.parseColor("#1A1A1A"))

        if (entries.isEmpty()) return

        val path = Path()
        val entryList = entries.toList()
        val stepX = w / maxEntries

        for (i in entryList.indices) {
            val x = i * stepX
            val y = h - (entryList[i].rms / maxRms * h * 0.9).toFloat()

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

            if (entryList[i].isPeak) {
                canvas.drawCircle(x, y, 6f, peakPaint)
            }
        }
        canvas.drawPath(path, linePaint)

        // Current RMS value text
        val lastRms = entryList.last().rms
        canvas.drawText("RMS: %.0f".format(lastRms), 10f, 30f, textPaint)
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/poc/RmsGraphView.kt
git commit -m "feat(poc): add RmsGraphView for RMS time series with peak markers"
```

---

### Task 4: レイアウトとActivity

Bluetooth SCO接続、AudioRecordキャプチャ、View描画を結合するActivity。

**Files:**
- Create: `app/src/main/res/layout/activity_teeth_click_poc.xml`
- Create: `app/src/main/java/com/example/voiceinput/poc/TeethClickPocActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Create layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#1A1A1A">

    <TextView
        android:id="@+id/scoStatus"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Bluetooth SCO: disconnected"
        android:textColor="#FFFFFF"
        android:textSize="14sp"
        android:padding="8dp" />

    <com.example.voiceinput.poc.WaveformView
        android:id="@+id/waveformView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="2" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="#333333" />

    <com.example.voiceinput.poc.RmsGraphView
        android:id="@+id/rmsGraphView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="2" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="#333333" />

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <TextView
            android:id="@+id/peakLog"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#AAAAAA"
            android:textSize="12sp"
            android:fontFamily="monospace"
            android:padding="8dp" />
    </ScrollView>

</LinearLayout>
```

**Step 2: Create Activity**

```kotlin
// app/src/main/java/com/example/voiceinput/poc/TeethClickPocActivity.kt
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
        registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
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
        // Convert to shorts for waveform
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        waveformView.pushSamples(samples)

        // Calculate RMS
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
```

**Step 3: Update AndroidManifest.xml**

Add `BLUETOOTH_CONNECT` permission and PoC Activity:

```xml
<!-- Add after existing permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Add inside <application>, after MainActivity -->
<activity
    android:name=".poc.TeethClickPocActivity"
    android:exported="false"
    android:label="Teeth Click PoC" />
```

**Step 4: Build and verify**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_teeth_click_poc.xml app/src/main/java/com/example/voiceinput/poc/TeethClickPocActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(poc): add TeethClickPocActivity with Bluetooth SCO audio capture"
```

---

### Task 5: MainActivityにPoC起動ボタンを追加

設定画面からPoCに遷移するボタンを追加する。

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/voiceinput/MainActivity.kt`

**Step 1: Add button to layout**

`activity_main.xml` の末尾（ntfySaveButton の後）に追加:

```xml
<!-- Teeth Click PoC -->
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="実験"
    android:textSize="18sp"
    android:layout_marginTop="32dp"
    android:textStyle="bold" />

<com.google.android.material.button.MaterialButton
    android:id="@+id/teethClickPocButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="歯カチカチ検出 PoC"
    android:layout_marginTop="8dp"
    style="@style/Widget.MaterialComponents.Button.OutlinedButton" />
```

**Step 2: Wire button in MainActivity**

`MainActivity.kt` の `onCreate` 末尾に追加:

```kotlin
findViewById<Button>(R.id.teethClickPocButton).setOnClickListener {
    startActivity(Intent(this, com.example.voiceinput.poc.TeethClickPocActivity::class.java))
}
```

**Step 3: Build and verify**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 4: Run all tests**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: all tests PASS

**Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/example/voiceinput/MainActivity.kt
git commit -m "feat(poc): add teeth click PoC launch button to settings"
```

---

### Task 6: APK生成とデプロイ

APKをビルドして同期先にコピー。

**Step 1: Build debug APK**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`

**Step 2: Copy to sync directory**

Run: `cp /Users/j/Area/tdd/voice-input-android-app/app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 3: Inform user**

APKがSyncフォルダにコピーされた旨を伝える。ユーザーが手動でインストール・テストする。
