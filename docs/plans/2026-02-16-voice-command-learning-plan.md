# 音声コマンド学習機能 Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 🧠学習モードでコマンドを登録し、音声サンプルを録音・保存する機能を追加する（Phase 1: 分類器なし）

**Architecture:** ModeIconPagerAdapterを3ページに拡張（🎤/🧠/⌨️）。学習モードは左側にCommandLearningView（コマンド一覧+英数字キーボード）を表示。コマンド辞書はVoiceCommandRepositoryでJSON永続化。音声サンプルは既存AudioRecorderで録音しWAVで保存。

**Tech Stack:** Kotlin, Android IME, Gson (JSON), AudioRecord (WAV録音)

---

### Task 1: VoiceCommand data class

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/VoiceCommand.kt`
- Create: `app/src/test/java/com/example/voiceinput/VoiceCommandTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `default values are correct`() {
        val cmd = VoiceCommand(id = "test", label = "test", text = "test\n")
        assertFalse(cmd.auto)
        assertEquals(0.95f, cmd.threshold)
        assertEquals(0, cmd.sampleCount)
        assertTrue(cmd.enabled)
    }

    @Test
    fun `custom values are preserved`() {
        val cmd = VoiceCommand(
            id = "exit", label = "exit", text = "/exit\n",
            auto = false, threshold = 0.98f, sampleCount = 3, enabled = true
        )
        assertEquals("exit", cmd.id)
        assertEquals("/exit\n", cmd.text)
        assertEquals(0.98f, cmd.threshold)
        assertEquals(3, cmd.sampleCount)
    }
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceCommandTest" 2>&1 | tail -10`

**Step 3: 実装**

```kotlin
package com.example.voiceinput

data class VoiceCommand(
    val id: String,
    val label: String,
    val text: String,
    val auto: Boolean = false,
    val threshold: Float = 0.95f,
    var sampleCount: Int = 0,
    val enabled: Boolean = true
)
```

**Step 4: テスト実行 → 成功確認**

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceCommand.kt app/src/test/java/com/example/voiceinput/VoiceCommandTest.kt
git commit -m "feat: add VoiceCommand data class"
```

---

### Task 2: VoiceCommandRepository

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/VoiceCommandRepository.kt`
- Create: `app/src/test/java/com/example/voiceinput/VoiceCommandRepositoryTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceCommandRepositoryTest {

    private lateinit var storageFile: File
    private lateinit var samplesDir: File
    private lateinit var repo: VoiceCommandRepository

    @Before
    fun setUp() {
        storageFile = File.createTempFile("voice_commands_test", ".json")
        samplesDir = File(storageFile.parentFile, "test_samples_${System.nanoTime()}")
        samplesDir.mkdirs()
        repo = VoiceCommandRepository(storageFile, samplesDir)
    }

    @After
    fun tearDown() {
        storageFile.delete()
        samplesDir.deleteRecursively()
    }

    @Test
    fun `addCommand creates a new command`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        assertEquals("exit", cmd.label)
        assertEquals("/exit\n", cmd.text)
        assertEquals(0, cmd.sampleCount)
    }

    @Test
    fun `getCommands returns all commands`() {
        repo.addCommand("exit", "/exit\n")
        repo.addCommand("ls", "ls\n")
        val commands = repo.getCommands()
        assertEquals(2, commands.size)
    }

    @Test
    fun `deleteCommand removes command and sample files`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        // Create a fake sample file
        File(samplesDir, "${cmd.id}_0.wav").writeText("fake")
        repo.deleteCommand(cmd.id)
        val commands = repo.getCommands()
        assertTrue(commands.isEmpty())
        assertFalse(File(samplesDir, "${cmd.id}_0.wav").exists())
    }

    @Test
    fun `updateSampleCount updates the count`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        repo.updateSampleCount(cmd.id, 3)
        val updated = repo.getCommands().first()
        assertEquals(3, updated.sampleCount)
    }

    @Test
    fun `data persists across instances`() {
        repo.addCommand("exit", "/exit\n")
        val repo2 = VoiceCommandRepository(storageFile, samplesDir)
        assertEquals(1, repo2.getCommands().size)
    }

    @Test
    fun `handles empty file gracefully`() {
        storageFile.delete()
        val repo2 = VoiceCommandRepository(storageFile, samplesDir)
        assertTrue(repo2.getCommands().isEmpty())
    }

    @Test
    fun `getSampleFile returns correct path`() {
        val file = repo.getSampleFile("exit", 2)
        assertEquals("exit_2.wav", file.name)
        assertEquals(samplesDir, file.parentFile)
    }
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.VoiceCommandRepositoryTest" 2>&1 | tail -10`

