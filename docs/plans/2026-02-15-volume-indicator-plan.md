# Volume Indicator Ring Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 録音中にマイクボタンの外周にリングを表示し、音量レベルを赤→黄→緑の色変化で示す

**Architecture:** AudioRecorder に getAmplitude() を追加し、MicButtonRingView（FrameLayout）でリング描画。VoiceInputIME が 100ms ポーリングで音量を MicButtonRingView に渡す。色補間ロジックは MicButtonRingView 内の companion object に切り出してユニットテスト可能にする。

**Tech Stack:** Android Canvas API, MediaRecorder.maxAmplitude, MockK

---

### Task 1: AudioRecorder — getAmplitude()

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AudioRecorder.kt`
- Modify: `app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt`

**Step 1: Write the failing tests**

```kotlin
// AudioRecorderTest.kt に追加

@Test
fun `getAmplitude returns 0 when not recording`() {
    val recorder = AudioRecorder(outputDir)
    assertEquals(0, recorder.getAmplitude())
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioRecorderTest" --info 2>&1 | tail -20`
Expected: FAIL — `getAmplitude` not defined

**Step 3: Write minimal implementation**

```kotlin
// AudioRecorder.kt に追加

fun getAmplitude(): Int {
    if (!isRecording) return 0
    return try {
        mediaRecorder?.maxAmplitude ?: 0
    } catch (e: Exception) {
        0
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioRecorderTest" --info 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app && git add app/src/main/java/com/example/voiceinput/AudioRecorder.kt app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt && git commit -m "feat: add getAmplitude() to AudioRecorder"
```

---

### Task 2: VoiceInputProcessor — getAmplitude() 委譲

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt`

**Step 1: Write the failing test**

```kotlin
// VoiceInputProcessorTest.kt に追加

@Test
fun `getAmplitude delegates to audioRecorder`() {
    every { audioRecorder.getAmplitude() } returns 3000
    assertEquals(3000, processor.getAmplitude())
    verify { audioRecorder.getAmplitude() }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest" --info 2>&1 | tail -20`
Expected: FAIL — `getAmplitude` not defined on VoiceInputProcessor

**Step 3: Write minimal implementation**

```kotlin
// VoiceInputProcessor.kt に追加

fun getAmplitude(): Int {
    return audioRecorder.getAmplitude()
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest" --info 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app && git add app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt && git commit -m "feat: add getAmplitude() to VoiceInputProcessor"
```

---

### Task 3: MicButtonRingView — 色補間ロジック

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/MicButtonRingView.kt`
- Create: `app/src/test/java/com/example/voiceinput/MicButtonRingViewTest.kt`

**Step 1: Write the failing tests**

```kotlin
// MicButtonRingViewTest.kt

package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class MicButtonRingViewTest {

    @Test
    fun `colorForAmplitude returns red for 0`() {
        val color = MicButtonRingView.colorForAmplitude(0.0f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns red for low amplitude`() {
        val color = MicButtonRingView.colorForAmplitude(0.2f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns yellow for mid amplitude`() {
        val color = MicButtonRingView.colorForAmplitude(0.5f)
        // Halfway between red and yellow
        // Red #FF5252 -> Yellow #FFC107
        // R: 255->255, G: 82->193, B: 82->7
        // At 0.5: ratio = (0.5 - 0.3) / (0.7 - 0.3) = 0.5
        // G: 82 + (193-82)*0.5 = 137, B: 82 + (7-82)*0.5 = 44
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        assertEquals(255, r)
        assertTrue("Green should be between 82 and 193, was $g", g in 100..170)
    }

    @Test
    fun `colorForAmplitude returns green for 1`() {
        val color = MicButtonRingView.colorForAmplitude(1.0f)
        assertEquals(0xFF4CAF50.toInt(), color)
    }

    @Test
    fun `colorForAmplitude clamps below 0`() {
        val color = MicButtonRingView.colorForAmplitude(-0.5f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude clamps above 1`() {
        val color = MicButtonRingView.colorForAmplitude(1.5f)
        assertEquals(0xFF4CAF50.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns yellow at 0_7 boundary`() {
        val color = MicButtonRingView.colorForAmplitude(0.7f)
        assertEquals(0xFFFFC107.toInt(), color)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.MicButtonRingViewTest" --info 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

Note: `MicButtonRingView` は Android の `FrameLayout` を継承するが、色補間ロジックは `companion object` の pure function として切り出す。View 描画部分はこのタスクでは空の状態にしておき、Task 4 で実装する。

```kotlin
// MicButtonRingView.kt

package com.example.voiceinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

class MicButtonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var amplitude: Float = 0f
    private var ringVisible: Boolean = false
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val ringRect = RectF()

    init {
        setWillNotDraw(false)
    }

    fun setAmplitude(level: Float) {
        amplitude = level.coerceIn(0f, 1f)
        ringPaint.color = colorForAmplitude(amplitude)
        invalidate()
    }

    fun showRing() {
        ringVisible = true
        invalidate()
    }

    fun hideRing() {
        ringVisible = false
        amplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ringVisible) return

        val strokeHalf = ringPaint.strokeWidth / 2
        ringRect.set(strokeHalf, strokeHalf, width - strokeHalf, height - strokeHalf)
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
    }

    companion object {
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_YELLOW = 0xFFFFC107.toInt()
        private const val COLOR_GREEN = 0xFF4CAF50.toInt()

        fun colorForAmplitude(amplitude: Float): Int {
            val clamped = amplitude.coerceIn(0f, 1f)
            return when {
                clamped <= 0.3f -> COLOR_RED
                clamped <= 0.7f -> {
                    val ratio = (clamped - 0.3f) / 0.4f
                    interpolateColor(COLOR_RED, COLOR_YELLOW, ratio)
                }
                else -> {
                    val ratio = (clamped - 0.7f) / 0.3f
                    interpolateColor(COLOR_YELLOW, COLOR_GREEN, ratio)
                }
            }
        }

        private fun interpolateColor(from: Int, to: Int, ratio: Float): Int {
            val a = 0xFF
            val r = ((from shr 16) and 0xFF) + (((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * ratio
            val g = ((from shr 8) and 0xFF) + (((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * ratio
            val b = (from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * ratio
            return (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.MicButtonRingViewTest" --info 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app && git add app/src/main/java/com/example/voiceinput/MicButtonRingView.kt app/src/test/java/com/example/voiceinput/MicButtonRingViewTest.kt && git commit -m "feat: add MicButtonRingView with color interpolation"
```

---

### Task 4: Layout — ime_voice_input.xml 変更

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`

**Step 1: Replace ImageView with MicButtonRingView wrapping ImageView**

Replace the `ImageView` block at the bottom of `ime_voice_input.xml`:

```xml
        <com.example.voiceinput.MicButtonRingView
            android:id="@+id/micButtonRing"
            android:layout_width="64dp"
            android:layout_height="64dp"
            android:layout_marginStart="8dp">

            <ImageView
                android:id="@+id/imeMicButton"
                android:layout_width="56dp"
                android:layout_height="56dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_mic"
                android:background="@drawable/mic_button_background"
                android:padding="14dp"
                android:contentDescription="@string/ime_mic_description" />

        </com.example.voiceinput.MicButtonRingView>
```

**Step 2: Build to verify layout compiles**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app && git add app/src/main/res/layout/ime_voice_input.xml && git commit -m "feat: wrap mic button with MicButtonRingView in layout"
```

---

### Task 5: VoiceInputIME — ポーリング統合

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: Add micButtonRing field and amplitude polling**

Add field:
```kotlin
private var micButtonRing: MicButtonRingView? = null
private var amplitudePoller: Runnable? = null
private companion object {
    const val LONG_PRESS_DELAY = 500L
    const val AMPLITUDE_POLL_INTERVAL = 100L
    const val AMPLITUDE_THRESHOLD = 5000f
}
```

In `onCreateInputView()`, after finding `micButton`, add:
```kotlin
micButtonRing = view.findViewById(R.id.micButtonRing)
```

Add polling methods:
```kotlin
private fun startAmplitudePolling() {
    micButtonRing?.showRing()
    amplitudePoller = object : Runnable {
        override fun run() {
            val amplitude = processor?.getAmplitude() ?: 0
            val normalized = (amplitude / AMPLITUDE_THRESHOLD).coerceIn(0f, 1f)
            micButtonRing?.setAmplitude(normalized)
            handler.postDelayed(this, AMPLITUDE_POLL_INTERVAL)
        }
    }
    handler.post(amplitudePoller!!)
}

private fun stopAmplitudePolling() {
    amplitudePoller?.let { handler.removeCallbacks(it) }
    amplitudePoller = null
    micButtonRing?.hideRing()
}
```

In `onMicPressed()`, after `micButton?.alpha = 0.5f`:
```kotlin
startAmplitudePolling()
```

In `onMicReleased()`, before `micButton?.alpha = 1.0f`:
```kotlin
stopAmplitudePolling()
```

**Step 2: Build and run all tests**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --info 2>&1 | tail -20`
Expected: All tests PASS

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app && git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt && git commit -m "feat: add amplitude polling to VoiceInputIME"
```

---

### Task 6: 全テスト実行 + APK コピー

**Step 1: Run all tests**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --info 2>&1 | tail -30`
Expected: All tests PASS

**Step 2: Build APK**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Copy APK to sync directory**

Run: `cp /Users/j/Area/tdd/voice-input-android-app/app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 4: Commit (if any remaining changes)**

Verify clean state: `cd /Users/j/Area/tdd/voice-input-android-app && git status`
