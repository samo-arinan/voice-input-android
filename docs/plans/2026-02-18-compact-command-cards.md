# Compact Command Cards Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** コマンド学習UIのカードを1行コンパクト表示に変更し、タップで展開/折りたたみ、トレーニング済みコマンドの音声再生ボタンを追加する。

**Architecture:** `CommandLearningView.buildCommandCard()` を全面書き換え。カードは横並び1行(ドット+名前+再生ボタン)の折りたたみ状態がデフォルト。タップで展開しSENDテキストとTRAIN/DELETEボタンを表示。`MediaPlayer`で最新録音サンプルを再生。レイアウトの高さ問題も修正。

**Tech Stack:** Android View (programmatic), `android.media.MediaPlayer`, 既存drawable再利用

**Design doc:** `docs/plans/2026-02-18-compact-command-cards-design.md`

---

### Task 1: Fix layout heights for bounded scroll area

**Files:**
- Modify: `app/src/main/res/layout/view_command_learning.xml:2-5`
- Modify: `app/src/main/res/layout/ime_voice_input.xml:139-143`

**Context:** 現在 `view_command_learning.xml` のルート FrameLayout が `wrap_content` なので、内部 ScrollView の `layout_weight=1` が効かない。IMEレイアウトでは各コンテンツタブが独自の高さを持つため、commandLearning に固定高さを設定する。

**Step 1: Modify view_command_learning.xml root FrameLayout**

```xml
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
```

Change `layout_height` from `wrap_content` to `match_parent`.

Also change the inner LinearLayout (line 9) from `wrap_content` to `match_parent`:
```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:background="#111418">
```

**Step 2: Set commandLearning fixed height in IME layout**

In `ime_voice_input.xml`, change commandLearning from `match_parent` to `250dp`:
```xml
        <com.example.voiceinput.CommandLearningView
            android:id="@+id/commandLearning"
            android:layout_width="match_parent"
            android:layout_height="250dp"
            android:visibility="gone" />
```

This gives a bounded height for the command tab. ScrollView inside with `weight=1` will fill the remaining space after title + input area.