**Step 3: 実装**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class VoiceCommandRepository(
    private val storageFile: File,
    private val samplesDir: File
) {
    private val gson = Gson()

    init {
        if (!samplesDir.exists()) samplesDir.mkdirs()
    }

    @Synchronized
    fun getCommands(): List<VoiceCommand> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val json = storageFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<VoiceCommand>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addCommand(label: String, text: String): VoiceCommand {
        val commands = getCommands().toMutableList()
        val id = label.lowercase().replace(Regex("[^a-z0-9]"), "_")
        val cmd = VoiceCommand(id = id, label = label, text = text)
        commands.add(cmd)
        persist(commands)
        return cmd
    }

    @Synchronized
    fun deleteCommand(id: String) {
        val commands = getCommands().toMutableList()
        commands.removeAll { it.id == id }
        persist(commands)
        // Delete sample files
        samplesDir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    @Synchronized
    fun updateSampleCount(id: String, count: Int) {
        val commands = getCommands().toMutableList()
        val index = commands.indexOfFirst { it.id == id }
        if (index >= 0) {
            commands[index] = commands[index].copy(sampleCount = count)
            persist(commands)
        }
    }

    fun getSampleFile(commandId: String, index: Int): File {
        return File(samplesDir, "${commandId}_${index}.wav")
    }

    private fun persist(commands: List<VoiceCommand>) {
        storageFile.writeText(gson.toJson(commands))
    }
}
```

**Step 4: テスト実行 → 成功確認**

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceCommandRepository.kt app/src/test/java/com/example/voiceinput/VoiceCommandRepositoryTest.kt
git commit -m "feat: add VoiceCommandRepository with JSON persistence"
```

---

### Task 3: ModeIconPagerAdapterに🧠ページ追加

**Files:**
- Create: `app/src/main/res/drawable/ic_brain.xml`
- Create: `app/src/main/res/layout/icon_page_brain.xml`
- Modify: `app/src/main/java/com/example/voiceinput/ModeIconPagerAdapter.kt`

**Step 1: 脳アイコンdrawableを作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4zM11,17h2v2h-2z" />
</vector>
```

Note: これは暫定アイコン（?マーク風）。Material Iconsのpsychologyアイコンが理想だが、パスが長いので後で差し替え可能。

**Step 2: icon_page_brain.xmlを作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center">

    <ImageView
        android:id="@+id/brainIcon"
        android:layout_width="56dp"
        android:layout_height="56dp"
        android:layout_gravity="center"
        android:src="@drawable/ic_brain"
        android:background="@drawable/mic_button_background"
        android:padding="14dp"
        android:contentDescription="学習モード" />

</FrameLayout>
```

**Step 3: ModeIconPagerAdapterを更新**

```kotlin
companion object {
    const val PAGE_MIC = 0
    const val PAGE_BRAIN = 1
    const val PAGE_KEYBOARD = 2
    const val PAGE_COUNT = 3
}
```

`onCreateViewHolder`のwhenに追加:
```kotlin
PAGE_BRAIN -> R.layout.icon_page_brain
```

**Step 4: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`

**Step 5: コミット**

```bash
git add app/src/main/res/drawable/ic_brain.xml app/src/main/res/layout/icon_page_brain.xml app/src/main/java/com/example/voiceinput/ModeIconPagerAdapter.kt
git commit -m "feat: add brain icon page to ModeIconPagerAdapter (3 pages)"
```

---

### Task 4: VoiceInputIMEに🧠モードを接続

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: レイアウトに学習モードエリア追加**

`ime_voice_input.xml`のFrameLayout内（voiceModeAreaとflickKeyboardの隣）に追加:

```xml
<!-- 学習モード（デフォルト非表示） -->
<LinearLayout
    android:id="@+id/learningModeArea"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:visibility="gone"
    android:padding="4dp">

    <TextView
        android:id="@+id/learningStatusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="コマンド学習モード"
        android:textSize="12sp"
        android:padding="4dp" />

</LinearLayout>
```

**Step 2: VoiceInputIME.ktを更新**

新フィールド追加:
```kotlin
private var learningModeArea: LinearLayout? = null
private var commandRepo: VoiceCommandRepository? = null
```

`onCreateInputView()`内で:
```kotlin
learningModeArea = view.findViewById(R.id.learningModeArea)

val commandsFile = File(filesDir, "voice_commands.json")
val samplesDir = File(filesDir, "voice_samples")
commandRepo = VoiceCommandRepository(commandsFile, samplesDir)
```

`ModeIconPagerAdapter`のページ定数更新に合わせ、onPageSelectedコールバック更新:
```kotlin
ModeIconPagerAdapter.PAGE_MIC -> showVoiceModeContent()
ModeIconPagerAdapter.PAGE_BRAIN -> showLearningModeContent()
ModeIconPagerAdapter.PAGE_KEYBOARD -> showFlickKeyboardContent()
```

新メソッド追加:
```kotlin
private fun showLearningModeContent() {
    isFlickMode = false
    voiceModeArea?.visibility = View.GONE
    flickKeyboard?.visibility = View.GONE
    learningModeArea?.visibility = View.VISIBLE
}
```

`showVoiceModeContent()`と`showFlickKeyboardContent()`に`learningModeArea?.visibility = View.GONE`を追加。

**Step 3: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`

**Step 4: APKビルド確認**

Run: `./gradlew assembleDebug 2>&1 | tail -5`

**Step 5: コミット**

```bash
git add app/src/main/res/layout/ime_voice_input.xml app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: connect brain mode to VoiceInputIME with learning mode area"
```

---

### Task 5: AlphanumericKeyboardView

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/AlphanumericKeyboardView.kt`
- Create: `app/src/test/java/com/example/voiceinput/AlphanumericKeyboardViewTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class AlphanumericKeyboardViewTest {

    @Test
    fun `KEY_ROWS contains all lowercase letters`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        for (c in 'a'..'z') {
            assertTrue("Missing key: $c", allKeys.contains(c.toString()))
        }
    }

    @Test
    fun `KEY_ROWS contains digits`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        for (c in '0'..'9') {
            assertTrue("Missing key: $c", allKeys.contains(c.toString()))
        }
    }

    @Test
    fun `KEY_ROWS contains special keys`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        assertTrue("Missing /", allKeys.contains("/"))
        assertTrue("Missing space", allKeys.contains("␣"))
        assertTrue("Missing backspace", allKeys.contains("⌫"))
        assertTrue("Missing newline", allKeys.contains("⏎"))
    }
}
```

**Step 2: テスト実行 → 失敗確認**

**Step 3: 実装**

```kotlin
package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.GridLayout

