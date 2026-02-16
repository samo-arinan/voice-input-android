# 音声コマンド認識 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 登録済み音声コマンドをオンデバイスMFCC+DTWで認識し、マッチ時に即実行する

**Architecture:** WAV→MFCC特徴量抽出(純Kotlin)→DTW距離照合→閾値判定→コマンド実行 or 通常Whisper変換。サンプル録音時にMFCCをキャッシュし、照合時はキャッシュから読む。

**Tech Stack:** Kotlin, JUnit4, mockk。外部ML依存なし。

---

## Task 1: MfccExtractor — WAVパース＋フレーム分割

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/MfccExtractor.kt`
- Test: `app/src/test/java/com/example/voiceinput/MfccExtractorTest.kt`

**Step 1: Write failing test — WAVパース**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MfccExtractorTest {

    private fun createWavBytes(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmData.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcmData.size)
        return header.array() + pcmData
    }

    @Test
    fun `parsePcm extracts samples from WAV bytes`() {
        val samples = shortArrayOf(100, 200, -100, -200)
        val wav = createWavBytes(samples)
        val result = MfccExtractor.parsePcm(wav)
        assertEquals(4, result.size)
        assertEquals(100.0f, result[0], 0.01f)
        assertEquals(-200.0f, result[3], 0.01f)
    }

    @Test
    fun `frameSignal splits into overlapping frames`() {
        // 800 samples = 2 full frames at 400 frame size, 160 stride
        // frames: 0-399, 160-559, 320-719, (400-799 is last full frame)
        val signal = FloatArray(800) { 1.0f }
        val frames = MfccExtractor.frameSignal(signal, frameSize = 400, stride = 160)
        // (800 - 400) / 160 + 1 = 3.5 → 3 frames (only full frames)
        assertEquals(3, frames.size)
        assertEquals(400, frames[0].size)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.MfccExtractorTest"`
Expected: FAIL — `MfccExtractor` not found

**Step 3: Write minimal implementation**

```kotlin
package com.example.voiceinput

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MfccExtractor {

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 400    // 25ms at 16kHz
    const val STRIDE = 160        // 10ms at 16kHz
    const val NUM_MFCC = 13
    const val FFT_SIZE = 512
    const val NUM_MEL_FILTERS = 26
    const val MEL_LOW_FREQ = 300.0
    const val MEL_HIGH_FREQ = 8000.0

    fun parsePcm(wavBytes: ByteArray): FloatArray {
        // Skip 44-byte WAV header
        val pcmData = wavBytes.copyOfRange(44, wavBytes.size)
        val shortBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuf.remaining())
        shortBuf.get(samples)
        return FloatArray(samples.size) { samples[it].toFloat() }
    }

    fun frameSignal(signal: FloatArray, frameSize: Int = FRAME_SIZE, stride: Int = STRIDE): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + frameSize <= signal.size) {
            frames.add(signal.copyOfRange(start, start + frameSize))
            start += stride
        }
        return frames
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.MfccExtractorTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/MfccExtractor.kt \
       app/src/test/java/com/example/voiceinput/MfccExtractorTest.kt
git commit -m "feat: add MfccExtractor WAV parse and frame splitting"
```

---

## Task 2: MfccExtractor — FFT + メルフィルタバンク + MFCC

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/MfccExtractor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/MfccExtractorTest.kt`

**Step 1: Write failing tests**

```kotlin
@Test
fun `hammingWindow applies correct windowing`() {
    val frame = FloatArray(4) { 1.0f }
    val windowed = MfccExtractor.applyHammingWindow(frame)
    // Hamming: 0.54 - 0.46*cos(2*pi*n/(N-1))
    // n=0: 0.54 - 0.46*cos(0) = 0.08
    assertEquals(0.08f, windowed[0], 0.01f)
    // n=1: 0.54 - 0.46*cos(2*pi/3) = 0.54+0.23 = 0.77
    assertEquals(0.77f, windowed[1], 0.01f)
}

