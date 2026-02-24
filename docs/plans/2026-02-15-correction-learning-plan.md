# 修正・学習システム Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 音声入力の誤変換を修正履歴で学習し、フリック入力で直接修正できるIMEにする。

**Architecture:** ローカルJSONファイルに修正履歴を保存し、GPT後処理時にコンテキストとして注入。フリックキーボードはカスタムViewで実装し、IME内で表示/非表示を切替。

**Tech Stack:** Kotlin, SQLiteOpenHelper不使用（JSONファイル+Gson）、OkHttp, mockk, JUnit4

---

### Task 1: CorrectionEntry + CorrectionRepository

修正履歴のデータモデルとJSONファイルベースのリポジトリ。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/CorrectionEntry.kt`
- Create: `app/src/main/java/com/example/voiceinput/CorrectionRepository.kt`
- Create: `app/src/test/java/com/example/voiceinput/CorrectionRepositoryTest.kt`

**Step 1: Write the failing tests**

```kotlin
package com.example.voiceinput

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class CorrectionRepositoryTest {

    private lateinit var storageFile: File
    private lateinit var repo: CorrectionRepository

    @Before
    fun setUp() {
        storageFile = File.createTempFile("corrections_test", ".json")
        repo = CorrectionRepository(storageFile)
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    @Test
    fun `save stores a correction entry`() {
        repo.save("おはよう御座います", "おはようございます")
        val corrections = repo.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals("おはよう御座います", corrections[0].original)
        assertEquals("おはようございます", corrections[0].corrected)
    }

    @Test
    fun `save increments frequency for duplicate pair`() {
        repo.save("御座います", "ございます")
        repo.save("御座います", "ございます")
        repo.save("御座います", "ございます")
        val corrections = repo.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals(3, corrections[0].frequency)
    }

    @Test
    fun `getTopCorrections returns sorted by frequency descending`() {
        repo.save("A", "a")
        repo.save("B", "b")
        repo.save("B", "b")
        repo.save("B", "b")
        repo.save("C", "c")
        repo.save("C", "c")
        val corrections = repo.getTopCorrections(10)
        assertEquals("B", corrections[0].original)
        assertEquals("C", corrections[1].original)
        assertEquals("A", corrections[2].original)
    }

    @Test
    fun `getTopCorrections respects limit`() {
        for (i in 1..10) {
            repo.save("orig$i", "corr$i")
        }
        val corrections = repo.getTopCorrections(3)
        assertEquals(3, corrections.size)
    }

    @Test
    fun `cleanup removes lowest frequency entries when exceeding max`() {
        val smallRepo = CorrectionRepository(storageFile, maxEntries = 3)
        smallRepo.save("A", "a")
        smallRepo.save("B", "b")
        smallRepo.save("B", "b") // freq 2
        smallRepo.save("C", "c")
        smallRepo.save("D", "d") // triggers cleanup

        val corrections = smallRepo.getTopCorrections(10)
        assertTrue(corrections.size <= 3)
        // B (freq 2) should survive, A or C or D (freq 1) may be removed
        assertTrue(corrections.any { it.original == "B" })
    }

    @Test
    fun `data persists across instances`() {
        repo.save("テスト", "test")
        val repo2 = CorrectionRepository(storageFile)
        val corrections = repo2.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals("テスト", corrections[0].original)
    }

    @Test
    fun `handles empty or missing file gracefully`() {
        storageFile.delete()
        val repo2 = CorrectionRepository(storageFile)
        val corrections = repo2.getTopCorrections(10)
        assertTrue(corrections.isEmpty())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CorrectionRepositoryTest" 2>&1 | tail -20`
Expected: FAIL — classes not found

**Step 3: Write CorrectionEntry data class**

```kotlin
package com.example.voiceinput

data class CorrectionEntry(
    val original: String,
    val corrected: String,
    var frequency: Int = 1
)
```

**Step 4: Write CorrectionRepository**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class CorrectionRepository(
    private val storageFile: File,
    private val maxEntries: Int = 500
) {
    private val gson = Gson()

    fun save(original: String, corrected: String) {
        val entries = loadAll().toMutableList()
        val existing = entries.find { it.original == original && it.corrected == corrected }
        if (existing != null) {
            existing.frequency++
        } else {
            entries.add(CorrectionEntry(original, corrected))
        }
        if (entries.size > maxEntries) {
            entries.sortBy { it.frequency }
            while (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
        persist(entries)
    }

    fun getTopCorrections(limit: Int): List<CorrectionEntry> {
        return loadAll()
            .sortedByDescending { it.frequency }
            .take(limit)
    }

    private fun loadAll(): List<CorrectionEntry> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val json = storageFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<CorrectionEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(entries: List<CorrectionEntry>) {
        storageFile.writeText(gson.toJson(entries))
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CorrectionRepositoryTest" 2>&1 | tail -20`
Expected: ALL PASS

**Step 6: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/CorrectionEntry.kt \
        app/src/main/java/com/example/voiceinput/CorrectionRepository.kt \
        app/src/test/java/com/example/voiceinput/CorrectionRepositoryTest.kt
git commit -m "feat: add CorrectionEntry and CorrectionRepository with JSON persistence"
```

---

### Task 2: GptConverter.convertWithHistory()

既存の`convert()`を拡張し、修正履歴をGPTプロンプトに注入。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/GptConverter.kt`
- Modify: `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`

**Step 1: Write the failing test**

Add to `GptConverterTest.kt`:

```kotlin
@Test
fun `convertWithHistory includes correction history in system prompt`() {
    server.enqueue(
        MockResponse()
            .setBody(chatResponse("おはようございます"))
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
    )

    val corrections = listOf(
        CorrectionEntry("おはよう御座います", "おはようございます", 5),
        CorrectionEntry("宜しく", "よろしく", 3)
    )

    val result = converter.convertWithHistory("おはよう御座います", corrections)

    assertEquals("おはようございます", result)

    val body = server.takeRequest().body.readUtf8()
    assertTrue("Should contain correction original", body.contains("おはよう御座います"))
    assertTrue("Should contain correction target", body.contains("おはようございます"))
    assertTrue("Should contain frequency", body.contains("5"))
}

@Test
fun `convertWithHistory with empty corrections behaves like convert`() {
    server.enqueue(
        MockResponse()
            .setBody(chatResponse("テスト結果"))
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
    )

    val result = converter.convertWithHistory("テスト結果", emptyList())
    assertEquals("テスト結果", result)
}

@Test
fun `convertWithHistory returns original on API error`() {
    server.enqueue(MockResponse().setResponseCode(500))

    val result = converter.convertWithHistory("テスト", listOf(
        CorrectionEntry("A", "B", 1)
    ))
    assertEquals("テスト", result)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest.convertWithHistory includes correction history in system prompt" 2>&1 | tail -20`
Expected: FAIL — method not found

**Step 3: Implement convertWithHistory in GptConverter.kt**

Add this method to the `GptConverter` class:

```kotlin
fun convertWithHistory(rawText: String, corrections: List<CorrectionEntry>): String {
    val prompt = if (corrections.isEmpty()) {
        SYSTEM_PROMPT
    } else {
        val historyLines = corrections.joinToString("\n") { entry ->
            "- 「${entry.original}」→「${entry.corrected}」(${entry.frequency}回)"
        }
        """
            $SYSTEM_PROMPT

            以下はユーザーの過去の修正履歴です。同様のパターンがあれば適用してください：
            $historyLines
        """.trimIndent()
    }
    return callGpt(prompt, rawText) ?: rawText
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest" 2>&1 | tail -20`
Expected: ALL PASS

**Step 5: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/GptConverter.kt \
        app/src/test/java/com/example/voiceinput/GptConverterTest.kt
git commit -m "feat: add convertWithHistory to GptConverter for correction-aware processing"
```

---

### Task 3: GptConverter.convertHiraganaToKanji()

フリック入力したひらがなをGPT APIで漢字変換。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/GptConverter.kt`
- Modify: `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`

**Step 1: Write the failing test**

Add to `GptConverterTest.kt`:

```kotlin
@Test
fun `convertHiraganaToKanji returns kanji candidates`() {
    val candidatesJson = """["今日は", "京は", "きょうは"]"""
    server.enqueue(
        MockResponse()
            .setBody("""{"choices":[{"message":{"content":${Gson().toJson(candidatesJson)}}}]}""")
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
    )

    val result = converter.convertHiraganaToKanji("きょうは")

    assertEquals(3, result.size)
    assertEquals("今日は", result[0])

    val body = server.takeRequest().body.readUtf8()
    assertTrue(body.contains("きょうは"))
    assertTrue(body.contains("漢字"))
}

@Test
fun `convertHiraganaToKanji returns empty list on error`() {
    server.enqueue(MockResponse().setResponseCode(500))

    val result = converter.convertHiraganaToKanji("てすと")
    assertTrue(result.isEmpty())
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest.convertHiraganaToKanji returns kanji candidates" 2>&1 | tail -20`
Expected: FAIL — method not found

**Step 3: Implement convertHiraganaToKanji in GptConverter.kt**

Add constant and method to `GptConverter`:

```kotlin
// Add to companion object:
private val KANJI_PROMPT = """
    ひらがなテキストの漢字変換候補を3〜5個生成してください。
    最も自然な変換を先頭にしてください。
    JSON配列のみを返してください。説明は不要です。
    例: 入力「きょう」→ ["今日", "京", "教"]
""".trimIndent()
```

```kotlin
fun convertHiraganaToKanji(hiragana: String): List<String> {
    val response = callGpt(KANJI_PROMPT, hiragana) ?: return emptyList()
    return try {
        val jsonArray = JsonParser.parseString(response).asJsonArray
        jsonArray.map { it.asString }
    } catch (e: Exception) {
        emptyList()
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterTest" 2>&1 | tail -20`
Expected: ALL PASS

**Step 5: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/GptConverter.kt \
        app/src/test/java/com/example/voiceinput/GptConverterTest.kt
git commit -m "feat: add convertHiraganaToKanji for flick input kanji conversion"
```

---

### Task 4: VoiceInputProcessor corrections passthrough

修正履歴をGPT後処理パイプラインに渡す。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt`
- Modify: `app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt`

**Step 1: Write the failing test**

Add to `VoiceInputProcessorTest.kt`:

```kotlin
@Test
fun `stopAndProcess uses convertWithHistory when corrections provided`() = runTest {
    val audioFile = mockk<File>()
    every { audioRecorder.stop() } returns audioFile
    every { whisperClient.transcribe(audioFile, any()) } returns "おはよう御座います"
    val corrections = listOf(CorrectionEntry("おはよう御座います", "おはようございます", 5))
    every { gptConverter.convertWithHistory("おはよう御座います", corrections) } returns "おはようございます"
    every { audioFile.delete() } returns true

    val result = processor.stopAndProcess(corrections = corrections)

    assertNotNull(result)
    verify { gptConverter.convertWithHistory("おはよう御座います", corrections) }
    verify(exactly = 0) { gptConverter.convert(any()) }
}

@Test
fun `stopAndProcess with null corrections uses convert`() = runTest {
    val audioFile = mockk<File>()
    every { audioRecorder.stop() } returns audioFile
    every { whisperClient.transcribe(audioFile, any()) } returns "テスト"
    every { gptConverter.convert("テスト") } returns "テスト"
    every { audioFile.delete() } returns true

    val result = processor.stopAndProcess()

    assertNotNull(result)
    verify { gptConverter.convert("テスト") }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest.stopAndProcess uses convertWithHistory when corrections provided" 2>&1 | tail -20`
Expected: FAIL — no matching `corrections` parameter

**Step 3: Modify stopAndProcess in VoiceInputProcessor.kt**

Change `stopAndProcess` signature and body:

```kotlin
suspend fun stopAndProcess(
    context: String? = null,
    corrections: List<CorrectionEntry>? = null
): List<ConversionChunk>? {
    val audioFile = audioRecorder.stop() ?: return null

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

**Step 4: Run ALL tests to verify nothing is broken**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceInputProcessorTest" 2>&1 | tail -20`
Expected: ALL PASS (existing tests still pass because `corrections` defaults to null)

**Step 5: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt \
        app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt
git commit -m "feat: pass correction history through VoiceInputProcessor to GptConverter"
```

---

### Task 5: FlickKeyboardView

12キーフリック入力のカスタムView。

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt`
- Create: `app/src/test/java/com/example/voiceinput/FlickKeyboardViewTest.kt`

**Step 1: Write the failing tests**

FlickKeyboardViewのコアロジック（フリック方向→文字の解決）をテスト可能な純粋関数として抽出。

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class FlickKeyboardViewTest {

    @Test
    fun `resolveFlick center tap on A-row returns あ`() {
        val result = FlickResolver.resolve("あ", FlickDirection.CENTER)
        assertEquals("あ", result)
    }

    @Test
    fun `resolveFlick up on A-row returns い`() {
        val result = FlickResolver.resolve("あ", FlickDirection.UP)
        assertEquals("い", result)
    }

    @Test
    fun `resolveFlick left on A-row returns う`() {
        val result = FlickResolver.resolve("あ", FlickDirection.LEFT)
        assertEquals("う", result)
    }

    @Test
    fun `resolveFlick down on A-row returns え`() {
        val result = FlickResolver.resolve("あ", FlickDirection.DOWN)
        assertEquals("え", result)
    }

    @Test
    fun `resolveFlick right on A-row returns お`() {
        val result = FlickResolver.resolve("あ", FlickDirection.RIGHT)
        assertEquals("お", result)
    }

    @Test
    fun `resolveFlick center on KA-row returns か`() {
        val result = FlickResolver.resolve("か", FlickDirection.CENTER)
        assertEquals("か", result)
    }

    @Test
    fun `resolveFlick up on KA-row returns き`() {
        val result = FlickResolver.resolve("か", FlickDirection.UP)
        assertEquals("き", result)
    }

    @Test
    fun `resolveFlick covers all rows`() {
        val rows = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")
        for (row in rows) {
            for (dir in FlickDirection.values()) {
                val result = FlickResolver.resolve(row, dir)
                assertNotNull("Row $row direction $dir should return a value", result)
                assertTrue("Result should not be empty", result!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `resolveFlick YA-row has only 3 chars`() {
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.CENTER))
        assertEquals("ゆ", FlickResolver.resolve("や", FlickDirection.LEFT))
        assertEquals("よ", FlickResolver.resolve("や", FlickDirection.RIGHT))
        // UP and DOWN return center character for ya-row
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.UP))
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.DOWN))
    }

    @Test
    fun `resolveFlick WA-row`() {
        assertEquals("わ", FlickResolver.resolve("わ", FlickDirection.CENTER))
        assertEquals("を", FlickResolver.resolve("わ", FlickDirection.LEFT))
        assertEquals("ん", FlickResolver.resolve("わ", FlickDirection.RIGHT))
    }

    @Test
    fun `detectFlickDirection with small movement returns CENTER`() {
        val dir = FlickResolver.detectDirection(0f, 0f, 5f, 3f)
        assertEquals(FlickDirection.CENTER, dir)
    }

    @Test
    fun `detectFlickDirection upward returns UP`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 100f, 30f)
        assertEquals(FlickDirection.UP, dir)
    }

    @Test
    fun `detectFlickDirection downward returns DOWN`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 100f, 170f)
        assertEquals(FlickDirection.DOWN, dir)
    }

    @Test
    fun `detectFlickDirection leftward returns LEFT`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 30f, 100f)
        assertEquals(FlickDirection.LEFT, dir)
    }

    @Test
    fun `detectFlickDirection rightward returns RIGHT`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 170f, 100f)
        assertEquals(FlickDirection.RIGHT, dir)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.FlickKeyboardViewTest" 2>&1 | tail -20`
