# Android音声入力アプリ 実装計画

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Android 11向けのAccessibilityサービス型音声入力アプリを構築する。フローティングボタンのPush-to-Talkで録音し、Whisper APIで文字起こし、GPT APIで文脈変換してアクティブなテキストフィールドに入力する。

**Architecture:** シンプル直結型。AccessibilityServiceが全体を統括し、フローティングボタン→録音→Whisper→GPT→テキスト入力を直列処理する。

**Tech Stack:** Kotlin, Android SDK (minSdk 30 / Android 11), OkHttp (HTTP client), Gson (JSON), JUnit5 + MockK (テスト)

**設計ドキュメント:** `docs/plans/2026-02-14-voice-input-android-design.md`

---

### Task 1: プロジェクト骨格の作成

**Files:**
- Create: `app/build.gradle.kts`
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/example/voiceinput/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/layout/activity_main.xml`

**Step 1: ルートの build.gradle.kts を作成**

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
```

**Step 2: settings.gradle.kts を作成**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VoiceInput"
include(":app")
```

**Step 3: gradle.properties を作成**

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2048m
```

**Step 4: app/build.gradle.kts を作成**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.voiceinput"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.voiceinput"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

**Step 5: AndroidManifest.xml を作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.MaterialComponents.DayNight.DarkActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

**Step 6: 最小限の MainActivity.kt を作成**

```kotlin
package com.example.voiceinput

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

**Step 7: strings.xml と activity_main.xml を作成**

```xml
<!-- res/values/strings.xml -->
<resources>
    <string name="app_name">Voice Input</string>
</resources>
```

```xml
<!-- res/layout/activity_main.xml -->
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="24sp" />

</LinearLayout>
```

**Step 8: Gradle Wrapper をセットアップしてビルド確認**

Run: `gradle wrapper --gradle-version 8.11.1` (Android Studio付属のgradleか、sdkmanagerで取得)
Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 9: コミット**

```bash
git add app/ build.gradle.kts settings.gradle.kts gradle.properties
git commit -m "chore: scaffold Android project with dependencies"
```

---

### Task 2: PreferencesManager（APIキー保存）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/PreferencesManager.kt`
- Create: `app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: PreferencesManager

    @Before
    fun setUp() {
        sharedPreferences = mockk()
        editor = mockk(relaxed = true)
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        manager = PreferencesManager(sharedPreferences)
    }

    @Test
    fun `saveApiKey stores key in preferences`() {
        manager.saveApiKey("sk-test-key-123")
        verify { editor.putString("openai_api_key", "sk-test-key-123") }
        verify { editor.apply() }
    }

    @Test
    fun `getApiKey returns stored key`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns "sk-test-key-123"
        assertEquals("sk-test-key-123", manager.getApiKey())
    }

    @Test
    fun `getApiKey returns null when no key stored`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns null
        assertNull(manager.getApiKey())
    }

    @Test
    fun `hasApiKey returns true when key exists`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns "sk-test-key-123"
        assertTrue(manager.hasApiKey())
    }

    @Test
    fun `hasApiKey returns false when key is null`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns null
        assertFalse(manager.hasApiKey())
    }
}
```

**Step 2: テスト実行、失敗を確認**

Run: `./gradlew test --tests "com.example.voiceinput.PreferencesManagerTest" --info`
Expected: FAIL (PreferencesManager class not found)

**Step 3: 最小限の実装**

```kotlin
package com.example.voiceinput

import android.content.SharedPreferences

class PreferencesManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }
}
```

**Step 4: テスト実行、成功を確認**

Run: `./gradlew test --tests "com.example.voiceinput.PreferencesManagerTest" --info`
Expected: ALL PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/PreferencesManager.kt \
       app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt
git commit -m "feat: add PreferencesManager for API key storage"
```

---

### Task 3: WhisperClient（音声→テキスト）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/WhisperClient.kt`
- Create: `app/src/test/java/com/example/voiceinput/WhisperClientTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WhisperClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WhisperClient(
            apiKey = "sk-test",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transcribe sends audio file and returns text`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"text": "こんにちは世界"}""")
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100)) // dummy audio data
            deleteOnExit()
        }

        val result = client.transcribe(audioFile)

        assertEquals("こんにちは世界", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("audio/transcriptions"))
        assertTrue(request.getHeader("Authorization")!!.contains("sk-test"))
    }

    @Test
    fun `transcribe returns null on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        val result = client.transcribe(audioFile)
        assertNull(result)
    }
}
```

**Step 2: テスト実行、失敗を確認**

Run: `./gradlew test --tests "com.example.voiceinput.WhisperClientTest" --info`
Expected: FAIL (WhisperClient class not found)

**Step 3: 最小限の実装**

```kotlin
package com.example.voiceinput

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribe(audioFile: File): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ja")
            .build()

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
}
```

**Step 4: テスト実行、成功を確認**

Run: `./gradlew test --tests "com.example.voiceinput.WhisperClientTest" --info`
Expected: ALL PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/WhisperClient.kt \
       app/src/test/java/com/example/voiceinput/WhisperClientTest.kt
git commit -m "feat: add WhisperClient for speech-to-text via OpenAI API"
```

---

### Task 4: GptConverter（文脈変換）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/GptConverter.kt`
- Create: `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`

**Step 1: テストを書く**

```kotlin
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
        return """
        {
            "choices": [{
                "message": {
                    "content": "$content"
                }
            }]
        }
        """.trimIndent()
    }

    @Test
    fun `convert sends text and returns converted result`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("git status"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val result = converter.convert("ギットステータス")

        assertEquals("git status", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("chat/completions"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("ギットステータス"))
        assertTrue(body.contains("gpt-4o-mini"))
    }

    @Test
    fun `convert returns original text on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = converter.convert("テスト入力")
        assertEquals("テスト入力", result)
    }

    @Test
    fun `convert includes system prompt with conversion rules`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("ls -la"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        converter.convert("ファイル一覧を表示して")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("音声入力アシスタント"))
        assertTrue(body.contains("コマンド"))
    }
}
```

**Step 2: テスト実行、失敗を確認**

Run: `./gradlew test --tests "com.example.voiceinput.GptConverterTest" --info`
Expected: FAIL (GptConverter class not found)

**Step 3: 最小限の実装**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
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
            - 発話がコマンドの意図なら、実行可能なコマンド文字列のみを返す
            - 発話が日本語の文章なら、自然な日本語としてそのまま返す
            - 余計な説明は一切付けず、変換結果のみを返す
        """.trimIndent()
    }

    fun convert(rawText: String): String {
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
            if (!response.isSuccessful) return rawText
            val responseBody = response.body?.string() ?: return rawText
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
        } catch (e: Exception) {
            rawText
        }
    }
}
```

**Step 4: テスト実行、成功を確認**

Run: `./gradlew test --tests "com.example.voiceinput.GptConverterTest" --info`
Expected: ALL PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/GptConverter.kt \
       app/src/test/java/com/example/voiceinput/GptConverterTest.kt
git commit -m "feat: add GptConverter for context-aware text conversion"
```

---

### Task 5: AudioRecorder（録音）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/AudioRecorder.kt`
- Create: `app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt`

**Step 1: テストを書く**

AudioRecorderはAndroidのMediaRecorderに依存するため、インターフェースとロジックのテストを行う。

```kotlin
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
    fun `getOutputFile returns file in specified directory with m4a extension`() {
        val recorder = AudioRecorder(outputDir)
        val file = recorder.getOutputFile()
        assertEquals(outputDir, file.parentFile)
        assertTrue(file.name.endsWith(".m4a"))
    }

    @Test
    fun `isRecording returns false initially`() {
        val recorder = AudioRecorder(outputDir)
        assertFalse(recorder.isRecording)
    }
}
```

**Step 2: テスト実行、失敗を確認**

Run: `./gradlew test --tests "com.example.voiceinput.AudioRecorderTest" --info`
Expected: FAIL (AudioRecorder class not found)

**Step 3: 最小限の実装**

```kotlin
package com.example.voiceinput

import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val outputDir: File) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    var isRecording: Boolean = false
        private set

    fun getOutputFile(): File {
        val fileName = "voice_${System.currentTimeMillis()}.m4a"
        return File(outputDir, fileName)
    }

    fun start() {
        if (isRecording) return
        val file = getOutputFile()
        currentFile = file

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        isRecording = true
    }

    fun stop(): File? {
        if (!isRecording) return null
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        return currentFile
    }
}
```

**Step 4: テスト実行、成功を確認**

Run: `./gradlew test --tests "com.example.voiceinput.AudioRecorderTest" --info`
Expected: ALL PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/AudioRecorder.kt \
       app/src/test/java/com/example/voiceinput/AudioRecorderTest.kt
git commit -m "feat: add AudioRecorder for microphone recording"
```

---

### Task 6: FloatingButtonManager（フローティングボタン）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/FloatingButtonManager.kt`
- Create: `app/src/main/res/drawable/ic_mic.xml`

**Step 1: マイクアイコンリソースを作成**

```xml
<!-- res/drawable/ic_mic.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z" />
</vector>
```

**Step 2: FloatingButtonManager を実装**

```kotlin
package com.example.voiceinput

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class FloatingButtonManager(
    private val context: Context,
    private val onRecordStart: () -> Unit,
    private val onRecordStop: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingButton: View? = null

    fun show() {
        if (floatingButton != null) return

        val button = FloatingActionButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            size = FloatingActionButton.SIZE_NORMAL
        }

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    button.alpha = 0.7f
                    onRecordStart()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.alpha = 1.0f
                    onRecordStop()
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
        }

        windowManager.addView(button, params)
        floatingButton = button
    }

    fun hide() {
        floatingButton?.let {
            windowManager.removeView(it)
            floatingButton = null
        }
    }
}
```

**Note:** FloatingButtonManagerはAndroidのWindowManagerに強く依存するため、ユニットテストは書かず、実機で動作確認する。Push-to-Talkの反応はTask 7で統合テストする。

**Step 3: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/FloatingButtonManager.kt \
       app/src/main/res/drawable/ic_mic.xml
