# 音声前処理による文字起こし精度改善 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 小声×早口での Whisper 文字起こし精度を向上させるため、AudioRecord への移行と音声前処理パイプラインを導入する

**Architecture:** MediaRecorder を AudioRecord（PCM直取り）に置き換え、録音後のPCMデータに対して RMS正規化→コンプレッサ→WAVエンコードを適用してから Whisper API に送信する。WhisperClient にも temperature=0 と prompt パラメータを追加する。

**Tech Stack:** Kotlin, Android AudioRecord API, PCM→WAV変換（自前実装）, JUnit 4 + MockK

---

## Task 1: AudioProcessor — WAVエンコーダ

PCMバイト列をWAVファイルに書き出すユーティリティ。後続の音声処理の基盤。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/AudioProcessor.kt`
- Create: `app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt`

**Step 1: Write the failing test — WAV encoding**

```kotlin
// AudioProcessorTest.kt
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessorTest {

    private lateinit var outputDir: File

    @Before
    fun setUp() {
        outputDir = File(System.getProperty("java.io.tmpdir"), "audio_processor_test")
        outputDir.mkdirs()
    }

    @Test
    fun `encodeWav creates valid WAV file from PCM data`() {
        // 1秒の無音 PCM (16kHz, 16bit, mono = 32000 bytes)
        val pcmData = ByteArray(32000)
        val outputFile = File(outputDir, "test.wav")

        AudioProcessor.encodeWav(pcmData, sampleRate = 16000, outputFile = outputFile)

        assertTrue(outputFile.exists())
        // WAV header = 44 bytes + PCM data
        assertEquals(32044L, outputFile.length())

        // Verify WAV header
        val header = outputFile.inputStream().use { it.readNBytes(44) }
        val riff = String(header, 0, 4)
        val wave = String(header, 8, 4)
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)

        // Verify sample rate in header (offset 24, little-endian)
        val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16000, sampleRate)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: FAIL — `AudioProcessor` not found

**Step 3: Write minimal implementation**

```kotlin
// AudioProcessor.kt
package com.example.voiceinput

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioProcessor {

    fun encodeWav(pcmData: ByteArray, sampleRate: Int, outputFile: File) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        FileOutputStream(outputFile).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // chunk size
            header.putShort(1) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            out.write(header.array())
            out.write(pcmData)
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/AudioProcessor.kt app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt
git commit -m "feat: add AudioProcessor with WAV encoding"
```

---

## Task 2: AudioProcessor — RMS正規化

小声対策の核心。PCMデータのRMSを測定し、目標レベルまでゲインを上げる。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AudioProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt`

**Step 1: Write the failing test — RMS normalization**

```kotlin
// AudioProcessorTest.kt に追加