Expected: FAIL — classes not found

**Step 3: Write FlickResolver (pure logic, no Android dependency)**

Create `FlickKeyboardView.kt` with the resolver logic first:

```kotlin
package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Button
import android.widget.GridLayout
import kotlin.math.abs

enum class FlickDirection {
    CENTER, UP, DOWN, LEFT, RIGHT
}

object FlickResolver {

    private val KANA_MAP = mapOf(
        "あ" to arrayOf("あ", "い", "う", "え", "お"),     // CENTER, UP, LEFT, DOWN, RIGHT
        "か" to arrayOf("か", "き", "く", "け", "こ"),
        "さ" to arrayOf("さ", "し", "す", "せ", "そ"),
        "た" to arrayOf("た", "ち", "つ", "て", "と"),
        "な" to arrayOf("な", "に", "ぬ", "ね", "の"),
        "は" to arrayOf("は", "ひ", "ふ", "へ", "ほ"),
        "ま" to arrayOf("ま", "み", "む", "め", "も"),
        "や" to arrayOf("や", "や", "ゆ", "や", "よ"),     // UP/DOWN → center
        "ら" to arrayOf("ら", "り", "る", "れ", "ろ"),
        "わ" to arrayOf("わ", "わ", "を", "わ", "ん")      // UP/DOWN → center
    )

    private val DIR_INDEX = mapOf(
        FlickDirection.CENTER to 0,
        FlickDirection.UP to 1,
        FlickDirection.LEFT to 2,
        FlickDirection.DOWN to 3,
        FlickDirection.RIGHT to 4
    )

    fun resolve(rowKey: String, direction: FlickDirection): String? {
        val row = KANA_MAP[rowKey] ?: return null
        return row[DIR_INDEX[direction]!!]
    }

    fun detectDirection(startX: Float, startY: Float, endX: Float, endY: Float): FlickDirection {
        val dx = endX - startX
        val dy = endY - startY
        val threshold = 30f

        if (abs(dx) < threshold && abs(dy) < threshold) return FlickDirection.CENTER

        return if (abs(dx) > abs(dy)) {
            if (dx > 0) FlickDirection.RIGHT else FlickDirection.LEFT
        } else {
            if (dy > 0) FlickDirection.DOWN else FlickDirection.UP
        }
    }
}

interface FlickKeyboardListener {
    fun onCharacterInput(char: String)
    fun onBackspace()
    fun onConvert()
    fun onConfirm()
    fun onSwitchToVoice()
}

class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    var listener: FlickKeyboardListener? = null

    private val keys = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")

    init {
        columnCount = 5
        buildKeyboard()
    }

    private fun buildKeyboard() {
        removeAllViews()

        // Row 1: あ か さ た な
        for (i in 0 until 5) {
            addFlickKey(keys[i])
        }
        // Row 2: は ま や ら わ
        for (i in 5 until 10) {
            addFlickKey(keys[i])
        }
        // Row 3: 🎤 ⌫ 変換 確定 (span across)
        addActionButton("🎤", 1) { listener?.onSwitchToVoice() }
        addActionButton("⌫", 1) { listener?.onBackspace() }
        addActionButton("変換", 2) { listener?.onConvert() }
        addActionButton("確定", 1) { listener?.onConfirm() }
    }

    private fun addFlickKey(rowKey: String) {
        val btn = Button(context).apply {
            text = rowKey
            textSize = 18f
            var startX = 0f
            var startY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dir = FlickResolver.detectDirection(startX, startY, event.rawX, event.rawY)
                        val char = FlickResolver.resolve(rowKey, dir)
                        if (char != null) {
                            listener?.onCharacterInput(char)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val params = LayoutParams(spec(UNDEFINED, 1f), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }

    private fun addActionButton(label: String, span: Int, action: () -> Unit) {
        val btn = Button(context).apply {
            text = label
            textSize = 14f
            setOnClickListener { action() }
        }
        val params = LayoutParams(spec(UNDEFINED, span.toFloat()), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.FlickKeyboardViewTest" 2>&1 | tail -20`