data class KeyDef(val display: String, val value: String)

interface AlphanumericKeyboardListener {
    fun onKeyInput(value: String)
    fun onBackspace()
}

class AlphanumericKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    var listener: AlphanumericKeyboardListener? = null

    companion object {
        val KEY_ROWS = listOf(
            listOf(
                KeyDef("a","a"), KeyDef("b","b"), KeyDef("c","c"), KeyDef("d","d"),
                KeyDef("e","e"), KeyDef("f","f"), KeyDef("g","g"), KeyDef("h","h"),
                KeyDef("i","i"), KeyDef("j","j")
            ),
            listOf(
                KeyDef("k","k"), KeyDef("l","l"), KeyDef("m","m"), KeyDef("n","n"),
                KeyDef("o","o"), KeyDef("p","p"), KeyDef("q","q"), KeyDef("r","r"),
                KeyDef("s","s"), KeyDef("t","t")
            ),
            listOf(
                KeyDef("u","u"), KeyDef("v","v"), KeyDef("w","w"), KeyDef("x","x"),
                KeyDef("y","y"), KeyDef("z","z"), KeyDef("/","/"), KeyDef("-","-"),
                KeyDef("␣"," "), KeyDef(".",".")
            ),
            listOf(
                KeyDef("0","0"), KeyDef("1","1"), KeyDef("2","2"), KeyDef("3","3"),
                KeyDef("4","4"), KeyDef("5","5"), KeyDef("6","6"), KeyDef("7","7"),
                KeyDef("8","8"), KeyDef("9","9")
            ),
            listOf(
                KeyDef("⏎","\n"), KeyDef("⌫","BACKSPACE")
            )
        )
    }

    init {
        columnCount = 10
        buildKeyboard()
    }

    private fun buildKeyboard() {
        removeAllViews()
        for (row in KEY_ROWS) {
            for (key in row) {
                addKeyButton(key, if (row == KEY_ROWS.last()) calcSpan(key, row) else 1)
            }
        }
    }

    private fun calcSpan(key: KeyDef, row: List<KeyDef>): Int {
        // Last row: split 10 columns across fewer keys
        return 10 / row.size
    }

    private fun addKeyButton(key: KeyDef, span: Int) {
        val btn = Button(context).apply {
            text = key.display
            textSize = 12f
            setOnClickListener {
                if (key.value == "BACKSPACE") {
                    listener?.onBackspace()
                } else {
                    listener?.onKeyInput(key.value)
                }
            }
        }
        val params = LayoutParams(spec(UNDEFINED, span.toFloat()), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }
}
```

**Step 4: テスト実行 → 成功確認**

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/AlphanumericKeyboardView.kt app/src/test/java/com/example/voiceinput/AlphanumericKeyboardViewTest.kt
git commit -m "feat: add AlphanumericKeyboardView for command input"
```

