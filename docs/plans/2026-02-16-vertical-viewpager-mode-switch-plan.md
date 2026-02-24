# 縦ViewPager2モード切替 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** IMEの音声入力モードとフリックキーボードモードの切替を縦ViewPager2スワイプに変更する

**Architecture:** 既存のvisibility切替をViewPager2(VERTICAL)に置き換える。RecyclerView.Adapterで2ページ（音声モード / フリックキーボード）を管理。candidateAreaはViewPager2の外に配置。フリックキーボードのキータッチとViewPager2スワイプの競合はrequestDisallowInterceptTouchEventで解決。

**Tech Stack:** ViewPager2 (androidx.viewpager2), RecyclerView.Adapter, Android IME InputMethodService

---

### Task 1: ViewPager2依存を追加

**Files:**
- Modify: `app/build.gradle.kts:28-39`

**Step 1: 依存を追加**

`build.gradle.kts` の dependencies に以下を追加:

```kotlin
implementation("androidx.viewpager2:viewpager2:1.1.0")
```

**Step 2: ビルド確認**

Run: `./gradlew app:dependencies --configuration releaseRuntimeClasspath | grep viewpager`
Expected: viewpager2が解決されていること

**Step 3: コミット**

```bash
git add app/build.gradle.kts
git commit -m "feat: add ViewPager2 dependency for vertical mode switching"
```

---

### Task 2: IMEModePagerAdapterのテストを書く

**Files:**
- Create: `app/src/test/java/com/example/voiceinput/IMEModePagerAdapterTest.kt`

**Step 1: テストを書く**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class IMEModePagerAdapterTest {

    @Test
    fun `adapter has exactly 2 pages`() {
        assertEquals(2, IMEModePagerAdapter.PAGE_COUNT)
    }

    @Test
    fun `page 0 is voice mode`() {
        assertEquals(0, IMEModePagerAdapter.PAGE_VOICE)
    }

    @Test
    fun `page 1 is flick keyboard mode`() {
        assertEquals(1, IMEModePagerAdapter.PAGE_FLICK)
    }
}
```

**Step 2: テスト実行 → 失敗確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.IMEModePagerAdapterTest" 2>&1 | tail -20`
Expected: FAIL (クラスが存在しない)

**Step 3: コミット**

```bash
git add app/src/test/java/com/example/voiceinput/IMEModePagerAdapterTest.kt
git commit -m "test: add failing tests for IMEModePagerAdapter constants"
```

---

### Task 3: IMEModePagerAdapterの定数を実装

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/IMEModePagerAdapter.kt`

**Step 1: 最小限の実装**

```kotlin
package com.example.voiceinput

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class IMEModePagerAdapter : RecyclerView.Adapter<IMEModePagerAdapter.PageViewHolder>() {

    companion object {
        const val PAGE_VOICE = 0
        const val PAGE_FLICK = 1
        const val PAGE_COUNT = 2
    }

    var onPageCreated: ((position: Int, view: View) -> Unit)? = null

    class PageViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = PAGE_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = when (viewType) {
            PAGE_VOICE -> LayoutInflater.from(parent.context)
                .inflate(R.layout.ime_page_voice, parent, false)
            PAGE_FLICK -> LayoutInflater.from(parent.context)
                .inflate(R.layout.ime_page_flick, parent, false)
            else -> throw IllegalArgumentException("Unknown page: $viewType")
        }
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        onPageCreated?.invoke(position, holder.view)
    }

    override fun getItemViewType(position: Int): Int = position
}
```

**Step 2: テスト実行 → 成功確認**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.IMEModePagerAdapterTest" 2>&1 | tail -20`
Expected: PASS（定数テストのみ。レイアウト依存のメソッドはAndroidフレームワーク依存なのでユニットテスト不可）