Expected: ALL PASS

**Step 5: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt \
        app/src/test/java/com/example/voiceinput/FlickKeyboardViewTest.kt
git commit -m "feat: add FlickResolver and FlickKeyboardView for kana input"
```

---

### Task 6: Layout XML changes

IMEレイアウトにキーボード切替ボタンとフリックキーボードコンテナを追加。

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`

**Step 1: Update layout**

Replace the full `ime_voice_input.xml` content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <!-- 候補エリア -->
    <LinearLayout
        android:id="@+id/candidateArea"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="4dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/candidateText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textSize="14sp"
            android:textIsSelectable="true"
            android:padding="4dp"
            android:background="#F0F0F0" />

        <Button
            android:id="@+id/candidateButton"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:text="候補"
            android:textSize="12sp"
            android:layout_marginStart="4dp"
            style="?android:attr/buttonBarButtonStyle" />

    </LinearLayout>

    <!-- 音声入力モード（デフォルト表示） -->
    <LinearLayout
        android:id="@+id/voiceModeArea"
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

        <ImageButton
            android:id="@+id/keyboardToggleButton"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:src="@android:drawable/ic_dialog_dialer"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="キーボード切替"
            android:layout_marginEnd="8dp" />

        <com.example.voiceinput.MicButtonRingView
            android:id="@+id/micButtonRing"
            android:layout_width="64dp"
            android:layout_height="64dp">

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

    </LinearLayout>

    <!-- フリックキーボード（デフォルト非表示） -->
    <com.example.voiceinput.FlickKeyboardView
        android:id="@+id/flickKeyboard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="4dp"
        android:visibility="gone" />