---

### Task 6: CommandLearningView（コマンド一覧+追加+録音UI）

**Files:**
- Create: `app/src/main/res/layout/view_command_learning.xml`
- Create: `app/src/main/java/com/example/voiceinput/CommandLearningView.kt`

**Step 1: レイアウトを作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <!-- 入力エリア: コマンド名 + 送信文字列 + 追加ボタン -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="4dp"
        android:gravity="center_vertical">

        <TextView
            android:id="@+id/inputDisplay"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="コマンドを入力..."
            android:textSize="14sp"
            android:background="#F0F0F0"
            android:padding="8dp"
            android:minHeight="36dp" />

        <Button
            android:id="@+id/addButton"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:text="＋追加"
            android:textSize="12sp"
            android:layout_marginStart="4dp" />

    </LinearLayout>

    <!-- コマンド一覧 (スクロール可能) -->
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginBottom="4dp">

        <LinearLayout
            android:id="@+id/commandList"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" />

    </ScrollView>

    <!-- 英数字キーボード -->
    <com.example.voiceinput.AlphanumericKeyboardView
        android:id="@+id/alphaKeyboard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="2dp" />

</LinearLayout>
```

**Step 2: CommandLearningViewを実装**

```kotlin
package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

interface CommandLearningListener {
    fun onRecordSample(commandId: String, sampleIndex: Int)
    fun onDeleteCommand(commandId: String)
}

class CommandLearningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var listener: CommandLearningListener? = null
    private var inputBuffer = StringBuilder()
    private var inputDisplay: TextView? = null
    private var commandList: LinearLayout? = null
    private var addButton: Button? = null
    private var commandRepo: VoiceCommandRepository? = null
    private var inputMode: InputMode = InputMode.LABEL
    private var pendingLabel: String? = null

    enum class InputMode { LABEL, TEXT }

    init {
        orientation = VERTICAL
        val view = LayoutInflater.from(context).inflate(R.layout.view_command_learning, this, true)
        inputDisplay = view.findViewById(R.id.inputDisplay)
        commandList = view.findViewById(R.id.commandList)
        addButton = view.findViewById(R.id.addButton)

        addButton?.setOnClickListener { onAddTapped() }

        val keyboard = view.findViewById<AlphanumericKeyboardView>(R.id.alphaKeyboard)
        keyboard?.listener = object : AlphanumericKeyboardListener {
            override fun onKeyInput(value: String) {
                inputBuffer.append(value)
                updateInputDisplay()
            }
            override fun onBackspace() {
                if (inputBuffer.isNotEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.length - 1)
                    updateInputDisplay()
                }
            }
        }

        updateInputDisplay()
    }

    fun setRepository(repo: VoiceCommandRepository) {
        commandRepo = repo
        refreshCommandList()
    }

    private fun updateInputDisplay() {
        val hint = when (inputMode) {
            InputMode.LABEL -> "コマンド名を入力..."
            InputMode.TEXT -> "送信文字列を入力..."
        }
        inputDisplay?.text = if (inputBuffer.isEmpty()) "" else inputBuffer.toString()
        inputDisplay?.hint = hint
    }

    private fun onAddTapped() {
        val input = inputBuffer.toString().trim()
        if (input.isEmpty()) return

        when (inputMode) {
            InputMode.LABEL -> {
                pendingLabel = input
                inputBuffer.clear()
                inputMode = InputMode.TEXT
                updateInputDisplay()
                addButton?.text = "確定"
            }
            InputMode.TEXT -> {
                val label = pendingLabel ?: return
                commandRepo?.addCommand(label, input)
                inputBuffer.clear()
                inputMode = InputMode.LABEL
                pendingLabel = null
                updateInputDisplay()
                addButton?.text = "＋追加"
                refreshCommandList()
            }
        }
    }

    fun refreshCommandList() {
        commandList?.removeAllViews()
        val commands = commandRepo?.getCommands() ?: return

        for (cmd in commands) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(8, 4, 8, 4)
            }

            val label = TextView(context).apply {
                text = "${cmd.label}  →  ${cmd.text.replace("\n", "\\n")}"
                textSize = 12f
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            val recordBtn = Button(context).apply {
                text = "🔴 ${cmd.sampleCount}/3"
                textSize = 10f
                setOnClickListener {
                    listener?.onRecordSample(cmd.id, cmd.sampleCount)
                }
            }

            val deleteBtn = Button(context).apply {
                text = "🗑"
                textSize = 10f
                setOnClickListener {
                    listener?.onDeleteCommand(cmd.id)
                }
            }

            row.addView(label)
            row.addView(recordBtn)
            row.addView(deleteBtn)
            commandList?.addView(row)
        }
    }
}
```

**Step 3: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`

