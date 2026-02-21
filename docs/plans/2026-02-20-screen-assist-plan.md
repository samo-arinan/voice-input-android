# Screen Assist Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a screen-capture assistant feature triggered by LG V60's Assistant button. The user traces a region on screen while asking a voice question, and GPT-4o Vision answers based on the captured image.

**Architecture:** VoiceInteractionService receives the Assistant button press and launches a VoiceInteractionSession. The session shows a full-screen overlay with the captured screenshot as background, records audio, lets the user trace a region, then sends the cropped image + transcribed question to GPT-4o Vision.

**Tech Stack:** Android VoiceInteractionService API, OkHttp + Gson (existing), OpenAI Whisper + GPT-4o Vision API, JUnit 4 + MockK + MockWebServer (existing test infra)

---

### Task 1: BoundingRectCalculator

Pure logic: converts a list of touch points into a bounding rectangle.

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/BoundingRectCalculator.kt`
- Test: `app/src/test/java/com/example/voiceinput/BoundingRectCalculatorTest.kt`

**Step 1: Write the failing test**

```kotlin
// BoundingRectCalculatorTest.kt
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class BoundingRectCalculatorTest {

    @Test
    fun `single point returns 1x1 rect`() {
        val points = listOf(Pair(100f, 200f))
        val rect = BoundingRectCalculator.calculate(points)
        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(101, rect.right)
        assertEquals(201, rect.bottom)
    }

    @Test
    fun `multiple points returns bounding rect`() {
        val points = listOf(
            Pair(50f, 100f),
            Pair(200f, 150f),
            Pair(100f, 300f)
        )
        val rect = BoundingRectCalculator.calculate(points)
        assertEquals(50, rect.left)
        assertEquals(100, rect.top)
        assertEquals(200, rect.right)
        assertEquals(300, rect.bottom)
    }

    @Test
    fun `empty points returns zero rect`() {
        val rect = BoundingRectCalculator.calculate(emptyList())
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(0, rect.right)
        assertEquals(0, rect.bottom)
    }

    @Test
    fun `clamps to bitmap bounds`() {
        val points = listOf(Pair(-10f, -20f), Pair(2000f, 3000f))
        val rect = BoundingRectCalculator.calculateClamped(points, 1080, 2340)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(2340, rect.bottom)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.BoundingRectCalculatorTest" 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```kotlin
// BoundingRectCalculator.kt
package com.example.voiceinput

import android.graphics.Rect

object BoundingRectCalculator {

    fun calculate(points: List<Pair<Float, Float>>): Rect {
        if (points.isEmpty()) return Rect(0, 0, 0, 0)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for ((x, y) in points) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        val left = minX.toInt()
        val top = minY.toInt()
        val right = maxOf(maxX.toInt(), left + 1)
        val bottom = maxOf(maxY.toInt(), top + 1)

        return Rect(left, top, right, bottom)
    }

    fun calculateClamped(points: List<Pair<Float, Float>>, bitmapWidth: Int, bitmapHeight: Int): Rect {
        val rect = calculate(points)
        return Rect(
            rect.left.coerceIn(0, bitmapWidth),
            rect.top.coerceIn(0, bitmapHeight),
            rect.right.coerceIn(0, bitmapWidth),
            rect.bottom.coerceIn(0, bitmapHeight)
        )
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.BoundingRectCalculatorTest" 2>&1 | tail -20`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/BoundingRectCalculator.kt app/src/test/java/com/example/voiceinput/BoundingRectCalculatorTest.kt
git commit -m "feat: add BoundingRectCalculator for touch region selection"
```

---

### Task 2: VisionClient

GPT-4o Vision API client. Sends Base64 image + text question, returns answer.

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/VisionClient.kt`
- Test: `app/src/test/java/com/example/voiceinput/VisionClientTest.kt`

**Step 1: Write the failing test**

```kotlin
// VisionClientTest.kt
package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VisionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VisionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = VisionClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends image and question and returns response`() {
        server.enqueue(MockResponse().setBody("""
            {
                "choices": [{
                    "message": {
                        "content": "This is a login screen with an error message."
                    }
                }]
            }
        """))

        val result = client.ask("aW1hZ2VkYXRh", "What is shown in this image?")

        assertEquals("This is a login screen with an error message.", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("v1/chat/completions"))
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("gpt-4o"))
        assertTrue(body.contains("aW1hZ2VkYXRh"))
        assertTrue(body.contains("What is shown in this image?"))
    }

    @Test
    fun `returns null on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.ask("aW1hZ2VkYXRh", "question")

        assertNull(result)
    }

    @Test
    fun `uses default question when question is null`() {
        server.enqueue(MockResponse().setBody("""
            {
                "choices": [{
                    "message": {
                        "content": "A screenshot of a web page."
                    }
                }]
            }
        """))

        val result = client.ask("aW1hZ2VkYXRh", null)

        assertEquals("A screenshot of a web page.", result)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("この画像について説明してください"))
    }

    @Test
    fun `request body contains correct image_url format`() {
        server.enqueue(MockResponse().setBody("""
            {"choices": [{"message": {"content": "ok"}}]}
        """))

        client.ask("dGVzdA==", "test")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("data:image/png;base64,dGVzdA=="))
        assertTrue(body.contains("image_url"))
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.VisionClientTest" 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```kotlin
// VisionClient.kt
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class VisionClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val DEFAULT_QUESTION = "この画像について説明してください"
    }

    fun ask(imageBase64: String, question: String?): String? {
        val textContent = mapOf("type" to "text", "text" to (question ?: DEFAULT_QUESTION))
        val imageContent = mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64")
        )
        val messages = listOf(
            mapOf(
                "role" to "user",
                "content" to listOf(textContent, imageContent)
            )
        )
        val body = gson.toJson(
            mapOf(
                "model" to "gpt-4o",
                "messages" to messages,
                "max_tokens" to 1024
            )
        )

        val request = Request.Builder()
            .url("${baseUrl}v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
        } catch (e: Exception) {
            null
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.VisionClientTest" 2>&1 | tail -20`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/VisionClient.kt app/src/test/java/com/example/voiceinput/VisionClientTest.kt
git commit -m "feat: add VisionClient for GPT-4o Vision API"
```

---

### Task 3: SilenceDetector

Analyzes PCM audio buffer to detect silence (used to auto-stop recording).

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/SilenceDetector.kt`
- Test: `app/src/test/java/com/example/voiceinput/SilenceDetectorTest.kt`

**Step 1: Write the failing test**

```kotlin
// SilenceDetectorTest.kt
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class SilenceDetectorTest {

    @Test
    fun `detects silence in zero-amplitude buffer`() {
        val silentBuffer = ByteArray(3200) // 100ms at 16kHz 16-bit mono
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        val result = detector.feed(silentBuffer, silentBuffer.size)

        assertTrue(result)
    }

    @Test
    fun `does not detect silence in loud buffer`() {
        // Fill with high-amplitude samples (16-bit little-endian)
        val loudBuffer = ByteArray(3200)
        for (i in loudBuffer.indices step 2) {
            loudBuffer[i] = 0x00       // low byte
            loudBuffer[i + 1] = 0x40   // high byte → ~16384 amplitude
        }
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        val result = detector.feed(loudBuffer, loudBuffer.size)

        assertFalse(result)
    }

    @Test
    fun `requires sustained silence for specified duration`() {
        val silentBuffer = ByteArray(1600) // 50ms
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 1500)

        // 50ms of silence is not enough for 1500ms threshold
        val result = detector.feed(silentBuffer, silentBuffer.size)

        assertFalse(result)
    }

    @Test
    fun `resets silence timer on loud input`() {
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        // Feed 200ms of silence (enough for 100ms threshold)
        val silentBuffer = ByteArray(6400)
        detector.feed(silentBuffer, silentBuffer.size)

        // Feed loud buffer → resets timer
        val loudBuffer = ByteArray(3200)
        for (i in loudBuffer.indices step 2) {
            loudBuffer[i + 1] = 0x40
        }
        detector.feed(loudBuffer, loudBuffer.size)

        // Feed only 50ms of silence → not enough after reset
        val shortSilence = ByteArray(1600)
        val result = detector.feed(shortSilence, shortSilence.size)

        assertFalse(result)
    }

    @Test
    fun `reset clears state`() {
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        // Accumulate silence
        val silentBuffer = ByteArray(6400) // 200ms
        detector.feed(silentBuffer, silentBuffer.size)

        detector.reset()

        // After reset, 50ms is not enough
        val shortSilence = ByteArray(1600)
        val result = detector.feed(shortSilence, shortSilence.size)

        assertFalse(result)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.SilenceDetectorTest" 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```kotlin
// SilenceDetector.kt
package com.example.voiceinput

import kotlin.math.sqrt

class SilenceDetector(
    private val thresholdRms: Double = 200.0,
    private val silenceDurationMs: Long = 1500,
    private val sampleRate: Int = 16000
) {
    private var silentMs: Long = 0

    fun feed(buffer: ByteArray, bytesRead: Int): Boolean {
        val rms = calculateRms(buffer, bytesRead)
        val durationMs = (bytesRead / 2) * 1000L / sampleRate

        if (rms < thresholdRms) {
            silentMs += durationMs
        } else {
            silentMs = 0
        }

        return silentMs >= silenceDurationMs
    }

    fun reset() {
        silentMs = 0
    }

    private fun calculateRms(buffer: ByteArray, bytesRead: Int): Double {
        var sumSquares = 0.0
        val sampleCount = bytesRead / 2

        if (sampleCount == 0) return 0.0

        for (i in 0 until bytesRead step 2) {
            if (i + 1 >= bytesRead) break
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed.toDouble() * signed.toDouble()
        }

        return sqrt(sumSquares / sampleCount)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.SilenceDetectorTest" 2>&1 | tail -20`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/SilenceDetector.kt app/src/test/java/com/example/voiceinput/SilenceDetectorTest.kt
git commit -m "feat: add SilenceDetector for auto-stop recording"
```

---

### Task 4: ScreenCropper

Utility to crop a Bitmap by coordinates and convert to Base64.

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/ScreenCropper.kt`
- Test: `app/src/test/java/com/example/voiceinput/ScreenCropperTest.kt`

**Step 1: Write the failing test**

Note: Bitmap is an Android class. Tests focus on coordinate validation logic. Actual Bitmap operations are tested manually.

```kotlin
// ScreenCropperTest.kt
package com.example.voiceinput

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class ScreenCropperTest {

    @Test
    fun `sanitize clamps rect to bitmap bounds`() {
        val rect = Rect(-10, -20, 2000, 3000)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertEquals(0, result.left)
        assertEquals(0, result.top)
        assertEquals(1080, result.right)
        assertEquals(2340, result.bottom)
    }

    @Test
    fun `sanitize returns null for zero-area rect`() {
        val rect = Rect(100, 100, 100, 100)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNull(result)
    }

    @Test
    fun `sanitize returns null for inverted rect`() {
        val rect = Rect(200, 200, 100, 100)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNull(result)
    }

    @Test
    fun `sanitize preserves valid rect`() {
        val rect = Rect(100, 200, 500, 600)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNotNull(result)
        assertEquals(100, result!!.left)
        assertEquals(200, result.top)
        assertEquals(500, result.right)
        assertEquals(600, result.bottom)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.ScreenCropperTest" 2>&1 | tail -20`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```kotlin
// ScreenCropper.kt
package com.example.voiceinput

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream

object ScreenCropper {

    fun sanitizeRect(rect: Rect, bitmapWidth: Int, bitmapHeight: Int): Rect? {
        val clamped = Rect(
            rect.left.coerceIn(0, bitmapWidth),
            rect.top.coerceIn(0, bitmapHeight),
            rect.right.coerceIn(0, bitmapWidth),
            rect.bottom.coerceIn(0, bitmapHeight)
        )
        if (clamped.width() <= 0 || clamped.height() <= 0) return null
        return clamped
    }

    fun cropAndEncode(bitmap: Bitmap, rect: Rect): String? {
        val sanitized = sanitizeRect(rect, bitmap.width, bitmap.height) ?: return null
        val cropped = Bitmap.createBitmap(
            bitmap, sanitized.left, sanitized.top, sanitized.width(), sanitized.height()
        )
        val stream = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 90, stream)
        if (cropped !== bitmap) cropped.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.ScreenCropperTest" 2>&1 | tail -20`
Expected: PASS (4 tests)

Note: `android.graphics.Rect` is available in unit tests because `minSdk=30` and the Android SDK stubs are on the classpath. If `Rect` causes issues, the test runner may need `testOptions.unitTests.isReturnDefaultValues = true` in `build.gradle.kts`. Check and add if needed:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/ScreenCropper.kt app/src/test/java/com/example/voiceinput/ScreenCropperTest.kt
git commit -m "feat: add ScreenCropper for bitmap crop and Base64 encode"
```

---

### Task 5: VoiceInteractionService + SessionService Skeleton

Register as a system assistant. No tests — this is Android framework wiring.

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/AssistInteractionService.kt`
- Create: `app/src/main/java/com/example/voiceinput/AssistSessionService.kt`
- Create: `app/src/main/res/xml/assist_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Create assist_config.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<voice-interaction-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="com.example.voiceinput.AssistSessionService"
    android:supportsAssist="true" />
```

**Step 2: Create AssistInteractionService.kt**

```kotlin
// AssistInteractionService.kt
package com.example.voiceinput

import android.service.voice.VoiceInteractionService

class AssistInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
```

**Step 3: Create AssistSessionService.kt**

```kotlin
// AssistSessionService.kt
package com.example.voiceinput

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistSession(this)
    }
}
```

**Step 4: Update AndroidManifest.xml**

Add these inside `<application>`:

```xml
<service
    android:name=".AssistInteractionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/assist_config" />
</service>

<service
    android:name=".AssistSessionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true" />
```

Add permissions (before `<application>`):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

**Step 5: Verify build compiles**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

Note: `AssistSession` doesn't exist yet — create a minimal placeholder in Step 6 of this task or create it in Task 6. If needed for compile, add a placeholder:

```kotlin
// AssistSession.kt (placeholder — will be expanded in Task 7)
package com.example.voiceinput

import android.content.Context
import android.service.voice.VoiceInteractionSession

class AssistSession(context: Context) : VoiceInteractionSession(context)
```

**Step 6: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/AssistInteractionService.kt \
    app/src/main/java/com/example/voiceinput/AssistSessionService.kt \
    app/src/main/java/com/example/voiceinput/AssistSession.kt \
    app/src/main/res/xml/assist_config.xml \
    app/src/main/AndroidManifest.xml
git commit -m "feat: register VoiceInteractionService for Assistant button"
```

**Step 7: Manual test on device**

1. Build and install: `./gradlew installDebug`
2. On LG V60: Settings → Apps → Default apps → Assist & voice input → select "VoiceInput"
3. Press Assistant button → should show blank session (no crash)

---

### Task 6: TouchCanvas

Custom View for finger tracing. Records touch points and renders the trace with bounding rect highlight.

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/TouchCanvas.kt`

Note: Custom Views are hard to unit test without Robolectric. The testable logic (bounding rect) is already in BoundingRectCalculator (Task 1). This task is implementation only.

**Step 1: Create TouchCanvas.kt**

```kotlin
// TouchCanvas.kt
package com.example.voiceinput

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class TouchCanvas(context: Context) : View(context) {

    private val points = mutableListOf<Pair<Float, Float>>()
    private val path = Path()
    private var boundingRect: Rect? = null
    var onTraceComplete: ((Rect) -> Unit)? = null

    private val pathPaint = Paint().apply {
        color = Color.argb(180, 74, 222, 128) // green trace
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rectPaint = Paint().apply {
        color = Color.argb(60, 74, 222, 128) // semi-transparent green fill
        style = Paint.Style.FILL
    }

    private val rectStrokePaint = Paint().apply {
        color = Color.argb(200, 74, 222, 128)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                path.reset()
                boundingRect = null
                points.add(Pair(event.x, event.y))
                path.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                points.add(Pair(event.x, event.y))
                path.lineTo(event.x, event.y)
                boundingRect = BoundingRectCalculator.calculate(points)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                points.add(Pair(event.x, event.y))
                boundingRect = BoundingRectCalculator.calculate(points)
                invalidate()
                boundingRect?.let { onTraceComplete?.invoke(it) }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, pathPaint)
        boundingRect?.let { rect ->
            canvas.drawRect(rect, rectPaint)
            canvas.drawRect(rect, rectStrokePaint)
        }
    }

    fun getPoints(): List<Pair<Float, Float>> = points.toList()

    fun getBoundingRect(): Rect? = boundingRect
}
```

**Step 2: Verify build compiles**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/TouchCanvas.kt
git commit -m "feat: add TouchCanvas for finger region tracing"
```

---

### Task 7: AssistSession with Overlay UI

The main orchestrator. Shows overlay, manages recording + tracing, sends to APIs, shows result.

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AssistSession.kt` (replace placeholder)
- Create: `app/src/main/res/layout/assist_overlay.xml`

**Step 1: Create the overlay layout**

```xml
<!-- res/layout/assist_overlay.xml -->
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#00000000">

    <!-- Screenshot background (set programmatically) -->
    <ImageView
        android:id="@+id/screenshotBackground"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="fitXY" />

    <!-- Semi-transparent overlay on top of screenshot -->
    <View
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#33000000" />

    <!-- TouchCanvas inserted programmatically here -->
    <FrameLayout
        android:id="@+id/touchCanvasContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Recording indicator at bottom -->
    <LinearLayout
        android:id="@+id/recordingIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="80dp"
        android:background="@drawable/rounded_bg"
        android:gravity="center"
        android:orientation="horizontal"
        android:padding="12dp">

        <View
            android:id="@+id/recordingDot"
            android:layout_width="12dp"
            android:layout_height="12dp"
            android:background="#FFFF4444" />

        <TextView
            android:id="@+id/recordingText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:text="録音中... なぞりながら質問してください"
            android:textColor="#FFFFFFFF"
            android:textSize="14sp" />
    </LinearLayout>

    <!-- Response area (shown after AI responds) -->
    <ScrollView
        android:id="@+id/responseContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp"
        android:background="#E6222222"
        android:padding="16dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/responseText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#FFFFFFFF"
            android:textSize="16sp" />
    </ScrollView>

    <!-- Status text (processing, error, etc.) -->
    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:background="#CC222222"
        android:padding="16dp"
        android:textColor="#FFFFFFFF"
        android:textSize="16sp"
        android:visibility="gone" />
</FrameLayout>
```

**Step 2: Create rounded_bg drawable (if not exists)**

```xml
<!-- res/drawable/rounded_bg.xml -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#CC222222" />
    <corners android:radius="24dp" />
</shape>
```

**Step 3: Implement AssistSession.kt**

Replace the placeholder with the full implementation:

```kotlin
// AssistSession.kt
package com.example.voiceinput

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import java.io.File

class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private var screenshot: Bitmap? = null
    private var audioRecorder: AudioRecorder? = null
    private var touchCanvas: TouchCanvas? = null
    private var traceRect: Rect? = null
    private var isProcessing = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoStopDelay = 1500L // ms after finger lift to stop recording

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
                // Start auto-stop timer after finger lifts
                handler.postDelayed({ stopAndProcess() }, autoStopDelay)
            }
        }
        container.addView(touchCanvas)

        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showWindow()
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

                // Transcribe audio
                val prefs = PreferencesManager(
                    context.getSharedPreferences("voice_input_prefs", Context.MODE_PRIVATE)
                )
                val apiKey = prefs.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    showError("APIキーが設定されていません")
                    return@Thread
                }

                var question: String? = null
                if (audioFile != null && audioFile.exists()) {
                    val whisper = WhisperClient(apiKey)
                    question = whisper.transcribe(audioFile)
                    audioFile.delete()
                }

                // Capture and crop screenshot
                val imageBase64: String? = if (bmp != null && rect != null) {
                    ScreenCropper.cropAndEncode(bmp, rect)
                } else if (bmp != null) {
                    // No trace → use full screenshot
                    ScreenCropper.cropAndEncode(
                        bmp, Rect(0, 0, bmp.width, bmp.height)
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

            // Tap anywhere to dismiss
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
```

**Step 4: Verify build compiles**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add app/src/main/java/com/example/voiceinput/AssistSession.kt \
    app/src/main/res/layout/assist_overlay.xml \
    app/src/main/res/drawable/rounded_bg.xml
git commit -m "feat: implement AssistSession with overlay, trace, and Vision API"
```

**Step 6: Manual test on device**

1. Build and install: `./gradlew installDebug`
2. On LG V60: Settings → Apps → Default apps → Assist & voice input → select "VoiceInput"
3. Open any app (e.g., browser)
4. Press Assistant button
5. Expected: overlay appears with screenshot behind it, recording starts
6. Trace a region while speaking a question
7. After lifting finger, wait 1.5s
8. Expected: "処理中..." then AI response appears
9. Tap to dismiss

---

### Task 8: Run All Tests

Verify no existing tests were broken.

**Step 1: Run all tests**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: All tests PASS (existing 22 test files + 4 new test files)

**Step 2: Fix any failures**

If `android.graphics.Rect` causes issues in unit tests, add to `app/build.gradle.kts`:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

**Step 3: Commit if any fixes needed**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add -A
git commit -m "fix: ensure all tests pass with new assist feature"
```

---

### Task 9: Edge Cases and Polish

Add handling for the edge cases defined in the design.

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/AssistSession.kt`

**Step 1: Write test for no-trace + voice-only behavior**

Already handled in AssistSession: when `traceRect` is null but `screenshot` is not null, it uses the full screenshot. Verify this path works manually.

**Step 2: Write test for voice-only (no trace) default question**

Already handled in VisionClient: when `question` is null, it uses "この画像について説明してください". This is covered by `VisionClientTest.uses default question when question is null`.

**Step 3: Manual verification checklist**

On the LG V60 device, verify:
- [ ] Assistant button → overlay appears
- [ ] Trace region + voice question → correct answer
- [ ] Voice only (no trace) → full screenshot + question
- [ ] Trace only (no voice) → cropped screenshot + default question
- [ ] Neither trace nor voice → full screenshot + default question
- [ ] Tap response to dismiss
- [ ] Press Assistant button again to dismiss
- [ ] API key not set → error message shown
- [ ] No crash on rapid button presses

**Step 4: Commit final state**

```bash
cd /Users/j/Area/tdd/voice-input-android-app
git add -A
git commit -m "feat: complete Screen Assist feature with edge case handling"
```