</LinearLayout>
```

**Step 2: Verify build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash

git add app/src/main/res/layout/ime_voice_input.xml
git commit -m "feat: add keyboard toggle button and FlickKeyboardView to IME layout"
```

---

### Task 7: VoiceInputIME integration

キーボード切替、フリック入力処理、自動学習をIMEに統合。

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: Add new fields and onCreateInputView wiring**

Add fields at the top of VoiceInputIME class:

```kotlin
private var voiceModeArea: LinearLayout? = null
private var flickKeyboard: FlickKeyboardView? = null
private var keyboardToggleButton: ImageButton? = null
private var correctionRepo: CorrectionRepository? = null
private var composingBuffer = StringBuilder()
```

In `onCreateInputView()`, after existing view setup, add:

```kotlin
voiceModeArea = view.findViewById(R.id.voiceModeArea)
flickKeyboard = view.findViewById(R.id.flickKeyboard)
keyboardToggleButton = view.findViewById(R.id.keyboardToggleButton)

// Initialize correction repository
val correctionsFile = File(filesDir, "corrections.json")
correctionRepo = CorrectionRepository(correctionsFile)

keyboardToggleButton?.setOnClickListener {
    showFlickKeyboard()
}

flickKeyboard?.listener = object : FlickKeyboardListener {
    override fun onCharacterInput(char: String) {
        composingBuffer.append(char)
        currentInputConnection?.setComposingText(composingBuffer.toString(), 1)
    }

    override fun onBackspace() {
        if (composingBuffer.isNotEmpty()) {
            composingBuffer.deleteCharAt(composingBuffer.length - 1)
            if (composingBuffer.isEmpty()) {
                currentInputConnection?.finishComposingText()
            } else {
                currentInputConnection?.setComposingText(composingBuffer.toString(), 1)
            }
        } else {
            currentInputConnection?.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            )
            currentInputConnection?.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
            )
        }
    }

    override fun onConvert() {
        if (composingBuffer.isEmpty()) return
        val hiragana = composingBuffer.toString()
        serviceScope.launch {
            val prefsManager = PreferencesManager(
                getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
            )
            val apiKey = prefsManager.getApiKey() ?: return@launch
            val converter = GptConverter(apiKey)
            val candidates = withContext(Dispatchers.IO) {
                converter.convertHiraganaToKanji(hiragana)
            }
            if (candidates.isNotEmpty()) {
                showKanjiCandidatePopup(candidates, hiragana)
            }
        }
    }

    override fun onConfirm() {
        if (composingBuffer.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composingBuffer.clear()
        }
    }

    override fun onSwitchToVoice() {
        if (composingBuffer.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composingBuffer.clear()
        }
        showVoiceMode()
    }
}
```