**Step 3: Run existing tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS (layout-only change doesn't affect unit tests)

**Step 4: Commit**

```bash
git add app/src/main/res/layout/view_command_learning.xml app/src/main/res/layout/ime_voice_input.xml
git commit -m "fix: set bounded height for command learning scroll area"
```

---

### Task 2: Write tests for compact card state logic

**Files:**
- Create: `app/src/test/java/com/example/voiceinput/CommandCardStateTest.kt`

**Context:** カードの展開/折りたたみと再生ボタン表示のロジックをテストする。`CommandLearningView` は Android View なので直接テストできないが、状態判定ロジックを companion object のヘルパーとして抽出しテストする。

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandCardStateTest {

    @Test
    fun `play button visible when sampleCount greater than zero`() {
        assertTrue(CommandLearningView.shouldShowPlayButton(sampleCount = 1))
        assertTrue(CommandLearningView.shouldShowPlayButton(sampleCount = 5))
    }

    @Test
    fun `play button hidden when sampleCount is zero`() {
        assertFalse(CommandLearningView.shouldShowPlayButton(sampleCount = 0))
    }

    @Test
    fun `latest sample index is sampleCount minus one`() {
        assertEquals(0, CommandLearningView.latestSampleIndex(sampleCount = 1))
        assertEquals(4, CommandLearningView.latestSampleIndex(sampleCount = 5))
    }

    @Test
    fun `latest sample index returns negative one when no samples`() {
        assertEquals(-1, CommandLearningView.latestSampleIndex(sampleCount = 0))
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandCardStateTest"`
Expected: FAIL — `shouldShowPlayButton` and `latestSampleIndex` don't exist yet

**Step 3: Add companion object helpers to CommandLearningView**

In `app/src/main/java/com/example/voiceinput/CommandLearningView.kt`, add to the existing `companion object`:

```kotlin
    companion object {
        // ... existing constants ...

        fun shouldShowPlayButton(sampleCount: Int): Boolean = sampleCount > 0

        fun latestSampleIndex(sampleCount: Int): Int = if (sampleCount > 0) sampleCount - 1 else -1
    }
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.voiceinput.CommandCardStateTest"`
Expected: All 4 tests PASS

**Step 5: Commit**

```bash
git add app/src/test/java/com/example/voiceinput/CommandCardStateTest.kt app/src/main/java/com/example/voiceinput/CommandLearningView.kt
git commit -m "feat: add companion helpers for card state logic with tests"
```

---

### Task 3: Refactor buildCommandCard to compact 1-line with tap-to-expand

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/CommandLearningView.kt:42-44` (new fields)
- Modify: `app/src/main/java/com/example/voiceinput/CommandLearningView.kt:150-282` (buildCommandCard rewrite)

**Context:** 現在の `buildCommandCard()` はフル展開カード (name, divider, send, training, dots, buttons) を返す。これを折りたたみ1行 (dots + name + play button) にし、タップで展開部分 (divider + send + buttons) を表示/非表示にする。

**Step 1: Add state field**

In `CommandLearningView`, add after existing field declarations (around line 44):
```kotlin
    private var expandedCommandId: String? = null
```

**Step 2: Rewrite buildCommandCard**

Replace the entire `buildCommandCard` method (lines 150-282) with:

```kotlin
    private fun buildCommandCard(cmd: VoiceCommand): LinearLayout {
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_command_card)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = params
        }

        // === Header row (always visible): [dots] [name] [play] ===
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // Dot indicators
        val dotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (i in 0 until MAX_SAMPLES) {
            val dot = ImageView(context).apply {
                setImageResource(
                    if (i < cmd.sampleCount) R.drawable.dot_filled else R.drawable.dot_empty
                )
                val size = dp(6)
                val params = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(3)
                }
                layoutParams = params
            }
            dotRow.addView(dot)
        }
        headerRow.addView(dotRow)

        // Command name
        val nameView = TextView(context).apply {
            text = cmd.label
            setTextColor(COLOR_TEXT_MAIN)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(nameView)

        // Play button (visible only if sampleCount > 0)
        val playBtn = TextView(context).apply {
            text = "\u25B6"  // ▶
            setTextColor(COLOR_ACCENT)
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(32)
            layoutParams = LinearLayout.LayoutParams(size, size)
            visibility = if (shouldShowPlayButton(cmd.sampleCount)) View.VISIBLE else View.GONE
            setOnClickListener {
                onPlayTapped(cmd)
            }
        }
        headerRow.addView(playBtn)

        card.addView(headerRow)

        // === Expandable section (hidden by default) ===
        val expandSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
            visibility = if (expandedCommandId == cmd.id) View.VISIBLE else View.GONE
            tag = "expandSection"
        }

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(6) }
        }
        expandSection.addView(divider)

        // Send text
        val sendView = TextView(context).apply {
            text = "SEND: /${cmd.text.replace("\n", "\\n")}"
            setTextColor(COLOR_TEXT_SUB)
            textSize = 11f
            letterSpacing = 0.15f
        }
        expandSection.addView(sendView)

        // Button row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = params
        }

        val trainBtn = TextView(context).apply {
            text = "TRAIN"
            setTextColor(COLOR_ACCENT)
            textSize = 11f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_outline_accent)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                flashCardBorder(card)
                listener?.onRecordSample(cmd.id, cmd.sampleCount)
            }
        }

        val deleteBtn = TextView(context).apply {
            text = "DELETE"
            setTextColor(COLOR_DANGER)
            textSize = 11f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_outline_danger)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            layoutParams = params
            setOnClickListener {
                listener?.onDeleteCommand(cmd.id)
            }
        }

        buttonRow.addView(trainBtn)
        buttonRow.addView(deleteBtn)
        expandSection.addView(buttonRow)

        card.addView(expandSection)

        // Tap header to expand/collapse
        headerRow.setOnClickListener {
            val section = card.findViewWithTag<View>("expandSection")
            if (expandedCommandId == cmd.id) {
                expandedCommandId = null
                section?.visibility = View.GONE
            } else {
                // Collapse previously expanded card
                collapseAllCards()
                expandedCommandId = cmd.id
                section?.visibility = View.VISIBLE
            }
        }

        return card
    }

    private fun collapseAllCards() {
        val list = commandList ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            child.findViewWithTag<View>("expandSection")?.visibility = View.GONE
        }
    }
