# SSH tmux コンテキスト取得機能 実装計画

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** リモートサーバーにSSH接続して `tmux capture-pane` の出力を取得し、WhisperのpromptとGPTのコンテキストに渡して音声認識精度を向上させる。

**Architecture:** JSch（SSH library）で接続管理する `SshContextProvider` を新規作成。PreferencesManagerにSSH設定を追加。GptConverterにターミナルコンテキスト渡し機能を追加。VoiceInputIMEの音声入力フローにコンテキスト取得を統合。

**Tech Stack:** JSch (com.github.mwiede:jsch:0.2.21), EncryptedSharedPreferences, Kotlin Coroutines

---

### Task 1: JSch依存追加とビルド確認

**Files:**
- Modify: `app/build.gradle.kts:28-34`

**Step 1: build.gradle.ktsにJSch依存を追加**

`dependencies` ブロックに追加:
```kotlin
implementation("com.github.mwiede:jsch:0.2.21")
```

**Step 2: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: コミット**

```bash
git add app/build.gradle.kts
git commit -m "Add JSch SSH library dependency"
```

---

### Task 2: SshContextProvider — SSH接続とコマンド実行（テスト）

**Files:**
- Create: `app/src/test/java/com/example/voiceinput/SshContextProviderTest.kt`
- Create: `app/src/main/java/com/example/voiceinput/SshContextProvider.kt`

**Step 1: テスト作成**