**Step 2: Add keyboard toggle methods**

```kotlin
private fun showFlickKeyboard() {
    voiceModeArea?.visibility = View.GONE
    flickKeyboard?.visibility = View.VISIBLE
}

private fun showVoiceMode() {
    flickKeyboard?.visibility = View.GONE
    voiceModeArea?.visibility = View.VISIBLE
}
```

**Step 3: Add kanji candidate popup**

```kotlin
private fun showKanjiCandidatePopup(candidates: List<String>, hiragana: String) {
    val anchor = flickKeyboard ?: return
    val popup = PopupMenu(this, anchor)

    candidates.forEachIndexed { i, candidate ->
        popup.menu.add(0, i, i, candidate)
    }

    popup.setOnMenuItemClickListener { item ->
        val selected = candidates[item.itemId]
        composingBuffer.clear()
        currentInputConnection?.commitText(selected, 1)
        true
    }
    popup.show()
}
```

**Step 4: Modify onMicReleasedForNewInput to use correction history**

Replace the `stopAndProcess()` call in `onMicReleasedForNewInput`:

```kotlin
private fun onMicReleasedForNewInput(proc: VoiceInputProcessor) {
    statusText?.text = "変換中..."

    serviceScope.launch {
        val corrections = correctionRepo?.getTopCorrections(20)
        val chunks = proc.stopAndProcess(corrections = corrections)
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
        statusText?.text = "長押し/ダブルタップで音声入力"
    }
}
```

**Step 5: Add auto-learning to replaceRange**

At the end of `replaceRange()`, after `showCandidateArea(newText)`, add:

```kotlin
// Auto-learn the correction
val originalFragment = fullText.substring(selStart, selEnd)
if (originalFragment != replacement) {
    correctionRepo?.save(originalFragment, replacement)
}
```

**Step 6: Add required imports at top of file**

```kotlin
import android.widget.ImageButton
import android.widget.PopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
```

**Step 7: Verify build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 8: Run all existing tests to verify nothing broken**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: ALL PASS

**Step 9: Commit**

```bash

git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: integrate flick keyboard, correction history, and auto-learning into IME"
```

---

### Task 8: Full test + APK build

**Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS

**Step 2: Build release APK**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Copy APK to sync folder**

```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk
```

**Step 4: Verify APK exists**

```bash
ls -la ~/Sync/APK/voice-input.apk
```