**Step 3: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/IMEModePagerAdapter.kt app/src/test/java/com/example/voiceinput/IMEModePagerAdapterTest.kt
git commit -m "feat: implement IMEModePagerAdapter with page constants"
```

---

### Task 4: 音声モードのページレイアウトを作成

**Files:**
- Create: `app/src/main/res/layout/ime_page_voice.xml`

**Step 1: レイアウトを作成**

音声モードのViewを独立したレイアウトとして抽出:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal"
    android:gravity="center"
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
        android:contentDescription="@string/ime_mic_description" />

</LinearLayout>
```

**Step 2: コミット**

```bash
git add app/src/main/res/layout/ime_page_voice.xml
git commit -m "feat: add voice mode page layout for ViewPager2"
```

---

### Task 5: フリックキーボードのページレイアウトを作成

**Files:**
- Create: `app/src/main/res/layout/ime_page_flick.xml`

**Step 1: レイアウトを作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.example.voiceinput.FlickKeyboardView
        android:id="@+id/flickKeyboard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="4dp" />

</FrameLayout>
```

**Step 2: コミット**

```bash
git add app/src/main/res/layout/ime_page_flick.xml
git commit -m "feat: add flick keyboard page layout for ViewPager2"
```

---

### Task 6: FlickKeyboardViewのタッチ競合解決

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt:104-121`
- Test: `app/src/test/java/com/example/voiceinput/FlickKeyboardViewTest.kt`

**Step 1: テストを追加**

FlickKeyboardViewTest.ktに追加:

```kotlin
@Test
fun `detectFlickDirection at boundary returns CENTER`() {
    val dir = FlickResolver.detectDirection(100f, 100f, 129f, 129f)
    assertEquals(FlickDirection.CENTER, dir)
}

@Test
fun `detectFlickDirection at boundary plus one returns directional`() {
    val dir = FlickResolver.detectDirection(100f, 100f, 131f, 100f)
    assertEquals(FlickDirection.RIGHT, dir)
}
```

**Step 2: テスト実行 → 成功確認**（既存ロジックのテスト）

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.FlickKeyboardViewTest" 2>&1 | tail -20`
Expected: PASS

**Step 3: FlickKeyboardViewにrequestDisallowInterceptTouchEvent追加**

`addFlickKey` メソッドの `setOnTouchListener` を修正:

```kotlin
setOnTouchListener { v, event ->
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            startX = event.rawX
            startY = event.rawY
            v.parent?.requestDisallowInterceptTouchEvent(true)
            true
        }
        MotionEvent.ACTION_UP -> {
            v.parent?.requestDisallowInterceptTouchEvent(false)
            val dir = FlickResolver.detectDirection(startX, startY, event.rawX, event.rawY)
            val char = FlickResolver.resolve(rowKey, dir)
            if (char != null) {
                listener?.onCharacterInput(char)
            }
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            v.parent?.requestDisallowInterceptTouchEvent(false)
            true
        }
        else -> false
    }
}
```

**Step 4: テスト実行 → 成功維持**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.FlickKeyboardViewTest" 2>&1 | tail -20`
Expected: PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt app/src/test/java/com/example/voiceinput/FlickKeyboardViewTest.kt
git commit -m "feat: add requestDisallowInterceptTouchEvent for ViewPager2 compatibility"
```

---

### Task 7: FlickKeyboardViewから🎤ボタンを削除

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt:91-96`
- Modify: `app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt:58-64` (FlickKeyboardListener)

**Step 1: FlickKeyboardListenerからonSwitchToVoiceを削除**

```kotlin
interface FlickKeyboardListener {
    fun onCharacterInput(char: String)
    fun onBackspace()
    fun onConvert()
    fun onConfirm()
}
```

**Step 2: buildKeyboardの3行目を修正**

🎤ボタンを「、」（句読点）ボタンに置き換え:

```kotlin
// Row 3: 、 ⌫ 変換 確定
addActionButton("、", 1) { listener?.onCharacterInput("、") }
addActionButton("⌫", 1) { listener?.onBackspace() }
addActionButton("変換", 2) { listener?.onConvert() }
addActionButton("確定", 1) { listener?.onConfirm() }
```

**Step 3: VoiceInputIME.ktのonSwitchToVoice実装を削除**

VoiceInputIME.ktの `flickKeyboard?.listener` 設定内の `onSwitchToVoice()` オーバーライドを削除。

**Step 4: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: ALL PASS

**Step 5: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/FlickKeyboardView.kt app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: replace mic button with punctuation key, remove onSwitchToVoice"
```

---

### Task 8: ime_voice_input.xmlをViewPager2レイアウトに変更

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml`

**Step 1: レイアウトを書き換え**

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

    <!-- ページインジケーター -->
    <LinearLayout
        android:id="@+id/pageIndicator"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center"
        android:paddingTop="4dp"
        android:paddingBottom="2dp">

        <View
            android:id="@+id/dot0"
            android:layout_width="8dp"
            android:layout_height="8dp"
            android:layout_margin="4dp"
            android:background="@drawable/indicator_dot_active" />

        <View
            android:id="@+id/dot1"
            android:layout_width="8dp"
            android:layout_height="8dp"
            android:layout_margin="4dp"
            android:background="@drawable/indicator_dot_inactive" />

    </LinearLayout>

    <!-- モード切替ViewPager2 -->
    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/modePager"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical" />

</LinearLayout>
```

**Step 2: コミット**

```bash
git add app/src/main/res/layout/ime_voice_input.xml
git commit -m "feat: replace voice/flick areas with ViewPager2 in IME layout"
```

---

### Task 9: インジケータードットのドローアブルを作成

**Files:**
- Create: `app/src/main/res/drawable/indicator_dot_active.xml`
- Create: `app/src/main/res/drawable/indicator_dot_inactive.xml`

**Step 1: アクティブドット**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FF6200EE" />
    <size android:width="8dp" android:height="8dp" />
</shape>
```

**Step 2: インアクティブドット**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FFBDBDBD" />
    <size android:width="8dp" android:height="8dp" />
</shape>
```

**Step 3: コミット**

```bash
git add app/src/main/res/drawable/indicator_dot_active.xml app/src/main/res/drawable/indicator_dot_inactive.xml
git commit -m "feat: add page indicator dot drawables"
```

---

### Task 10: VoiceInputIMEをViewPager2に接続

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

**Step 1: フィールドを変更**

旧フィールドを削除:
```kotlin
// 削除:
private var voiceModeArea: LinearLayout? = null
private var flickKeyboard: FlickKeyboardView? = null
private var keyboardToggleButton: ImageButton? = null
```

新フィールドを追加:
```kotlin
import androidx.viewpager2.widget.ViewPager2

private var modePager: ViewPager2? = null
private var flickKeyboard: FlickKeyboardView? = null
private var dot0: View? = null
private var dot1: View? = null
```

**Step 2: onCreateInputViewを書き換え**

`onCreateInputView()` で:
1. `modePager` を `view.findViewById(R.id.modePager)` で取得
2. `dot0`, `dot1` をfindViewById
3. `IMEModePagerAdapter` をセットアップ
4. `adapter.onPageCreated` コールバックで各ページのViewをセットアップ
   - PAGE_VOICE: statusText, micButton を取得し、既存のジェスチャー/タッチリスナーをセット
   - PAGE_FLICK: flickKeyboard を取得し、既存の listener をセット
5. `modePager?.offscreenPageLimit = 1`
6. `ViewPager2.OnPageChangeCallback` で dot0/dot1 のbackgroundを切替

```kotlin
modePager = view.findViewById(R.id.modePager)
dot0 = view.findViewById(R.id.dot0)
dot1 = view.findViewById(R.id.dot1)