@Test
fun `fft produces power spectrum of correct size`() {
    val frame = FloatArray(512) { 0f }
    frame[0] = 1.0f // impulse
    val power = MfccExtractor.powerSpectrum(frame, 512)
    // FFT of impulse = flat spectrum
    assertEquals(257, power.size) // FFT_SIZE/2 + 1
    // All bins should be equal for impulse
    val expected = 1.0f / 512f
    assertEquals(expected, power[0], 0.001f)
    assertEquals(expected, power[1], 0.001f)
}

@Test
fun `extract produces correct dimensions`() {
    // 1 second of silence = 16000 samples
    val samples = ShortArray(16000) { 0 }
    val wav = createWavBytes(samples)
    val mfcc = MfccExtractor.extract(wav)
    // (16000 - 400) / 160 + 1 = 98 frames
    assertEquals(98, mfcc.size)
    assertEquals(13, mfcc[0].size)
}

@Test
fun `extract of sine wave produces non-zero MFCCs`() {
    // 440Hz sine wave, 1 second
    val samples = ShortArray(16000) { i ->
        (16000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toInt().toShort()
    }
    val wav = createWavBytes(samples)
    val mfcc = MfccExtractor.extract(wav)
    // Middle frame should have non-trivial MFCC values
    val midFrame = mfcc[mfcc.size / 2]
    val energy = midFrame.map { it * it }.sum()
    assertTrue("MFCC energy should be non-trivial", energy > 1.0f)
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.MfccExtractorTest"`
Expected: FAIL — methods not found

**Step 3: Add implementation to MfccExtractor**

Add these methods to `MfccExtractor`:

```kotlin
fun applyHammingWindow(frame: FloatArray): FloatArray {
    val n = frame.size
    return FloatArray(n) { i ->
        frame[i] * (0.54f - 0.46f * kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat())
    }
}

fun applyPreEmphasis(signal: FloatArray, coeff: Float = 0.97f): FloatArray {
    val result = FloatArray(signal.size)
    result[0] = signal[0]
    for (i in 1 until signal.size) {
        result[i] = signal[i] - coeff * signal[i - 1]
    }
    return result
}

fun powerSpectrum(frame: FloatArray, fftSize: Int = FFT_SIZE): FloatArray {
    // Zero-pad frame to fftSize
    val padded = FloatArray(fftSize)
    frame.copyInto(padded, 0, 0, minOf(frame.size, fftSize))

    // In-place FFT (Cooley-Tukey radix-2)
    val real = padded.copyOf()
    val imag = FloatArray(fftSize)
    fft(real, imag)

    // Power spectrum: |X[k]|^2 / N
    val halfSize = fftSize / 2 + 1
    return FloatArray(halfSize) { k ->
        (real[k] * real[k] + imag[k] * imag[k]) / fftSize.toFloat()
    }
}

private fun fft(real: FloatArray, imag: FloatArray) {
    val n = real.size
    // Bit reversal
    var j = 0
    for (i in 0 until n) {
        if (i < j) {
            var temp = real[i]; real[i] = real[j]; real[j] = temp
            temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
        }
        var m = n / 2
        while (m >= 1 && j >= m) { j -= m; m /= 2 }
        j += m
    }
    // Butterfly
    var step = 1
    while (step < n) {
        val halfStep = step
        step *= 2
        val angleStep = -Math.PI / halfStep
        for (k in 0 until halfStep) {
            val angle = k * angleStep
            val wr = kotlin.math.cos(angle).toFloat()
            val wi = kotlin.math.sin(angle).toFloat()
            var i = k
            while (i < n) {
                val j2 = i + halfStep
                val tr = wr * real[j2] - wi * imag[j2]
                val ti = wr * imag[j2] + wi * real[j2]
                real[j2] = real[i] - tr
                imag[j2] = imag[i] - ti
                real[i] += tr
                imag[i] += ti
                i += step
            }
        }
    }
}

private fun melFilterbank(powerSpec: FloatArray): FloatArray {
    val numBins = powerSpec.size
    fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    val melLow = hzToMel(MEL_LOW_FREQ)
    val melHigh = hzToMel(MEL_HIGH_FREQ)
    val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
        melLow + i * (melHigh - melLow) / (NUM_MEL_FILTERS + 1)
    }
    val binPoints = IntArray(melPoints.size) { i ->
        ((melToHz(melPoints[i]) * FFT_SIZE / SAMPLE_RATE).toInt()).coerceIn(0, numBins - 1)
    }

    return FloatArray(NUM_MEL_FILTERS) { m ->
        var sum = 0.0f
        val left = binPoints[m]
        val center = binPoints[m + 1]
        val right = binPoints[m + 2]
        for (k in left until center) {
            if (center > left) {
                sum += powerSpec[k] * (k - left).toFloat() / (center - left)
            }
        }
        for (k in center until right) {
            if (right > center) {
                sum += powerSpec[k] * (right - k).toFloat() / (right - center)
            }
        }
        sum
    }
}

private fun dct(input: FloatArray, numCoeffs: Int = NUM_MFCC): FloatArray {
    val n = input.size
    return FloatArray(numCoeffs) { k ->
        var sum = 0.0f
        for (i in 0 until n) {
            sum += input[i] * kotlin.math.cos(Math.PI * k * (2 * i + 1) / (2.0 * n)).toFloat()
        }
        sum
    }
}

fun extract(wavBytes: ByteArray): Array<FloatArray> {
    val rawSignal = parsePcm(wavBytes)
    val signal = applyPreEmphasis(rawSignal)
    val frames = frameSignal(signal)

    return Array(frames.size) { i ->
        val windowed = applyHammingWindow(frames[i])
        val power = powerSpectrum(windowed)
        val melEnergies = melFilterbank(power)
        // Log compression (floor to avoid log(0))
        val logMel = FloatArray(melEnergies.size) { j ->
            kotlin.math.ln(melEnergies[j].coerceAtLeast(1e-10f))
        }
        dct(logMel)
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.MfccExtractorTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/MfccExtractor.kt \
       app/src/test/java/com/example/voiceinput/MfccExtractorTest.kt
git commit -m "feat: add FFT, mel filterbank, and full MFCC extraction"
```

---

## Task 3: DtwMatcher — DTW距離計算

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/DtwMatcher.kt`
- Test: `app/src/test/java/com/example/voiceinput/DtwMatcherTest.kt`

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class DtwMatcherTest {

    @Test
    fun `dtw distance of identical sequences is zero`() {
        val seq = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val dist = DtwMatcher.dtwDistance(seq, seq)
        assertEquals(0.0f, dist, 0.001f)
    }

    @Test
    fun `dtw distance of different sequences is positive`() {
        val a = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
        val b = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f))
        val dist = DtwMatcher.dtwDistance(a, b)
        assertTrue(dist > 0)
    }

    @Test
    fun `dtw handles time-stretched sequences`() {
        val short = arrayOf(floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f))
        // Same pattern but repeated (time-stretched)
        val long = arrayOf(
            floatArrayOf(1f), floatArrayOf(1f),
            floatArrayOf(2f), floatArrayOf(2f),
            floatArrayOf(3f), floatArrayOf(3f)
        )
        val distStretched = DtwMatcher.dtwDistance(short, long)
        // Should be small since content matches
        val different = arrayOf(floatArrayOf(9f), floatArrayOf(8f), floatArrayOf(7f))
        val distDifferent = DtwMatcher.dtwDistance(short, different)
        assertTrue("Stretched should be closer than different",
            distStretched < distDifferent)
    }

    @Test
    fun `dtw is symmetric`() {
        val a = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val b = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        val dAB = DtwMatcher.dtwDistance(a, b)
        val dBA = DtwMatcher.dtwDistance(b, a)
        assertEquals(dAB, dBA, 0.001f)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.DtwMatcherTest"`
Expected: FAIL — `DtwMatcher` not found

**Step 3: Write implementation**

```kotlin
package com.example.voiceinput

import kotlin.math.sqrt

object DtwMatcher {

    fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        // Cost matrix with +1 size for boundary
        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dtw[0][0] = 0f

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = euclideanDist(a[i - 1], b[j - 1])
                dtw[i][j] = cost + minOf(
                    dtw[i - 1][j],     // insertion
                    dtw[i][j - 1],     // deletion
                    dtw[i - 1][j - 1]  // match
                )
            }
        }

        return dtw[n][m] / (n + m) // Normalize by path length
    }

    private fun euclideanDist(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.DtwMatcherTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/DtwMatcher.kt \
       app/src/test/java/com/example/voiceinput/DtwMatcherTest.kt
git commit -m "feat: add DTW distance matcher for MFCC sequences"
```

---

## Task 4: CommandMatcher — コマンド照合ロジック

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/CommandMatcher.kt`
- Test: `app/src/test/java/com/example/voiceinput/CommandMatcherTest.kt`

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandMatcherTest {

    private val sampleMfcc = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
    private val differentMfcc = arrayOf(floatArrayOf(99f, 99f), floatArrayOf(99f, 99f))

    private fun cmd(id: String, threshold: Float = 10f) = VoiceCommand(
        id = id, label = id, text = "/$id\n", threshold = threshold
    )

    @Test
    fun `match returns command when distance below threshold`() {
        val commands = listOf(cmd("exit", threshold = 10f))
        val samples = mapOf("exit" to listOf(sampleMfcc))
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc) // identical → distance ~0
        assertNotNull(result)
        assertEquals("exit", result!!.command.id)
        assertTrue(result.distance < 10f)
    }

    @Test
    fun `match returns null when no commands registered`() {
        val matcher = CommandMatcher(emptyList(), emptyMap())
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }

    @Test
    fun `match returns null when distance above threshold`() {
        val commands = listOf(cmd("exit", threshold = 0.001f))
        val samples = mapOf("exit" to listOf(differentMfcc))
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }

    @Test
    fun `match picks closest command among multiple`() {
        val commands = listOf(cmd("exit"), cmd("hello"))
        val samples = mapOf(
            "exit" to listOf(differentMfcc),
            "hello" to listOf(sampleMfcc) // identical to input
        )
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNotNull(result)
        assertEquals("hello", result!!.command.id)
    }

    @Test
    fun `match uses minimum distance across multiple samples`() {
        val commands = listOf(cmd("exit"))
        val samples = mapOf(
            "exit" to listOf(differentMfcc, sampleMfcc) // second sample matches
        )
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNotNull(result)
        assertTrue(result!!.distance < 1f)
    }

    @Test
    fun `match skips commands with no samples`() {
        val commands = listOf(cmd("exit"))
        val samples = mapOf<String, List<Array<FloatArray>>>()
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandMatcherTest"`
Expected: FAIL — `CommandMatcher` not found

**Step 3: Write implementation**

```kotlin
package com.example.voiceinput

class CommandMatcher(
    private val commands: List<VoiceCommand>,
    private val sampleMfccs: Map<String, List<Array<FloatArray>>>
) {

    data class MatchResult(
        val command: VoiceCommand,
        val distance: Float
    )

    fun match(inputMfcc: Array<FloatArray>): MatchResult? {
        var bestMatch: MatchResult? = null

        for (cmd in commands) {
            val samples = sampleMfccs[cmd.id] ?: continue
            if (samples.isEmpty()) continue

            val minDist = samples.minOf { DtwMatcher.dtwDistance(inputMfcc, it) }

            if (minDist < cmd.threshold) {
                if (bestMatch == null || minDist < bestMatch.distance) {
                    bestMatch = MatchResult(cmd, minDist)
                }
            }
        }

        return bestMatch
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandMatcherTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/CommandMatcher.kt \
       app/src/test/java/com/example/voiceinput/CommandMatcherTest.kt
git commit -m "feat: add CommandMatcher with multi-sample DTW matching"
```

---

## Task 5: CommandExecutor — コマンドテキスト送信

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/CommandExecutor.kt`
- Test: `app/src/test/java/com/example/voiceinput/CommandExecutorTest.kt`

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandExecutorTest {

    @Test
    fun `parseActions splits text and newlines`() {
        val actions = CommandExecutor.parseActions("hello\nworld\n")
        assertEquals(
            listOf(
                CommandExecutor.Action.Text("hello"),
                CommandExecutor.Action.Enter,
                CommandExecutor.Action.Text("world"),
                CommandExecutor.Action.Enter
            ),
            actions
        )
    }

    @Test
    fun `parseActions handles text without newlines`() {
        val actions = CommandExecutor.parseActions("hello")
        assertEquals(listOf(CommandExecutor.Action.Text("hello")), actions)
    }

    @Test
    fun `parseActions handles only newline`() {
        val actions = CommandExecutor.parseActions("\n")
        assertEquals(listOf(CommandExecutor.Action.Enter), actions)
    }

    @Test
    fun `parseActions handles empty text`() {
        val actions = CommandExecutor.parseActions("")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `parseActions handles multiple consecutive newlines`() {
        val actions = CommandExecutor.parseActions("\n\n")
        assertEquals(listOf(CommandExecutor.Action.Enter, CommandExecutor.Action.Enter), actions)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandExecutorTest"`
Expected: FAIL — `CommandExecutor` not found

**Step 3: Write implementation**

```kotlin
package com.example.voiceinput

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

object CommandExecutor {

    sealed class Action {
        data class Text(val value: String) : Action()
        data object Enter : Action()
    }

    fun parseActions(text: String): List<Action> {
        if (text.isEmpty()) return emptyList()
        val actions = mutableListOf<Action>()
        val buffer = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (buffer.isNotEmpty()) {
                    actions.add(Action.Text(buffer.toString()))
                    buffer.clear()
                }
                actions.add(Action.Enter)
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            actions.add(Action.Text(buffer.toString()))
        }
        return actions
    }

    fun execute(text: String, ic: InputConnection) {
        for (action in parseActions(text)) {
            when (action) {
                is Action.Text -> ic.commitText(action.value, 1)
                is Action.Enter -> {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandExecutorTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/CommandExecutor.kt \
       app/src/test/java/com/example/voiceinput/CommandExecutorTest.kt
git commit -m "feat: add CommandExecutor with text/Enter action parsing"
```

---

## Task 6: MFCCキャッシュ — VoiceCommandRepository拡張

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceCommandRepository.kt`
- Modify: `app/src/test/java/com/example/voiceinput/VoiceCommandRepositoryTest.kt`

**Step 1: Write failing tests**

Add to `VoiceCommandRepositoryTest.kt`:

```kotlin
@Test
fun `getMfccCacheFile returns correct path`() {
    val file = repo.getMfccCacheFile("exit", 1)
    assertEquals("exit_1.mfcc", file.name)
    assertEquals(samplesDir, file.parentFile)
}

@Test
fun `saveMfccCache and loadMfccCache roundtrip`() {
    val mfcc = arrayOf(floatArrayOf(1.5f, 2.5f), floatArrayOf(3.5f, 4.5f))
    repo.saveMfccCache("exit", 0, mfcc)
    val loaded = repo.loadMfccCache("exit", 0)
    assertNotNull(loaded)
    assertEquals(2, loaded!!.size)
    assertEquals(1.5f, loaded[0][0], 0.001f)
    assertEquals(4.5f, loaded[1][1], 0.001f)
}

@Test
fun `loadMfccCache returns null when no cache`() {
    val loaded = repo.loadMfccCache("nonexistent", 0)
    assertNull(loaded)
}

@Test
fun `loadAllMfccs loads cached MFCCs for all samples`() {
    val cmd = repo.addCommand("exit", "/exit\n")
    repo.updateSampleCount(cmd.id, 2)
    val mfcc0 = arrayOf(floatArrayOf(1f, 2f))
    val mfcc1 = arrayOf(floatArrayOf(3f, 4f))
    repo.saveMfccCache(cmd.id, 0, mfcc0)
    repo.saveMfccCache(cmd.id, 1, mfcc1)
    val all = repo.loadAllMfccs()
    assertEquals(1, all.size)
    assertEquals(2, all[cmd.id]!!.size)
}

@Test
fun `deleteCommand also removes mfcc cache files`() {
    val cmd = repo.addCommand("exit", "/exit\n")
    repo.saveMfccCache(cmd.id, 0, arrayOf(floatArrayOf(1f)))
    repo.deleteCommand(cmd.id)
    assertNull(repo.loadMfccCache(cmd.id, 0))
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceCommandRepositoryTest"`
Expected: FAIL — methods not found

**Step 3: Add implementation to VoiceCommandRepository**

Add these methods:

```kotlin
fun getMfccCacheFile(commandId: String, index: Int): File {
    return File(samplesDir, "${commandId}_${index}.mfcc")
}

fun saveMfccCache(commandId: String, index: Int, mfcc: Array<FloatArray>) {
    val file = getMfccCacheFile(commandId, index)
    val buffer = java.nio.ByteBuffer.allocate(8 + mfcc.sumOf { 4 + it.size * 4 })
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(mfcc.size) // number of frames
    buffer.putInt(if (mfcc.isNotEmpty()) mfcc[0].size else 0) // dimensions per frame
    for (frame in mfcc) {
        for (value in frame) {
            buffer.putFloat(value)
        }
    }
    file.writeBytes(buffer.array())
}

fun loadMfccCache(commandId: String, index: Int): Array<FloatArray>? {
    val file = getMfccCacheFile(commandId, index)
    if (!file.exists()) return null
    return try {
        val bytes = file.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val numFrames = buffer.getInt()
        val dimensions = buffer.getInt()
        Array(numFrames) { FloatArray(dimensions) { buffer.getFloat() } }
    } catch (e: Exception) {
        null
    }
}

fun loadAllMfccs(): Map<String, List<Array<FloatArray>>> {
    val result = mutableMapOf<String, MutableList<Array<FloatArray>>>()
    for (cmd in getCommands()) {
        val samples = mutableListOf<Array<FloatArray>>()
        for (i in 0 until cmd.sampleCount) {
            val mfcc = loadMfccCache(cmd.id, i)
            if (mfcc != null) samples.add(mfcc)
        }
        if (samples.isNotEmpty()) {
            result[cmd.id] = samples
        }
    }
    return result
}
```

Also update `deleteCommand` to also remove `.mfcc` files (the existing filter `it.name.startsWith("${id}_")` already handles this since `.mfcc` files also start with `{id}_`). Verify the existing code in `deleteCommand`:

```kotlin
samplesDir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
```

This already deletes both `.wav` and `.mfcc` files. No change needed.

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceCommandRepositoryTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceCommandRepository.kt \
       app/src/test/java/com/example/voiceinput/VoiceCommandRepositoryTest.kt
git commit -m "feat: add MFCC cache to VoiceCommandRepository"
```

---

## Task 7: サンプル上限を3→5に変更

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/CommandLearningView.kt:38` — MAX_SAMPLES
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt:546` — guard check
- Modify: `app/src/test/java/com/example/voiceinput/VoiceCommandTest.kt` (if max is tested)

**Step 1: Write failing test**

Add to `VoiceCommandRepositoryTest.kt`:

```kotlin
@Test
fun `can record up to 5 samples`() {
    val cmd = repo.addCommand("exit", "/exit\n")
    for (i in 0 until 5) {
        repo.getSampleFile(cmd.id, i).writeText("fake$i")
        repo.updateSampleCount(cmd.id, i + 1)
    }
    assertEquals(5, repo.getCommands().first().sampleCount)
    for (i in 0 until 5) {
        assertTrue(repo.getSampleFile(cmd.id, i).exists())
    }
}
```

**Step 2: Run test — should pass already since repo has no limit**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceCommandRepositoryTest.can record up to 5 samples"`
Expected: PASS (repo itself has no limit)

**Step 3: Update constants**

In `CommandLearningView.kt` line 38:
```kotlin
private const val MAX_SAMPLES = 5
```

In `VoiceInputIME.kt` line 546 (`recordCommandSample`):
```kotlin
if (sampleIndex >= 5) return // max 5 samples
```

**Step 4: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/CommandLearningView.kt \
       app/src/main/java/com/example/voiceinput/VoiceInputIME.kt \
       app/src/test/java/com/example/voiceinput/VoiceCommandRepositoryTest.kt
git commit -m "feat: increase max voice command samples from 3 to 5"
```

---

## Task 8: VoiceInputIME統合 — 録音時MFCC計算 + 照合フロー

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: MFCC計算をサンプル録音時に追加**

`VoiceInputIME.kt`の`recordCommandSample`メソッド内、WAVファイル保存の直後にMFCCキャッシュ生成を追加:

```kotlin
// After: wavFile.copyTo(targetFile, overwrite = true)
// Add:
serviceScope.launch(Dispatchers.IO) {
    try {
        val mfcc = MfccExtractor.extract(targetFile.readBytes())
        commandRepo?.saveMfccCache(commandId, sampleIndex)
    } catch (_: Exception) {}
}
```

Wait — this is better done inline since the WAV is already available:

In `recordCommandSample`, after the `wavFile.copyTo(targetFile, ...)` block, add MFCC caching:

```kotlin
if (targetFile != null) {
    wavFile.copyTo(targetFile, overwrite = true)
    // Cache MFCC for recognition
    try {
        val mfcc = MfccExtractor.extract(targetFile.readBytes())
        commandRepo?.saveMfccCache(commandId, sampleIndex, mfcc)
    } catch (_: Exception) {}
    wavFile.delete()
    commandRepo?.updateSampleCount(commandId, sampleIndex + 1)
    commandLearning?.refreshCommandList()
}
```

**Step 2: 音声入力後のコマンド照合フローを追加**

VoiceInputProcessorに録音停止のみでWAVを返すメソッドが必要。現在`stopAndTranscribeOnly`は内部でaudioFile.deleteする。新メソッド`stopRecording`を追加:

`VoiceInputProcessor.kt`に追加:
```kotlin
fun stopRecording(): File? {
    return audioRecorder.stop()
}
```

`VoiceInputIME.kt`の`onMicReleasedForNewInput`を修正:

```kotlin
private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
    statusText?.text = "処理中..."

    serviceScope.launch {
        val audioFile = proc.stopRecording() ?: run {
            statusText?.text = "録音に失敗しました"
            return@launch
        }

        try {
            // Try command matching first
            val commands = commandRepo?.getCommands()?.filter { it.enabled && it.sampleCount > 0 }
            if (!commands.isNullOrEmpty()) {
                val mfccSamples = withContext(Dispatchers.IO) {
                    commandRepo?.loadAllMfccs() ?: emptyMap()
                }
                if (mfccSamples.isNotEmpty()) {
                    val inputMfcc = withContext(Dispatchers.IO) {
                        MfccExtractor.extract(audioFile.readBytes())
                    }
                    val matcher = CommandMatcher(commands, mfccSamples)
                    val matchResult = matcher.match(inputMfcc)
                    if (matchResult != null) {
                        val ic = currentInputConnection
                        if (ic != null) {
                            CommandExecutor.execute(matchResult.command.text, ic)
                            statusText?.text = "コマンド実行: ${matchResult.command.label}"
                            delay(3000)
                            statusText?.text = "ダブルタップで音声入力"
                        }
                        audioFile.delete()
                        return@launch
                    }
                }
            }

            // No command match — proceed with Whisper→GPT
            statusText?.text = "変換中..."
            val corrections = correctionRepo?.getTopCorrections(20)
            val rawText = withContext(Dispatchers.IO) {
                WhisperClient(
                    PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
                        .getApiKey() ?: return@withContext null,
                    model = PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
                        .getWhisperModel()
                ).transcribe(audioFile)
            }
            audioFile.delete()

            if (rawText == null) {
                statusText?.text = "変換に失敗しました"
                delay(5000)
                statusText?.text = "ダブルタップで音声入力"
                return@launch
            }

            val convertedText = withContext(Dispatchers.IO) {
                val apiKey = PreferencesManager(
                    getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
                ).getApiKey() ?: return@withContext rawText
                val converter = GptConverter(apiKey)
                if (corrections != null) {
                    converter.convertWithHistory(rawText, corrections)
                } else {
                    converter.convert(rawText)
                }
            }

            val chunks = TextDiffer.diff(rawText, convertedText)
            val fullText = chunks.joinToString("") { it.displayText }
            committedTextLength = fullText.length
            currentFullText = fullText
            currentInputConnection?.commitText(fullText, 1)
            showCandidateArea(fullText)
            statusText?.text = "完了（テキスト選択→候補）"
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
```

**注意:** 上記は大幅な書き換えになる。既存の`proc.stopAndProcess()`を使わず、手動でWhisper→GPTを呼ぶ。代替案として、`VoiceInputProcessor`に`stopRecording()`だけ追加し、既存`stopAndProcess`のロジックを手動展開するのではなく、processorにaudioFileを渡せるメソッドを追加するほうがクリーン:

`VoiceInputProcessor.kt`に追加:
```kotlin
suspend fun processAudioFile(
    audioFile: File,
    context: String? = null,
    corrections: List<CorrectionEntry>? = null
): List<ConversionChunk>? {
    try {
        val rawText = withContext(Dispatchers.IO) {
            whisperClient.transcribe(audioFile, prompt = context)
        } ?: return null

        val convertedText = withContext(Dispatchers.IO) {
            if (corrections != null) {
                gptConverter.convertWithHistory(rawText, corrections)
            } else {
                gptConverter.convert(rawText)
            }
        }

        return TextDiffer.diff(rawText, convertedText)
    } finally {
        audioFile.delete()
    }
}
```

Then `onMicReleasedForNewInput` becomes simpler:

```kotlin
private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
    statusText?.text = "処理中..."

    serviceScope.launch {
        val audioFile = proc.stopRecording() ?: run {
            statusText?.text = "録音に失敗しました"
            return@launch
        }

        // Try command matching first
        val matched = tryMatchCommand(audioFile)
        if (matched) {
            audioFile.delete()
            return@launch
        }

        // No command match — Whisper→GPT
        statusText?.text = "変換中..."
        val corrections = correctionRepo?.getTopCorrections(20)
        val chunks = proc.processAudioFile(audioFile, corrections = corrections)
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

private suspend fun tryMatchCommand(audioFile: File): Boolean {
    val commands = commandRepo?.getCommands()?.filter { it.enabled && it.sampleCount > 0 }
    if (commands.isNullOrEmpty()) return false

    val mfccSamples = withContext(Dispatchers.IO) {
        commandRepo?.loadAllMfccs() ?: emptyMap()
    }
    if (mfccSamples.isEmpty()) return false

    val inputMfcc = withContext(Dispatchers.IO) {
        MfccExtractor.extract(audioFile.readBytes())
    }

    val matcher = CommandMatcher(commands, mfccSamples)
    val result = matcher.match(inputMfcc) ?: return false

    val ic = currentInputConnection ?: return false
    CommandExecutor.execute(result.command.text, ic)
    statusText?.text = "コマンド実行: ${result.command.label}"
    delay(3000)
    statusText?.text = "ダブルタップで音声入力"
    return true
}
```

**Step 3: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

**Step 4: Build and sync APK**

```bash
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt \
       app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt
git commit -m "feat: integrate command recognition into voice input flow"
```

---

## Task 9: 最終ビルド検証 + APK同期

**Step 1: Run all tests**

```bash
./gradlew testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass

**Step 2: Build APK**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

**Step 3: Sync APK**

```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk
```

**Step 4: Final commit if needed**

If any adjustments were made during verification.