SshContextProviderはJSchのSessionを受け取るインターフェース設計で、純粋ロジック部分をテスト。
- `parseOutput`: コマンド出力から空行トリム
- `extractWhisperContext`: 末尾20行を切り出し
- `extractGptContext`: 全テキスト（80行上限、既にcapture-paneで制限）

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class SshContextProviderTest {

    @Test
    fun `parseOutput trims trailing blank lines`() {
        val raw = "line1\nline2\n\n\n"
        val result = SshContextProvider.parseOutput(raw)
        assertEquals("line1\nline2", result)
    }

    @Test
    fun `parseOutput returns null for blank output`() {
        assertNull(SshContextProvider.parseOutput(""))
        assertNull(SshContextProvider.parseOutput("\n\n"))
    }

    @Test
    fun `parseOutput preserves internal blank lines`() {
        val raw = "line1\n\nline3\n"
        val result = SshContextProvider.parseOutput(raw)
        assertEquals("line1\n\nline3", result)
    }

    @Test
    fun `extractWhisperContext returns last 20 lines`() {
        val lines = (1..50).map { "line$it" }.joinToString("\n")
        val result = SshContextProvider.extractWhisperContext(lines)
        val resultLines = result!!.split("\n")
        assertEquals(20, resultLines.size)
        assertEquals("line31", resultLines.first())
        assertEquals("line50", resultLines.last())
    }

    @Test
    fun `extractWhisperContext returns all if under 20 lines`() {
        val text = "line1\nline2\nline3"
        val result = SshContextProvider.extractWhisperContext(text)
        assertEquals(text, result)
    }

    @Test
    fun `extractWhisperContext returns null for null input`() {
        assertNull(SshContextProvider.extractWhisperContext(null))
    }

    @Test
    fun `extractGptContext returns full text`() {
        val text = "line1\nline2"
        assertEquals(text, SshContextProvider.extractGptContext(text))
    }

    @Test
    fun `extractGptContext returns null for null input`() {
        assertNull(SshContextProvider.extractGptContext(null))
    }
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.SshContextProviderTest"`
Expected: FAIL — Unresolved reference 'SshContextProvider'

**Step 3: 最小実装**

```kotlin
package com.example.voiceinput

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

class SshContextProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val privateKey: String
) {
    companion object {
        private const val COMMAND = "tmux capture-pane -p -S -80"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val WHISPER_CONTEXT_LINES = 20

        fun parseOutput(raw: String): String? {
            val trimmed = raw.trimEnd('\n', ' ')
            return if (trimmed.isEmpty()) null else trimmed
        }

        fun extractWhisperContext(text: String?): String? {
            if (text == null) return null
            val lines = text.split("\n")
            return if (lines.size <= WHISPER_CONTEXT_LINES) {
                text
            } else {
                lines.takeLast(WHISPER_CONTEXT_LINES).joinToString("\n")
            }
        }

        fun extractGptContext(text: String?): String? {
            return text
        }
    }

    private var cachedSession: Session? = null

    fun fetchContext(): String? {
        return try {
            val session = getOrCreateSession()
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(COMMAND)
            channel.inputStream.use { input ->
                channel.connect(CONNECT_TIMEOUT_MS)
                val output = input.bufferedReader().readText()
                channel.disconnect()
                parseOutput(output)
            }
        } catch (e: Exception) {
            cachedSession?.disconnect()
            cachedSession = null
            null
        }
    }

    private fun getOrCreateSession(): Session {
        cachedSession?.let {
            if (it.isConnected) return it
        }
        val jsch = JSch()
        jsch.addIdentity("key", privateKey.toByteArray(), null, null)
        val session = jsch.getSession(username, host, port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(CONNECT_TIMEOUT_MS)
        cachedSession = session
        return session
    }

    fun disconnect() {
        cachedSession?.disconnect()
        cachedSession = null
    }
}
```

**Step 4: テスト実行 → 成功確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.SshContextProviderTest"`
Expected: PASS

**Step 5: コミット**

```bash
git add app/src/test/java/com/example/voiceinput/SshContextProviderTest.kt \
        app/src/main/java/com/example/voiceinput/SshContextProvider.kt
git commit -m "Add SshContextProvider with SSH connection and tmux context extraction"
```

---

### Task 3: PreferencesManager — SSH設定の保存/取得

**Files:**
- Modify: `app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt`
- Modify: `app/src/main/java/com/example/voiceinput/PreferencesManager.kt`

**Step 1: テスト作成**

PreferencesManagerTestが存在するか確認し、SSH設定のテストを追加:

```kotlin
@Test
fun `saveSshConfig stores all SSH fields`() {
    prefsManager.saveSshHost("192.168.1.100")
    prefsManager.saveSshPort(22)
    prefsManager.saveSshUsername("user")
    prefsManager.saveSshPrivateKey("-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----")
    prefsManager.saveSshContextEnabled(true)

    assertEquals("192.168.1.100", prefsManager.getSshHost())
    assertEquals(22, prefsManager.getSshPort())
    assertEquals("user", prefsManager.getSshUsername())
    assertEquals("-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----", prefsManager.getSshPrivateKey())
    assertTrue(prefsManager.isSshContextEnabled())
}

@Test
fun `SSH defaults are correct`() {
    assertNull(prefsManager.getSshHost())
    assertEquals(22, prefsManager.getSshPort())
    assertNull(prefsManager.getSshUsername())
    assertNull(prefsManager.getSshPrivateKey())
    assertFalse(prefsManager.isSshContextEnabled())
}

@Test
fun `isSshConfigured returns true when all fields set`() {
    prefsManager.saveSshHost("host")
    prefsManager.saveSshUsername("user")
    prefsManager.saveSshPrivateKey("key")
    prefsManager.saveSshContextEnabled(true)
    assertTrue(prefsManager.isSshConfigured())
}

@Test
fun `isSshConfigured returns false when host missing`() {
    prefsManager.saveSshUsername("user")
    prefsManager.saveSshPrivateKey("key")
    prefsManager.saveSshContextEnabled(true)
    assertFalse(prefsManager.isSshConfigured())
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.PreferencesManagerTest"`
Expected: FAIL — Unresolved reference

**Step 3: PreferencesManagerにSSH設定メソッドを追加**

```kotlin
companion object {
    // ... existing keys ...
    private const val KEY_SSH_HOST = "ssh_host"
    private const val KEY_SSH_PORT = "ssh_port"
    private const val KEY_SSH_USERNAME = "ssh_username"
    private const val KEY_SSH_PRIVATE_KEY = "ssh_private_key"
    private const val KEY_SSH_CONTEXT_ENABLED = "ssh_context_enabled"
    const val DEFAULT_SSH_PORT = 22
}

fun saveSshHost(host: String) { prefs.edit().putString(KEY_SSH_HOST, host).apply() }
fun getSshHost(): String? = prefs.getString(KEY_SSH_HOST, null)

fun saveSshPort(port: Int) { prefs.edit().putInt(KEY_SSH_PORT, port).apply() }
fun getSshPort(): Int = prefs.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)

fun saveSshUsername(username: String) { prefs.edit().putString(KEY_SSH_USERNAME, username).apply() }
fun getSshUsername(): String? = prefs.getString(KEY_SSH_USERNAME, null)

fun saveSshPrivateKey(key: String) { prefs.edit().putString(KEY_SSH_PRIVATE_KEY, key).apply() }
fun getSshPrivateKey(): String? = prefs.getString(KEY_SSH_PRIVATE_KEY, null)

fun saveSshContextEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_SSH_CONTEXT_ENABLED, enabled).apply() }
fun isSshContextEnabled(): Boolean = prefs.getBoolean(KEY_SSH_CONTEXT_ENABLED, false)

fun isSshConfigured(): Boolean {
    return isSshContextEnabled()
        && !getSshHost().isNullOrBlank()
        && !getSshUsername().isNullOrBlank()
        && !getSshPrivateKey().isNullOrBlank()
}
```

**Step 4: テスト実行 → 成功確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.PreferencesManagerTest"`
Expected: PASS

**Step 5: コミット**

```bash
git add app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt \
        app/src/main/java/com/example/voiceinput/PreferencesManager.kt
git commit -m "Add SSH configuration to PreferencesManager"
```

---

### Task 4: GptConverter — ターミナルコンテキスト付加

**Files:**
- Modify: `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`（存在しなければ作成）
- Modify: `app/src/main/java/com/example/voiceinput/GptConverter.kt`

**Step 1: テスト作成**

GptConverterのcontextFormatting部分をテスト。API呼び出しはモックが必要なので、メッセージ組み立てロジックをstaticメソッドとして切り出してテスト:

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class GptConverterContextTest {

    @Test
    fun `buildUserMessage without context returns raw text only`() {
        val result = GptConverter.buildUserMessage("こんにちは", null)
        assertEquals("こんにちは", result)
    }

    @Test
    fun `buildUserMessage with context includes terminal section`() {
        val result = GptConverter.buildUserMessage("こんにちは", "$ ls\nfile1 file2")
        assertTrue(result.contains("[端末コンテキスト]"))
        assertTrue(result.contains("$ ls\nfile1 file2"))
        assertTrue(result.contains("[音声入力テキスト]"))
        assertTrue(result.contains("こんにちは"))
    }

    @Test
    fun `buildUserMessage with blank context returns raw text only`() {
        val result = GptConverter.buildUserMessage("こんにちは", "  ")
        assertEquals("こんにちは", result)
    }
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterContextTest"`
Expected: FAIL — Unresolved reference 'buildUserMessage'

**Step 3: GptConverterに `buildUserMessage` を追加し、`callGpt` で使用**

`companion object` 内に追加:
```kotlin
fun buildUserMessage(rawText: String, terminalContext: String?): String {
    if (terminalContext.isNullOrBlank()) return rawText
    return "[端末コンテキスト]\n$terminalContext\n\n[音声入力テキスト]\n$rawText"
}
```

`convertWithHistory` のシグネチャ変更:
```kotlin
fun convertWithHistory(rawText: String, corrections: List<CorrectionEntry>, terminalContext: String? = null): String {
    // ... existing system prompt build ...
    val userMessage = buildUserMessage(rawText, terminalContext)
    return callGpt(prompt, userMessage) ?: rawText
}
```

**Step 4: テスト実行 → 成功確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.GptConverterContextTest"`
Expected: PASS

**Step 5: 全テスト確認**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS

**Step 6: コミット**

```bash
git add app/src/test/java/com/example/voiceinput/GptConverterContextTest.kt \
        app/src/main/java/com/example/voiceinput/GptConverter.kt
git commit -m "Add terminal context support to GptConverter"
```

---

### Task 5: VoiceInputProcessor — context引数の活用

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt`

**Step 1: processAudioFileのシグネチャにterminalContext追加**

`processAudioFile` は既に `context: String?` パラメータがあり、Whisperのpromptに渡している。
`terminalContext: String?` を追加してGPTに渡す:

```kotlin
suspend fun processAudioFile(
    audioFile: File,
    context: String? = null,
    terminalContext: String? = null,
    corrections: List<CorrectionEntry>? = null
): List<ConversionChunk>? {
    try {
        val rawText = withContext(Dispatchers.IO) {
            whisperClient.transcribe(audioFile, prompt = context)
        } ?: return null

        val convertedText = withContext(Dispatchers.IO) {
            if (corrections != null) {
                gptConverter.convertWithHistory(rawText, corrections, terminalContext)
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

**Step 2: ビルド確認**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS

**Step 3: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt
git commit -m "Add terminalContext parameter to VoiceInputProcessor"
```

---

### Task 6: VoiceInputIME — コンテキスト取得の統合

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: SshContextProviderフィールドとセットアップ**

VoiceInputIMEに追加:
```kotlin
private var sshContextProvider: SshContextProvider? = null
```

`refreshProcessor()` 内 or `onCreateInputView()` で、SSH設定があればproviderを初期化:
```kotlin
private fun refreshSshProvider() {
    val prefsManager = PreferencesManager(
        getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
    )
    if (prefsManager.isSshConfigured()) {
        sshContextProvider = SshContextProvider(
            host = prefsManager.getSshHost()!!,
            port = prefsManager.getSshPort(),
            username = prefsManager.getSshUsername()!!,
            privateKey = prefsManager.getSshPrivateKey()!!
        )
    } else {
        sshContextProvider?.disconnect()
        sshContextProvider = null
    }
}
```

**Step 2: onMicReleasedForNewInputにコンテキスト取得を統合**

`onMicReleasedForNewInput` のコマンドマッチ後のWhisper→GPTフローを変更:

```kotlin
// No command match — Whisper→GPT
statusText?.text = "変換中..."

// Fetch terminal context via SSH (if configured)
val tmuxContext = withContext(Dispatchers.IO) {
    sshContextProvider?.fetchContext()
}
val whisperPrompt = SshContextProvider.extractWhisperContext(tmuxContext)
val gptContext = SshContextProvider.extractGptContext(tmuxContext)

val corrections = correctionRepo?.getTopCorrections(20)
val chunks = proc.processAudioFile(
    audioFile,
    context = whisperPrompt,
    terminalContext = gptContext,
    corrections = corrections
)
```

**Step 3: onDestroyでSSH接続切断**

```kotlin
override fun onDestroy() {
    super.onDestroy()
    sshContextProvider?.disconnect()
    serviceScope.cancel()
}
```

**Step 4: refreshProcessorからrefreshSshProviderも呼ぶ**

```kotlin
private fun refreshProcessor() {
    // ... existing code ...
    refreshSshProvider()
}
```

**Step 5: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 6: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "Integrate SSH context fetching into voice input flow"
```

---

### Task 7: 設定画面にSSH設定UIを追加

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/voiceinput/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: activity_main.xmlにSSH設定セクションを追加**

statusText の後に追加:
```xml
<!-- SSH Context Settings -->
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/ssh_settings_title"
    android:textSize="18sp"
    android:layout_marginTop="32dp"
    android:textStyle="bold" />

<com.google.android.material.switchmaterial.SwitchMaterial
    android:id="@+id/sshContextSwitch"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/ssh_context_enable"
    android:layout_marginTop="8dp" />

<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="@string/ssh_host_hint"
    android:layout_marginTop="8dp">
    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/sshHostInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="text" />
</com.google.android.material.textfield.TextInputLayout>

<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_marginTop="8dp">

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:hint="@string/ssh_username_hint">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/sshUsernameInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="text" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="100dp"
        android:layout_height="wrap_content"
        android:hint="@string/ssh_port_hint"
        android:layout_marginStart="8dp">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/sshPortInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="number"
            android:text="22" />
    </com.google.android.material.textfield.TextInputLayout>
</LinearLayout>

<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="@string/ssh_private_key_hint"
    android:layout_marginTop="8dp">
    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/sshPrivateKeyInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="textMultiLine"
        android:minLines="3"
        android:gravity="top" />
</com.google.android.material.textfield.TextInputLayout>

<com.google.android.material.button.MaterialButton
    android:id="@+id/sshSaveButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/ssh_save"
    android:layout_marginTop="8dp" />

<com.google.android.material.button.MaterialButton
    android:id="@+id/sshTestButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/ssh_test_connection"
    android:layout_marginTop="4dp"
    style="@style/Widget.MaterialComponents.Button.OutlinedButton" />
```

**Step 2: strings.xmlにSSH関連文字列を追加**

```xml
<string name="ssh_settings_title">SSH コンテキスト設定</string>
<string name="ssh_context_enable">端末コンテキストを有効化</string>
<string name="ssh_host_hint">ホスト名 / IP</string>
<string name="ssh_username_hint">ユーザー名</string>
<string name="ssh_port_hint">ポート</string>
<string name="ssh_private_key_hint">秘密鍵（PEM形式でペースト）</string>
<string name="ssh_save">SSH設定を保存</string>
<string name="ssh_test_connection">接続テスト</string>
<string name="ssh_test_success">接続成功！</string>
<string name="ssh_test_fail">接続失敗</string>
<string name="ssh_saved">SSH設定を保存しました</string>
```

**Step 3: MainActivityにSSH設定の保存/読み込みロジックを追加**

```kotlin
private fun setupSshSettings() {
    val sshSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sshContextSwitch)
    val hostInput = findViewById<EditText>(R.id.sshHostInput)
    val portInput = findViewById<EditText>(R.id.sshPortInput)
    val usernameInput = findViewById<EditText>(R.id.sshUsernameInput)
    val privateKeyInput = findViewById<EditText>(R.id.sshPrivateKeyInput)
    val saveButton = findViewById<Button>(R.id.sshSaveButton)
    val testButton = findViewById<Button>(R.id.sshTestButton)

    // Load existing values
    sshSwitch.isChecked = prefsManager.isSshContextEnabled()
    prefsManager.getSshHost()?.let { hostInput.setText(it) }
    portInput.setText(prefsManager.getSshPort().toString())
    prefsManager.getSshUsername()?.let { usernameInput.setText(it) }
    prefsManager.getSshPrivateKey()?.let { privateKeyInput.setText(it) }

    saveButton.setOnClickListener {
        prefsManager.saveSshContextEnabled(sshSwitch.isChecked)
        prefsManager.saveSshHost(hostInput.text.toString().trim())
        prefsManager.saveSshPort(portInput.text.toString().toIntOrNull() ?: 22)
        prefsManager.saveSshUsername(usernameInput.text.toString().trim())
        prefsManager.saveSshPrivateKey(privateKeyInput.text.toString())
        Toast.makeText(this, R.string.ssh_saved, Toast.LENGTH_SHORT).show()
    }

    testButton.setOnClickListener {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 22
        val username = usernameInput.text.toString().trim()
        val key = privateKeyInput.text.toString()

        Thread {
            try {
                val provider = SshContextProvider(host, port, username, key)
                val result = provider.fetchContext()
                provider.disconnect()
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.ssh_test_success) + " (${result?.length ?: 0} chars)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.ssh_test_fail)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
```

`onCreate` の末尾で `setupSshSettings()` を呼ぶ。

**Step 4: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 5: コミット**

```bash
git add app/src/main/res/layout/activity_main.xml \
        app/src/main/java/com/example/voiceinput/MainActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "Add SSH settings UI to MainActivity"
```

---

### Task 8: デバッグ表示更新 — VOICEタブ長押しでSSHコンテキストも表示

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: showInputContextDebugを更新**

VOICEタブ長押し時にgetTextBeforeCursorに加えてSSHコンテキストも取得・表示:

```kotlin
private fun showInputContextDebug() {
    val ic = currentInputConnection
    val before = ic?.getTextBeforeCursor(500, 0)
    val icDebug = InputContextReader.formatContextDebug(before)

    serviceScope.launch {
        val sshText = withContext(Dispatchers.IO) {
            sshContextProvider?.fetchContext()
        }
        val sshDebug = if (sshText != null) "SSH[${sshText.length}]: OK" else "SSH: N/A"
        statusText?.text = "$icDebug | $sshDebug"
    }
}
```

**Step 2: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "Update debug display to show SSH context status"
```

---

### Task 9: 全テスト + ビルド最終確認

**Step 1: 全テスト実行**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS

**Step 2: APKビルド**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: APK同期**

```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk
```

**Step 4: コミット（必要なら）**

全コミット済みなら最終確認のみ。