val adapter = IMEModePagerAdapter()
adapter.onPageCreated = { position, pageView ->
    when (position) {
        IMEModePagerAdapter.PAGE_VOICE -> setupVoicePage(pageView)
        IMEModePagerAdapter.PAGE_FLICK -> setupFlickPage(pageView)
    }
}
modePager?.adapter = adapter
modePager?.offscreenPageLimit = 1

modePager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
        updateIndicator(position)
        if (position == IMEModePagerAdapter.PAGE_VOICE && composingBuffer.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composingBuffer.clear()
        }
    }
})
```

**Step 3: setupVoicePage / setupFlickPage メソッドを追加**

既存の `onCreateInputView()` 内のセットアップコードを各メソッドに移動:

```kotlin
private fun setupVoicePage(pageView: View) {
    statusText = pageView.findViewById(R.id.imeStatusText)
    micButton = pageView.findViewById(R.id.imeMicButton)

    // gestureDetector + micButton touch listener setup (既存コードそのまま)
}

private fun setupFlickPage(pageView: View) {
    flickKeyboard = pageView.findViewById(R.id.flickKeyboard)
    flickKeyboard?.listener = object : FlickKeyboardListener {
        // 既存のlistener実装そのまま（onSwitchToVoice削除済み）
    }
}
```

**Step 4: showFlickKeyboard / showVoiceMode を変更**

```kotlin
private fun showFlickKeyboard() {
    modePager?.setCurrentItem(IMEModePagerAdapter.PAGE_FLICK, true)
}

private fun showVoiceMode() {
    modePager?.setCurrentItem(IMEModePagerAdapter.PAGE_VOICE, true)
}
```

**Step 5: updateIndicator メソッドを追加**

```kotlin
private fun updateIndicator(position: Int) {
    dot0?.setBackgroundResource(
        if (position == 0) R.drawable.indicator_dot_active else R.drawable.indicator_dot_inactive
    )
    dot1?.setBackgroundResource(
        if (position == 1) R.drawable.indicator_dot_active else R.drawable.indicator_dot_inactive
    )
}
```

**Step 6: keyboardToggleButton関連のコードを全て削除**

**Step 7: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: ALL PASS

**Step 8: コミット**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt app/src/main/java/com/example/voiceinput/IMEModePagerAdapter.kt
git commit -m "feat: connect VoiceInputIME to ViewPager2 for vertical mode switching"
```

---

### Task 11: ビルド確認 & 回帰テスト

**Files:** None (テストのみ)

**Step 1: 全テスト実行**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: ALL PASS

**Step 2: APKビルド**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: APKを同期先にコピー**

Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

---

### Task 12: ViewPager2の高さ問題に対応

**注意:** ViewPager2はwrap_contentが正しく動作しないことがある。IMEとしてキーボードの高さが適切になるよう調整が必要。

**Files:**
- Modify: `app/src/main/res/layout/ime_voice_input.xml` (必要に応じて)
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt` (必要に応じて)

**Step 1: ビルド後にIMEの高さが適切か確認**

ViewPager2に固定高さ（例: `200dp`）を設定するか、動的に高さを測定して設定:

```xml
<androidx.viewpager2.widget.ViewPager2
    android:id="@+id/modePager"
    android:layout_width="match_parent"
    android:layout_height="200dp"
    android:orientation="vertical" />
```

あるいは、`onCreateInputView()` で動的に高さを設定:

```kotlin
modePager?.post {
    val params = modePager?.layoutParams
    params?.height = resources.getDimensionPixelSize(R.dimen.ime_pager_height)
    modePager?.layoutParams = params
}
```

`res/values/dimens.xml` に追加:
```xml
<dimen name="ime_pager_height">200dp</dimen>
```

**Step 2: テスト実行 → 全テストパス確認**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: ALL PASS

**Step 3: APKビルド & 同期**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 4: コミット**

```bash
git add -A
git commit -m "fix: set appropriate height for ViewPager2 in IME"
```