```

**Step 3: Update animateDotFill for new card structure**

The `animateDotFill` method (line 295-310) references the old child indices. Update it:

```kotlin
    fun animateDotFill(cardIndex: Int, filledCount: Int) {
        val card = commandList?.getChildAt(cardIndex) as? LinearLayout ?: return
        // Header row is child 0, expand section is child 1
        val headerRow = card.getChildAt(0) as? LinearLayout ?: return
        // Dot row is first child of header row
        val dotRow = headerRow.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until dotRow.childCount) {
            if (i < filledCount) {
                val dot = dotRow.getChildAt(i) as? ImageView ?: continue
                dot.postDelayed({
                    dot.setImageResource(R.drawable.dot_filled)
                    dot.scaleX = 0f
                    dot.scaleY = 0f
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }, (i * 100).toLong())
            }
        }
    }
```

**Step 4: Add placeholder onPlayTapped method**

```kotlin
    private fun onPlayTapped(cmd: VoiceCommand) {
        // Will be implemented in Task 4
    }
```

**Step 5: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/CommandLearningView.kt
git commit -m "feat: refactor command cards to compact 1-line with tap-to-expand"
```

---

### Task 4: Add MediaPlayer playback

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/CommandLearningView.kt` (imports, fields, onPlayTapped, cleanup)

**Context:** ▶ボタンタップで最新サンプルWAVを `MediaPlayer` で再生。再生中は■(停止)に変わり、タップで停止。再生完了で▶に自動復帰。View detach時にリソース解放。

**Step 1: Add imports and fields**

Add to imports:
```kotlin
import android.media.MediaPlayer
```

Add fields after `expandedCommandId`:
```kotlin
    private var mediaPlayer: MediaPlayer? = null
    private var playingCommandId: String? = null
    private var activePlayButton: TextView? = null
```

**Step 2: Implement onPlayTapped**

Replace the placeholder with:

```kotlin
    private fun onPlayTapped(cmd: VoiceCommand) {
        // If already playing this command, stop
        if (playingCommandId == cmd.id) {
            stopPlayback()
            return
        }

        // Stop any existing playback
        stopPlayback()

        val repo = commandRepo ?: return
        val sampleIndex = latestSampleIndex(cmd.sampleCount)
        if (sampleIndex < 0) return
        val file = repo.getSampleFile(cmd.id, sampleIndex)
        if (!file.exists()) return

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { stopPlayback() }
            }
            playingCommandId = cmd.id
            updatePlayButtonState(cmd.id, playing = true)
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        val prevId = playingCommandId
        mediaPlayer?.release()
        mediaPlayer = null
        playingCommandId = null
        if (prevId != null) {
            updatePlayButtonState(prevId, playing = false)
        }
    }

    private fun updatePlayButtonState(commandId: String, playing: Boolean) {
        // Find the card for this command and update the play button text
        val list = commandList ?: return
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i) as? LinearLayout ?: continue
            val headerRow = card.getChildAt(0) as? LinearLayout ?: continue
            // Play button is the last child of header row
            val playBtn = headerRow.getChildAt(headerRow.childCount - 1) as? TextView ?: continue
            if (playBtn.visibility == View.VISIBLE || playing) {
                // Check if this is the right card by matching tag
                if (card.tag == commandId) {
                    playBtn.text = if (playing) "\u25A0" else "\u25B6"  // ■ or ▶
                }
            }
        }
    }
```

**Step 3: Set card tag in buildCommandCard**

In the `buildCommandCard` method, after creating the card LinearLayout, add:
```kotlin
        card.tag = cmd.id
```

This is needed for `updatePlayButtonState` to find the correct card.

**Step 4: Add cleanup on detach**

Override `onDetachedFromWindow`:
```kotlin
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPlayback()
    }
```

**Step 5: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/CommandLearningView.kt
git commit -m "feat: add MediaPlayer playback for trained command samples"
```

---

### Task 5: Build, verify, and sync APK

**Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

**Step 2: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Copy APK to sync folder**

Run: `cp app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 4: Final commit if any remaining changes**

```bash
git status
```

If clean, done. If not, commit any remaining changes.
