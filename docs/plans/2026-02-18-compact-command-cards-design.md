# Compact Command Cards Design

## Goal

コマンド学習UIのカードを1行コンパクト表示に変更し、タップで展開、トレーニング完了後に音声再生ボタンを表示する。

## Current Problem

- カードが縦に大きく、コマンドが増えるとスクロールが必要
- ルートFrameLayoutが`wrap_content`のためScrollViewの`layout_weight=1`が効かない
- トレーニング完了後に録音サンプルを確認する手段がない

## Design

### Layout Fix

`view_command_learning.xml`のルート`FrameLayout`: `layout_height="wrap_content"` → `layout_height="match_parent"`

### Card: Collapsed (1 line)

```
[●●●○○]  コマンド名                    [▶]
```

- 左: ドットインジケータ (6dp dots, 既存drawable使用)
- 中: コマンド名 (weight=1, 14sp, bold, COLOR_TEXT_MAIN)
- 右: ▶ボタン (sampleCount > 0の場合のみ visible)
- カード背景: 既存 `bg_command_card`
- パディング: 水平12dp, 垂直8dp
- タップでトグル展開

### Card: Expanded (tap to open)

```
[●●●○○]  コマンド名                    [▶]
──────────────────────────────────────────
SEND: /exit
                         [TRAIN]  [DELETE]
```

- 展開部分: divider + SEND text + ボタン行
- アニメーション不要（即時表示/非表示）

### Play Button

- `MediaPlayer`で最新サンプル(`getSampleFile(id, sampleCount - 1)`)を再生
- 再生中: ▶ → ■ (停止ボタン)、タップで停止
- 再生完了: ■ → ▶ に自動復帰
- 画面遷移なし、COMMANDタブ内で完結

### State Management

- `expandedCommandId: String?` — 現在展開中のコマンドID (1つだけ展開)
- `mediaPlayer: MediaPlayer?` — 再生管理
- `playingCommandId: String?` — 再生中のコマンドID

## Tech

- `android.media.MediaPlayer` (標準API、追加依存なし)
- WAVファイルパス: `VoiceCommandRepository.getSampleFile(commandId, sampleCount - 1)`
