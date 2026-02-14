# チャンク選択UI 実装計画

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** GPT変換結果をチャンク単位で表示し、Whisper原文との差分部分をタップしてraw/convertedを切り替えられるIME UIを実装する。

**Architecture:** GptConverterのプロンプトを変更してJSON配列（raw/convertedペア）を返させる。VoiceInputProcessorの戻り値をConversionChunkのリストに変更。VoiceInputIMEに候補バーUIを追加し、差分チャンクのタップで入力済みテキストを差し替える。

**Tech Stack:** Kotlin, Android InputMethodService, OkHttp, Gson, JUnit4, MockK, MockWebServer

---

### Task 1: ConversionChunk データクラス作成

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/ConversionChunk.kt`
- Test: `app/src/test/java/com/example/voiceinput/ConversionChunkTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/example/voiceinput/ConversionChunkTest.kt
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class ConversionChunkTest {

    @Test
    fun `isDifferent returns true when raw and converted differ`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git")
        assertTrue(chunk.isDifferent)
    }

    @Test
    fun `isDifferent returns false when raw and converted are same`() {
        val chunk = ConversionChunk(raw = "こんにちは", converted = "こんにちは")
        assertFalse(chunk.isDifferent)
    }

    @Test
    fun `displayText returns converted by default`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git")
        assertEquals("git", chunk.displayText)
    }

    @Test
    fun `displayText returns raw when useRaw is true`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git", useRaw = true)
        assertEquals("ギット", chunk.displayText)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.ConversionChunkTest"`
Expected: FAIL — `Unresolved reference 'ConversionChunk'`

**Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/example/voiceinput/ConversionChunk.kt
package com.example.voiceinput