git commit -m "feat: add FloatingButtonManager with push-to-talk support"
```

---

### Task 7: VoiceInputService（AccessibilityService統合）

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/VoiceInputService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (AccessibilityService登録を追加)

**Step 1: Accessibilityサービス設定XMLを作成**

```xml
<!-- res/xml/accessibility_service_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewFocused|typeViewTextChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description" />
```

**Step 2: strings.xml にサービス説明を追加**

`app/src/main/res/values/strings.xml` に追加:
```xml
<string name="accessibility_service_description">音声入力でテキストフィールドに文字を入力します</string>
```

**Step 3: VoiceInputService を実装**

```kotlin
package com.example.voiceinput

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

class VoiceInputService : AccessibilityService() {

    private var floatingButtonManager: FloatingButtonManager? = null
    private var audioRecorder: AudioRecorder? = null
    private var whisperClient: WhisperClient? = null
    private var gptConverter: GptConverter? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()

        val prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )

        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_LONG).show()
            return
        }

        audioRecorder = AudioRecorder(cacheDir)
        whisperClient = WhisperClient(apiKey)
        gptConverter = GptConverter(apiKey)

        floatingButtonManager = FloatingButtonManager(
            context = this,
            onRecordStart = { startRecording() },
            onRecordStop = { stopRecordingAndProcess() }
        )
        floatingButtonManager?.show()
    }

    private fun startRecording() {
        audioRecorder?.start()
    }

    private fun stopRecordingAndProcess() {
        val audioFile = audioRecorder?.stop() ?: return

        serviceScope.launch {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient?.transcribe(audioFile)
            }
            if (rawText == null) {
                Toast.makeText(this@VoiceInputService, "音声認識に失敗しました", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val convertedText = withContext(Dispatchers.IO) {
                gptConverter?.convert(rawText) ?: rawText
            }

            inputTextToFocusedField(convertedText)
            audioFile.delete()
        }
    }

    private fun inputTextToFocusedField(text: String) {
        val focusedNode = findFocusedEditText(rootInActiveWindow) ?: return
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = findFocusedEditText(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // イベント監視（現時点では不要）
    }

    override fun onInterrupt() {
        // 中断処理
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingButtonManager?.hide()
        serviceScope.cancel()
    }
}
```

**Step 4: AndroidManifest.xml にサービスを登録**

`<application>` タグ内に追加:
```xml
<service
    android:name=".VoiceInputService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**Step 5: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 6: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputService.kt \
       app/src/main/res/xml/accessibility_service_config.xml \
       app/src/main/res/values/strings.xml \
       app/src/main/AndroidManifest.xml
git commit -m "feat: add VoiceInputService with accessibility integration"
```

---

### Task 8: MainActivity（設定画面）

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: レイアウトXMLを更新**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="24sp"
        android:layout_marginBottom="32dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/api_key_hint">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/apiKeyInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword" />

    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/saveButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/save_api_key"
        android:layout_marginTop="16dp" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/accessibilityButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/open_accessibility_settings"
        android:layout_marginTop="8dp"
        style="@style/Widget.MaterialComponents.Button.OutlinedButton" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:textSize="14sp" />

</LinearLayout>
```

**Step 2: strings.xml に追加**

```xml
<string name="api_key_hint">OpenAI API Key</string>
<string name="save_api_key">APIキーを保存</string>
<string name="open_accessibility_settings">アクセシビリティ設定を開く</string>
<string name="api_key_saved">APIキーを保存しました</string>
<string name="api_key_empty">APIキーを入力してください</string>
<string name="service_enabled">サービス: 有効</string>
<string name="service_disabled">サービス: 無効（アクセシビリティ設定で有効にしてください）</string>
```

**Step 3: MainActivity を更新**

```kotlin
package com.example.voiceinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val accessibilityButton = findViewById<Button>(R.id.accessibilityButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        // 既存のAPIキーがあれば表示
        prefsManager.getApiKey()?.let {
            apiKeyInput.setText(it)
        }

        saveButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.api_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefsManager.saveApiKey(key)
            Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show()
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val enabled = isAccessibilityServiceEnabled()
        statusText.setText(
            if (enabled) R.string.service_enabled else R.string.service_disabled
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${VoiceInputService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }
}
```

**Step 4: ビルド確認**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/MainActivity.kt \
       app/src/main/res/layout/activity_main.xml \
       app/src/main/res/values/strings.xml
git commit -m "feat: add MainActivity with API key settings and accessibility toggle"
```

---

### Task 9: 全テスト実行 + 実機テスト準備

**Step 1: 全テスト実行**

Run: `./gradlew test --info`
Expected: ALL PASS

**Step 2: APKビルド**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

**Step 3: 実機テスト手順を確認**

1. APKをAndroid 11端末にインストール
2. アプリを開いてOpenAI APIキーを入力・保存
3. 「アクセシビリティ設定を開く」→ Voice Input サービスを有効化
4. Termiusを開く
5. フローティングボタンを長押し → 話す → 離す
6. テキストフィールドに変換結果が入力されることを確認

**Step 4: コミット（最終）**

```bash
git add -A
git commit -m "chore: final build verification"
```
