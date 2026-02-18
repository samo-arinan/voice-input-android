# TMUX Tab Design

## Goal

INPUT タブを TMUX タブに置き換え。リモート tmux の内容表示、キー送信、Claude Code 承認待ち ntfy.sh 通知を実装する。

## Changes

### Tab Rename
- `TAB_INPUT` → `TAB_TMUX`
- タブテキスト: "INPUT" → "TMUX"
- `showFlickKeyboardContent()` → `showTmuxContent()`

### Delete (INPUT関連全削除)
- `FlickKeyboardView.kt`
- `FlickResolver.kt`
- `FlickKeyboardListener` interface
- `VoiceInputIME.kt`: `isFlickMode`, `composingBuffer`, flick関連メソッド全削除
- `ime_voice_input.xml`: flickKeyboard要素削除

### New: TmuxView
- `view_tmux.xml` レイアウト + `TmuxView.kt` クラス
- 上部: tmux出力エリア (モノスペース TextView, scrollable, 20行)
- 下部: キーボード1行

### Tmux表示エリア
- `SshContextProvider` を再利用、`tmux capture-pane -p -S -20` で取得
- タブ表示中: 2秒間隔ポーリング更新
- タブ非表示: ポーリング停止
- 背景: `#111418`、テキスト: `#E6EDF3`、フォント: monospace 10sp

### キーボード (1行)
```
[↑] [↓] [0][1][2][3][4][5][6][7][8][9] [⌫] [⏎]
```
- COMMAND画面と同じトーン: 背景 `#1A1F26`, テキスト `#8B949E`, 11sp
- SSH経由 `tmux send-keys` でリモートに送信
- 各キーのsend-keys引数:
  - ↑: `Up`, ↓: `Down`, 0-9: そのまま, ⌫: `BSpace`, ⏎: `Enter`

### ntfy.sh 通知
- **設定**: MainActivity に ntfy トピック名入力欄追加 (PreferencesManager)
- **受信**: IME起動時に `https://ntfy.sh/{topic}/sse` を購読 (EventSource/OkHttp SSE)
- **送信**: ユーザーがClaude Codeフックで設定: `curl -d "approval" ntfy.sh/{topic}`

### タブ通知アニメーション
- TMUX タブテキスト右に緑丸 (6dp, `#4ADE80`)
- 通知受信: alpha 0↔1 の明滅 (500ms loop)
- TMUXタブタップ: 明滅停止、緑丸非表示

## Tech
- `SshContextProvider` (既存): tmux表示 + send-keys
- `OkHttp` (既存依存): ntfy.sh SSE購読
- `Handler/Runnable`: ポーリングタイマー
- `ObjectAnimator`: 緑丸明滅