@Test
fun `normalizeRms amplifies quiet audio to target level`() {
    // 小声をシミュレート: 振幅 ±500 程度（16bit最大は ±32767）
    val samples = ShortArray(16000) { i ->
        (500 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
    }
    val pcmData = ByteArray(samples.size * 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

    val originalRms = calculateRms(pcmData)

    val normalized = AudioProcessor.normalizeRms(pcmData, targetRmsDb = -18.0)
    val normalizedRms = calculateRms(normalized)

    // 正規化後のRMSが元より大きいこと
    assertTrue("RMS should increase: original=$originalRms, normalized=$normalizedRms",
        normalizedRms > originalRms * 2)
    // クリッピングしていないこと
    val normalizedSamples = ShortArray(normalized.size / 2)
    ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(normalizedSamples)
    val maxSample = normalizedSamples.maxOf { kotlin.math.abs(it.toInt()) }
    assertTrue("Should not clip: max=$maxSample", maxSample <= 32767)
}

@Test
fun `normalizeRms does not amplify already loud audio`() {
    // 大きな音: 振幅 ±20000
    val samples = ShortArray(16000) { i ->
        (20000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
    }
    val pcmData = ByteArray(samples.size * 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

    val originalRms = calculateRms(pcmData)
    val normalized = AudioProcessor.normalizeRms(pcmData, targetRmsDb = -18.0)
    val normalizedRms = calculateRms(normalized)

    // 既に十分大きいので変化は小さい（±50%以内）
    val ratio = normalizedRms / originalRms.toDouble()
    assertTrue("Should stay similar: ratio=$ratio", ratio in 0.5..1.5)
}

private fun calculateRms(pcmData: ByteArray): Double {
    val samples = ShortArray(pcmData.size / 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
    val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
    return kotlin.math.sqrt(sumSquares / samples.size)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: FAIL — `normalizeRms` not found

**Step 3: Write minimal implementation**

```kotlin
// AudioProcessor.kt に追加

fun normalizeRms(pcmData: ByteArray, targetRmsDb: Double = -18.0): ByteArray {
    if (pcmData.isEmpty()) return pcmData

    val samples = ShortArray(pcmData.size / 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

    // Calculate current RMS
    val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
    val currentRms = kotlin.math.sqrt(sumSquares / samples.size)
    if (currentRms < 1.0) return pcmData // silence

    // Target RMS in linear scale (relative to 16-bit max)
    val targetRms = 32767.0 * kotlin.math.pow(10.0, targetRmsDb / 20.0)
    val gain = targetRms / currentRms

    // Cap gain to prevent extreme amplification
    val cappedGain = gain.coerceAtMost(20.0)

    // Apply gain with clipping protection
    val output = ShortArray(samples.size) { i ->
        (samples[i] * cappedGain).toInt().coerceIn(-32768, 32767).toShort()
    }

    val result = ByteArray(output.size * 2)
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
    return result
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/AudioProcessor.kt app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt
git commit -m "feat: add RMS normalization for quiet audio"
```

---

## Task 3: AudioProcessor — コンプレッサ

子音を前に出すためのダイナミクス処理。小声での子音消失を防ぐ。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AudioProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt`

**Step 1: Write the failing test — compressor**

```kotlin
// AudioProcessorTest.kt に追加

@Test
fun `compress reduces dynamic range`() {
    // ダイナミックレンジが広い信号: 前半小さい、後半大きい
    val samples = ShortArray(16000) { i ->
        val amplitude = if (i < 8000) 500 else 15000
        (amplitude * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
    }
    val pcmData = ByteArray(samples.size * 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

    val compressed = AudioProcessor.compress(pcmData)
    val compressedSamples = ShortArray(compressed.size / 2)
    ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(compressedSamples)

    // 前半と後半のRMSの比率が、元より小さくなっていること（＝ダイナミックレンジ圧縮）
    val origQuietRms = rmsOfRange(samples, 2000, 6000) // 安定区間
    val origLoudRms = rmsOfRange(samples, 10000, 14000)
    val compQuietRms = rmsOfRange(compressedSamples, 2000, 6000)
    val compLoudRms = rmsOfRange(compressedSamples, 10000, 14000)

    val origRatio = origLoudRms / origQuietRms
    val compRatio = compLoudRms / compQuietRms

    assertTrue("Dynamic range should be reduced: origRatio=$origRatio, compRatio=$compRatio",
        compRatio < origRatio)
}

private fun rmsOfRange(samples: ShortArray, from: Int, to: Int): Double {
    val slice = samples.sliceArray(from until to)
    val sumSquares = slice.sumOf { it.toDouble() * it.toDouble() }
    return kotlin.math.sqrt(sumSquares / slice.size)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: FAIL — `compress` not found

**Step 3: Write minimal implementation**

```kotlin
// AudioProcessor.kt に追加

fun compress(
    pcmData: ByteArray,
    thresholdDb: Double = -30.0,
    ratio: Double = 2.5,
    attackMs: Double = 10.0,
    releaseMs: Double = 80.0,
    sampleRate: Int = 16000
): ByteArray {
    if (pcmData.isEmpty()) return pcmData

    val samples = ShortArray(pcmData.size / 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

    val thresholdLinear = 32767.0 * kotlin.math.pow(10.0, thresholdDb / 20.0)
    val attackCoeff = kotlin.math.exp(-1.0 / (attackMs * sampleRate / 1000.0))
    val releaseCoeff = kotlin.math.exp(-1.0 / (releaseMs * sampleRate / 1000.0))

    var envelope = 0.0
    val output = ShortArray(samples.size)

    for (i in samples.indices) {
        val inputAbs = kotlin.math.abs(samples[i].toDouble())

        // Envelope follower
        envelope = if (inputAbs > envelope) {
            attackCoeff * envelope + (1.0 - attackCoeff) * inputAbs
        } else {
            releaseCoeff * envelope + (1.0 - releaseCoeff) * inputAbs
        }

        // Compute gain reduction
        val gain = if (envelope > thresholdLinear) {
            val overDb = 20.0 * kotlin.math.log10(envelope / thresholdLinear)
            val reducedDb = overDb * (1.0 - 1.0 / ratio)
            kotlin.math.pow(10.0, -reducedDb / 20.0)
        } else {
            1.0
        }

        output[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }

    val result = ByteArray(output.size * 2)
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
    return result
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/AudioProcessor.kt app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt
git commit -m "feat: add compressor for dynamic range reduction"
```

---

## Task 4: AudioProcessor — processForWhisper 統合メソッド

正規化→コンプレッサ→WAVエンコードを一発で実行するパイプラインメソッド。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AudioProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt`

**Step 1: Write the failing test**

```kotlin
// AudioProcessorTest.kt に追加

@Test
fun `processForWhisper applies normalization and compression then outputs WAV`() {
    // 小声の信号
    val samples = ShortArray(16000) { i ->
        (300 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
    }
    val pcmData = ByteArray(samples.size * 2)
    ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

    val outputFile = File(outputDir, "processed.wav")
    AudioProcessor.processForWhisper(pcmData, sampleRate = 16000, outputFile = outputFile)

    assertTrue(outputFile.exists())
    // WAV header (44) + processed PCM data (32000)
    assertEquals(32044L, outputFile.length())

    // 処理済みPCMのRMSが元より大きいこと
    val wavData = outputFile.readBytes()
    val processedPcm = wavData.copyOfRange(44, wavData.size)
    val processedRms = calculateRms(processedPcm)
    val originalRms = calculateRms(pcmData)
    assertTrue("Processed should be louder: orig=$originalRms, proc=$processedRms",
        processedRms > originalRms * 2)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: FAIL — `processForWhisper` not found

**Step 3: Write minimal implementation**

```kotlin
// AudioProcessor.kt に追加

fun processForWhisper(pcmData: ByteArray, sampleRate: Int, outputFile: File) {
    val normalized = normalizeRms(pcmData)
    val compressed = compress(normalized, sampleRate = sampleRate)
    encodeWav(compressed, sampleRate, outputFile)
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioProcessorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/AudioProcessor.kt app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt
git commit -m "feat: add processForWhisper pipeline method"
```

---

## Task 5: AudioRecorder — MediaRecorder → AudioRecord 移行

PCMデータを直接取得できるように AudioRecord API に切り替える。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AudioRecorder.kt`
- Modify: `app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt`

**Step 1: Write the failing tests — new interface**

AudioRecord は Android フレームワーク依存のため、インターフェース変更のテストと、ファイル出力形式の変更を検証する。

```kotlin
// AudioRecorderTest.kt — 全体を書き換え
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AudioRecorderTest {

    private lateinit var outputDir: File

    @Before
    fun setUp() {
        outputDir = File(System.getProperty("java.io.tmpdir"), "voice_input_test")
        outputDir.mkdirs()
    }

    @Test
    fun `getOutputFile returns file in specified directory with wav extension`() {
        val recorder = AudioRecorder(outputDir)
        val file = recorder.getOutputFile()
        assertEquals(outputDir, file.parentFile)
        assertTrue(file.name.endsWith(".wav"))
        assertTrue(file.name.startsWith("voice_"))
    }

    @Test
    fun `isRecording returns false initially`() {
        val recorder = AudioRecorder(outputDir)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `getAmplitude returns 0 when not recording`() {
        val recorder = AudioRecorder(outputDir)
        assertEquals(0, recorder.getAmplitude())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioRecorderTest" 2>&1 | tail -20`
Expected: FAIL — `.wav` assertion fails (currently `.m4a`)

**Step 3: Rewrite AudioRecorder**

```kotlin
// AudioRecorder.kt — 全体を書き換え
package com.example.voiceinput

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File

class AudioRecorder(private val outputDir: File) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmBuffer: ByteArrayOutputStream? = null
    var isRecording: Boolean = false
        private set

    private var lastAmplitude: Int = 0

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun getOutputFile(): File {
        val fileName = "voice_${System.currentTimeMillis()}.wav"
        return File(outputDir, fileName)
    }

    fun start(): Boolean {
        if (isRecording) return false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return false
        }

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }

            pcmBuffer = ByteArrayOutputStream()
            audioRecord = recorder
            recorder.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        pcmBuffer?.write(buffer, 0, bytesRead)
                        lastAmplitude = calculateAmplitude(buffer, bytesRead)
                    }
                }
            }.also { it.start() }

            true
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            pcmBuffer = null
            false
        }
    }

    fun getAmplitude(): Int {
        if (!isRecording) return 0
        return lastAmplitude
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false

        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        val pcmData = pcmBuffer?.toByteArray() ?: return null
        pcmBuffer = null

        if (pcmData.isEmpty()) return null

        val outputFile = getOutputFile()
        AudioProcessor.processForWhisper(pcmData, SAMPLE_RATE, outputFile)
        return outputFile
    }

    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var maxAmplitude = 0
        for (i in 0 until bytesRead - 1 step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample.toShort().toInt())
            if (abs > maxAmplitude) maxAmplitude = abs
        }
        return maxAmplitude
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.AudioRecorderTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Run ALL tests to verify nothing broke**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS（VoiceInputProcessorTest は AudioRecorder を mockk しているので影響なし）

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/AudioRecorder.kt app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt
git commit -m "feat: migrate AudioRecorder from MediaRecorder to AudioRecord with preprocessing"
```

---

## Task 6: WhisperClient — temperature と prompt パラメータ追加

Whisper API に temperature=0（安定化）と prompt（文脈）を渡せるようにする。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/WhisperClient.kt`
- Modify: `app/src/test/java/com/example/voiceinput/WhisperClientTest.kt`

**Step 1: Write the failing tests**

```kotlin
// WhisperClientTest.kt に追加

@Test
fun `transcribe sends temperature 0 by default`() {
    server.enqueue(
        MockResponse()
            .setBody("""{"text": "テスト"}""")
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
    )

    val audioFile = File.createTempFile("test", ".wav").apply { deleteOnExit() }
    audioFile.writeBytes(ByteArray(100))

    client.transcribe(audioFile)

    val request = server.takeRequest()
    val body = request.body.readUtf8()
    assertTrue("Should contain temperature", body.contains("temperature"))
    assertTrue("Should contain 0", body.contains("0"))
}

@Test
fun `transcribe sends prompt when provided`() {
    server.enqueue(
        MockResponse()
            .setBody("""{"text": "テスト"}""")
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
    )

    val audioFile = File.createTempFile("test", ".wav").apply { deleteOnExit() }
    audioFile.writeBytes(ByteArray(100))

    client.transcribe(audioFile, prompt = "前回の文脈です")

    val request = server.takeRequest()
    val body = request.body.readUtf8()
    assertTrue("Should contain prompt", body.contains("前回の文脈です"))
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.WhisperClientTest" 2>&1 | tail -20`
Expected: FAIL — temperature not in request body / prompt parameter not found

**Step 3: Update WhisperClient**

```kotlin
// WhisperClient.kt — transcribe メソッドを更新
fun transcribe(audioFile: File, prompt: String? = null): String? {
    val builder = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            audioFile.name,
            audioFile.asRequestBody("audio/wav".toMediaType())
        )
        .addFormDataPart("model", model)
        .addFormDataPart("language", "ja")
        .addFormDataPart("temperature", "0")

    if (!prompt.isNullOrBlank()) {
        builder.addFormDataPart("prompt", prompt)
    }

    val requestBody = builder.build()

    val request = Request.Builder()
        .url("${baseUrl}v1/audio/transcriptions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(requestBody)
        .build()

    return try {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        JsonParser.parseString(body).asJsonObject.get("text").asString
    } catch (e: Exception) {
        null
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.WhisperClientTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Run ALL tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/WhisperClient.kt app/src/test/java/com/example/voiceinput/WhisperClientTest.kt
git commit -m "feat: add temperature=0 and prompt parameter to WhisperClient"
```

---

## Task 7: VoiceInputProcessor — 文脈 prompt の受け渡し

入力欄の既存テキストを Whisper の prompt として渡すようにする。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt`
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: Write the failing test**

```kotlin
// VoiceInputProcessorTest.kt に追加

@Test
fun `stopAndProcess passes context to whisper`() = runTest {
    val audioFile = mockk<File>()
    every { audioRecorder.stop() } returns audioFile
    every { whisperClient.transcribe(audioFile, "既存テキスト") } returns "変換前"
    every { gptConverter.convert("変換前") } returns "変換後"
    every { audioFile.delete() } returns true

    val result = processor.stopAndProcess(context = "既存テキスト")

    assertNotNull(result)
    verify { whisperClient.transcribe(audioFile, "既存テキスト") }
}

@Test
fun `stopAndTranscribeOnly passes context to whisper`() = runTest {
    val audioFile = mockk<File>()
    every { audioRecorder.stop() } returns audioFile
    every { whisperClient.transcribe(audioFile, "文脈") } returns "結果"
    every { audioFile.delete() } returns true

    val result = processor.stopAndTranscribeOnly(context = "文脈")

    assertEquals("結果", result)
    verify { whisperClient.transcribe(audioFile, "文脈") }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest" 2>&1 | tail -20`
Expected: FAIL — context parameter not found

**Step 3: Update VoiceInputProcessor**

```kotlin
// VoiceInputProcessor.kt — メソッドに context パラメータ追加

suspend fun stopAndTranscribeOnly(context: String? = null): String? {
    val audioFile = audioRecorder.stop() ?: return null
    try {
        return withContext(Dispatchers.IO) {
            whisperClient.transcribe(audioFile, prompt = context)
        }
    } finally {
        audioFile.delete()
    }
}

suspend fun stopAndProcess(context: String? = null): List<ConversionChunk>? {
    val audioFile = audioRecorder.stop() ?: return null

    try {
        val rawText = withContext(Dispatchers.IO) {
            whisperClient.transcribe(audioFile, prompt = context)
        } ?: return null

        val convertedText = withContext(Dispatchers.IO) {
            gptConverter.convert(rawText)
        }

        return TextDiffer.diff(rawText, convertedText)
    } finally {
        audioFile.delete()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: Update VoiceInputIME to pass context**

```kotlin
// VoiceInputIME.kt — onMicReleasedForNewInput を更新
private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
    statusText?.text = "変換中..."
    val context = getEditorContext()

    serviceScope.launch {
        val chunks = proc.stopAndProcess(context = context)
        // ... 以下変更なし
    }
}

// onMicReleasedForReplacement を更新
private fun onMicReleasedForReplacement(proc: VoiceInputProcessor, range: Pair<Int, Int>) {
    statusText?.text = "音声認識中..."
    val context = getEditorContext()

    serviceScope.launch {
        val rawText = proc.stopAndTranscribeOnly(context = context)
        // ... 以下変更なし
    }
}

// 新規メソッド追加
private fun getEditorContext(): String? {
    val ic = currentInputConnection ?: return null
    val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
    val after = ic.getTextAfterCursor(50, 0)?.toString() ?: ""
    val context = (before + after).trim()
    return context.ifEmpty { null }
}
```

**Step 6: Run ALL tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS

**Step 7: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: pass editor context as Whisper prompt for better recognition"
```

---

## Task 8: 全体結合テスト + APK ビルド

全テスト通過を確認し、APK をビルドして同期先にコピー。

**Files:**
- No new files

**Step 1: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS

**Step 2: Build APK**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Copy APK to sync directory**

Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 4: Commit**

```bash
git add -A
git commit -m "chore: verify all tests pass after audio preprocessing pipeline"
```

---

## 改善効果の期待値

| 対策 | 期待効果 | タスク |
|------|----------|--------|
| AudioRecord移行 | PCM直取りで前処理可能に | Task 5 |
| RMS正規化 | 小声の振幅不足を解消 | Task 2 |
| コンプレッサ | 子音を前に出し、聞き取り精度向上 | Task 3 |
| temperature=0 | 出力の揺れを抑制 | Task 6 |
| prompt（文脈） | 固有名詞・文脈依存の誤認識を軽減 | Task 7 |

## 将来の追加候補（今回は含めない）

- VAD（無音区切り）— 長い録音の分割
- タイムストレッチ（1.05-1.1倍）— 早口時の母音復元
- ノイズ抑制（NoiseSuppressor）— 端末依存のため要検証
- カスタム辞書 — 固有名詞対策（RAGとの統合）