**Step 4: コミット**

```bash
git add app/src/main/res/layout/view_command_learning.xml app/src/main/java/com/example/voiceinput/CommandLearningView.kt
git commit -m "feat: add CommandLearningView with command list and alphanumeric keyboard"
```

---

### Task 7: 学習モードをVoiceInputIMEに統合

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: ime_voice_input.xmlの学習モードエリアを更新**

Task 4で追加した`learningModeArea` LinearLayoutを`CommandLearningView`に置き換え:

```xml
<!-- 学習モード（デフォルト非表示） -->
<com.example.voiceinput.CommandLearningView
    android:id="@+id/commandLearning"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

**Step 2: VoiceInputIME.ktを更新**

フィールド変更:
```kotlin
// learningModeArea → commandLearning
private var commandLearning: CommandLearningView? = null
```

`onCreateInputView()`内で:
```kotlin
commandLearning = view.findViewById(R.id.commandLearning)
commandLearning?.setRepository(commandRepo!!)
commandLearning?.listener = object : CommandLearningListener {
    override fun onRecordSample(commandId: String, sampleIndex: Int) {
        recordCommandSample(commandId, sampleIndex)
    }
    override fun onDeleteCommand(commandId: String) {
        commandRepo?.deleteCommand(commandId)
        commandLearning?.refreshCommandList()
    }
}
```

録音メソッド追加:
```kotlin
private var sampleRecorder: AudioRecorder? = null
private var recordingCommandId: String? = null
private var recordingSampleIndex: Int = 0

private fun recordCommandSample(commandId: String, sampleIndex: Int) {
    if (sampleIndex >= 3) return // max 3 samples

    if (sampleRecorder?.isRecording == true) {
        // Stop recording
        val wavFile = sampleRecorder?.stop() ?: return
        val targetFile = commandRepo?.getSampleFile(commandId, sampleIndex)
        if (targetFile != null) {
            wavFile.copyTo(targetFile, overwrite = true)
            wavFile.delete()
            commandRepo?.updateSampleCount(commandId, sampleIndex + 1)
            commandLearning?.refreshCommandList()
        }
        sampleRecorder = null
        recordingCommandId = null
        return
    }

    // Start recording (auto-stop after 2 seconds)
    sampleRecorder = AudioRecorder(cacheDir)
    recordingCommandId = commandId
    recordingSampleIndex = sampleIndex
    val started = sampleRecorder?.start() ?: false
    if (started) {
        serviceScope.launch {
            delay(2000)
            if (sampleRecorder?.isRecording == true) {
                recordCommandSample(commandId, sampleIndex)
            }
        }
    }
}
```

`showLearningModeContent()`を更新:
```kotlin
private fun showLearningModeContent() {
    isFlickMode = false
    voiceModeArea?.visibility = View.GONE
    flickKeyboard?.visibility = View.GONE
    commandLearning?.visibility = View.VISIBLE
    commandLearning?.refreshCommandList()
}
```

`showVoiceModeContent()`と`showFlickKeyboardContent()`に`commandLearning?.visibility = View.GONE`を追加。

**Step 3: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`

**Step 4: APKビルド + 同期**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 5: コミット**

```bash
git add app/src/main/res/layout/ime_voice_input.xml app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: integrate CommandLearningView into IME with sample recording"
```

---

### Task 8: 全体ビルド確認 & 回帰テスト

**Step 1: 全テスト実行**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: ALL PASS

**Step 2: APKビルド + 同期**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`