data class ConversionChunk(
    val raw: String,
    val converted: String,
    var useRaw: Boolean = false
) {
    val isDifferent: Boolean
        get() = raw != converted

    val displayText: String
        get() = if (useRaw) raw else converted
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.ConversionChunkTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/ConversionChunk.kt \
       app/src/test/java/com/example/voiceinput/ConversionChunkTest.kt
git commit -m "feat: add ConversionChunk data class"
```

---

### Task 2: GptConverter — JSON配列レスポンスに変更

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/GptConverter.kt`
- Modify: `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`

**Step 1: Write the failing tests**

既存テストを修正し、新しいテストを追加する。`convert()` → `convertToChunks()` に変更。

```kotlin
// app/src/test/java/com/example/voiceinput/GptConverterTest.kt
package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GptConverterTest {

    private lateinit var server: MockWebServer
    private lateinit var converter: GptConverter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        converter = GptConverter(
            apiKey = "sk-test",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun chatResponse(content: String): String {
        // contentにJSON配列をエスケープせずに入れたいのでエスケープ処理
        val escaped = content.replace("\"", "\\\"").replace("\n", "\\n")
        return """
        {
            "choices": [{
                "message": {
                    "content": "$escaped"
                }
            }]
        }
        """.trimIndent()
    }

    @Test
    fun `convertToChunks returns chunks with raw and converted`() {
        val jsonArray = """[{"raw":"ギット","converted":"git"},{"raw":"ステータス","converted":"status"}]"""
        server.enqueue(
            MockResponse()
                .setBody(chatResponse(jsonArray))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val chunks = converter.convertToChunks("ギットステータス")

        assertEquals(2, chunks.size)
        assertEquals("ギット", chunks[0].raw)
        assertEquals("git", chunks[0].converted)
        assertEquals("ステータス", chunks[1].raw)
        assertEquals("status", chunks[1].converted)
    }

    @Test
    fun `convertToChunks returns single chunk on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val chunks = converter.convertToChunks("テスト入力")

        assertEquals(1, chunks.size)
        assertEquals("テスト入力", chunks[0].raw)
        assertEquals("テスト入力", chunks[0].converted)
    }

    @Test
    fun `convertToChunks returns single chunk when response is not JSON array`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("git status"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val chunks = converter.convertToChunks("ギットステータス")

        assertEquals(1, chunks.size)
        assertEquals("ギットステータス", chunks[0].raw)
        assertEquals("git status", chunks[0].converted)
    }

    @Test
    fun `convertToChunks sends correct prompt requesting JSON`() {
        val jsonArray = """[{"raw":"テスト","converted":"テスト"}]"""
        server.enqueue(
            MockResponse()
                .setBody(chatResponse(jsonArray))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        converter.convertToChunks("テスト")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("JSON"))
        assertTrue(body.contains("raw"))
        assertTrue(body.contains("converted"))
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest"`
Expected: FAIL — `Unresolved reference 'convertToChunks'`

**Step 3: Write implementation**

```kotlin
// app/src/main/java/com/example/voiceinput/GptConverter.kt
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GptConverter(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private val SYSTEM_PROMPT = """
            あなたは音声入力アシスタントです。
            ユーザーの発話テキストを受け取り、適切な出力に変換してください。

            ルール：
            - 発話がコマンドの意図なら、実行可能なコマンド文字列に変換する
            - 発話が日本語の文章なら、自然な日本語としてそのまま返す
            - 必ずJSON配列形式で返す。各要素は {"raw": "元の部分", "converted": "変換後"} の形式
            - 意味のある単位（単語・フレーズ）ごとにチャンクに分割する
            - 余計な説明は一切付けず、JSON配列のみを返す

            例：
            入力: "ギットステータスを確認して"
            出力: [{"raw":"ギットステータス","converted":"git status"},{"raw":"を確認して","converted":"を確認して"}]

            入力: "こんにちは世界"
            出力: [{"raw":"こんにちは","converted":"こんにちは"},{"raw":"世界","converted":"世界"}]
        """.trimIndent()
    }

    fun convertToChunks(rawText: String): List<ConversionChunk> {
        val messages = listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to rawText)
        )
        val body = gson.toJson(
            mapOf(
                "model" to "gpt-4o-mini",
                "messages" to messages,
                "temperature" to 0.3
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
            if (!response.isSuccessful) return listOf(ConversionChunk(rawText, rawText))
            val responseBody = response.body?.string() ?: return listOf(ConversionChunk(rawText, rawText))
            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()

            parseChunks(rawText, content)
        } catch (e: Exception) {
            listOf(ConversionChunk(rawText, rawText))
        }
    }

    private fun parseChunks(rawText: String, content: String): List<ConversionChunk> {
        return try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val items: List<Map<String, String>> = gson.fromJson(content, type)
            items.map { item ->
                ConversionChunk(
                    raw = item["raw"] ?: "",
                    converted = item["converted"] ?: ""
                )
            }
        } catch (e: Exception) {
            // JSONパース失敗 → 従来形式（プレーンテキスト）として扱う
            listOf(ConversionChunk(rawText, content))
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/GptConverter.kt \
       app/src/test/java/com/example/voiceinput/GptConverterTest.kt
git commit -m "feat: change GptConverter to return ConversionChunk list"
```

---

### Task 3: VoiceInputProcessor — チャンク対応

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt`

**Step 1: Write the failing tests**

`stopAndProcess()` の戻り値を `String?` → `List<ConversionChunk>?` に変更。

```kotlin
// app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt
package com.example.voiceinput

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceInputProcessorTest {

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperClient: WhisperClient
    private lateinit var gptConverter: GptConverter
    private lateinit var processor: VoiceInputProcessor

    @Before
    fun setUp() {
        audioRecorder = mockk(relaxed = true)
        whisperClient = mockk()
        gptConverter = mockk()
        processor = VoiceInputProcessor(audioRecorder, whisperClient, gptConverter)
    }

    @Test
    fun `startRecording delegates to audioRecorder`() {
        every { audioRecorder.start() } returns true
        val result = processor.startRecording()
        assertTrue(result)
        verify { audioRecorder.start() }
    }

    @Test
    fun `startRecording returns false when recorder fails`() {
        every { audioRecorder.start() } returns false
        val result = processor.startRecording()
        assertFalse(result)
    }

    @Test
    fun `stopAndProcess returns chunks on success`() = runTest {
        val audioFile = mockk<File>()
        val chunks = listOf(
            ConversionChunk("ギット", "git"),
            ConversionChunk("ステータス", "status")
        )
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile) } returns "ギットステータス"
        every { gptConverter.convertToChunks("ギットステータス") } returns chunks
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertEquals(2, result!!.size)
        assertEquals("git", result[0].converted)
        assertEquals("status", result[1].converted)
        verify { audioFile.delete() }
    }

    @Test
    fun `stopAndProcess returns null when recorder returns null`() = runTest {
        every { audioRecorder.stop() } returns null

        val result = processor.stopAndProcess()

        assertNull(result)
    }

    @Test
    fun `stopAndProcess returns null when transcription fails`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile) } returns null
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertNull(result)
        verify { audioFile.delete() }
    }

    @Test
    fun `isRecording delegates to audioRecorder`() {
        every { audioRecorder.isRecording } returns true
        assertTrue(processor.isRecording)
        every { audioRecorder.isRecording } returns false
        assertFalse(processor.isRecording)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest"`
Expected: FAIL — `convertToChunks` not found / type mismatch

**Step 3: Write implementation**

```kotlin
// app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt
package com.example.voiceinput

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceInputProcessor(
    private val audioRecorder: AudioRecorder,
    private val whisperClient: WhisperClient,
    private val gptConverter: GptConverter
) {
    val isRecording: Boolean
        get() = audioRecorder.isRecording

    fun startRecording(): Boolean {
        return audioRecorder.start()
    }

    suspend fun stopAndProcess(): List<ConversionChunk>? {
        val audioFile = audioRecorder.stop() ?: return null

        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile)
            } ?: return null

            return withContext(Dispatchers.IO) {
                gptConverter.convertToChunks(rawText)
            }
        } finally {
            audioFile.delete()
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt \
       app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt
git commit -m "feat: change VoiceInputProcessor to return chunks"
```

---

### Task 4: IMEレイアウト — 候補バー追加

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`

**Step 1: Update layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <!-- 候補バー -->
    <HorizontalScrollView
        android:id="@+id/imeCandidateScroll"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:scrollbars="none"
        android:visibility="gone">

        <LinearLayout
            android:id="@+id/imeCandidateBar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:padding="4dp" />

    </HorizontalScrollView>

    <!-- ボタン行 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/imeStatusText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/ime_hint"
            android:textSize="14sp" />

        <ImageView
            android:id="@+id/imeMicButton"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:src="@drawable/ic_mic"
            android:background="@drawable/mic_button_background"
            android:padding="14dp"
            android:contentDescription="@string/ime_mic_description"
            android:layout_marginStart="8dp" />

        <ImageButton
            android:id="@+id/imeSwitchButton"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:src="@drawable/ic_keyboard"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/ime_switch_keyboard"
            android:layout_marginStart="8dp" />

    </LinearLayout>

</LinearLayout>
```

**Step 2: Commit**

```bash
git add app/src/main/res/layout/ime_voice_input.xml
git commit -m "feat: add candidate bar to IME layout"
```

---

### Task 5: VoiceInputIME — 候補バーUIとテキスト差し替え

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`
- Create: `app/src/main/res/drawable/chunk_highlight_bg.xml`
- Create: `app/src/main/res/drawable/chunk_normal_bg.xml`

**Step 1: Create chunk background drawables**

```xml
<!-- app/src/main/res/drawable/chunk_highlight_bg.xml -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FFFFCC80" />
    <corners android:radius="4dp" />
    <padding android:left="8dp" android:right="8dp" android:top="4dp" android:bottom="4dp" />
</shape>
```

```xml
<!-- app/src/main/res/drawable/chunk_normal_bg.xml -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FFE0E0E0" />
    <corners android:radius="4dp" />
    <padding android:left="8dp" android:right="8dp" android:top="4dp" android:bottom="4dp" />
</shape>
```

**Step 2: Update VoiceInputIME**

```kotlin
// app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
package com.example.voiceinput

import android.inputmethodservice.InputMethodService
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
        candidateBar = view.findViewById(R.id.imeCandidateBar)
        candidateScroll = view.findViewById(R.id.imeCandidateScroll)
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
            statusText?.text = "長押しで音声入力"
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

        // 変更チャンクより後ろの文字数を計算
        val charsAfter = chunks.drop(chunkIndex + 1).sumOf { it.displayText.length }
        // 変更チャンクの古いテキスト長
        val oldLen = oldText.length

        // カーソルを末尾と仮定して、後ろから位置を特定
        ic.deleteSurroundingText(charsAfter + oldLen, 0)
        ic.commitText(newText, 1)
        // 後続テキストを再入力
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
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt \
       app/src/main/res/drawable/chunk_highlight_bg.xml \
       app/src/main/res/drawable/chunk_normal_bg.xml
git commit -m "feat: add candidate bar with chunk selection to IME"
```

---

### Task 6: 全テスト + ビルド確認

**Step 1: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS

**Step 2: Build APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Push**

```bash
git push origin main
```
