# 縦ViewPager2によるモード切替UI

## 概要

IMEの音声入力モードとフリックキーボードモードの切替を、縦方向ViewPager2によるスワイプ操作に変更する。既存のトグルボタンと🎤ボタンは削除する。

## 現状

- 音声モード内の `keyboardToggleButton` タップでモード切替
- フリックキーボード内の🎤ボタンタップで音声モードに戻る
- 2つのView (`voiceModeArea`, `flickKeyboard`) の `visibility` を切替

## 設計

### レイアウト構造

```
ime_voice_input.xml
├── candidateArea (候補表示 - 上部、モード共通)
└── ViewPager2 (orientation=VERTICAL)
    ├── Page 0: 音声入力モード (imeMicButton + statusText)
    └── Page 1: フリックキーボード (FlickKeyboardView)
```

### コンポーネント

#### IMEModePagerAdapter

`RecyclerView.Adapter` を継承。2ページ分のViewを管理。

- Position 0: 音声モードView（マイクボタン + ステータステキスト）
- Position 1: フリックキーボードView（FlickKeyboardView）

IMEサービス内ではFragmentが使えないため、View-based Adapterを使用。

#### ViewPager2設定

- `orientation = ViewPager2.ORIENTATION_VERTICAL`
- `offscreenPageLimit = 1`（両ページを常にメモリに保持）

#### ページインジケーター

ViewPager2の右端に小さなドットインジケーター（2つ）を表示。現在のページをハイライト。

### タッチ競合の解決

フリックキーボード（Page 1）のキーフリック操作とViewPager2の縦スワイプが競合する。

**解決策:**
- FlickKeyboardView内の各キーの `onTouchEvent` で `parent.requestDisallowInterceptTouchEvent(true)` を呼び、キー操作中はViewPager2のスワイプを無効化
- キーのタッチ終了時に `requestDisallowInterceptTouchEvent(false)` で再度有効化
- フリックキーボードからのスワイプ戻りは、キー外の余白領域でのスワイプで可能

### 削除する要素

- `keyboardToggleButton`（音声モード内のImageButton）
- フリックキーボード3行目の🎤ボタン → 別のキー（句読点など）に置き換え

### VoiceInputIMEとの連携

既存の `showFlickKeyboard()` / `showVoiceMode()` メソッドを更新:
- `showFlickKeyboard()` → `viewPager.setCurrentItem(1, true)`
- `showVoiceMode()` → `viewPager.setCurrentItem(0, true)`

`ViewPager2.OnPageChangeCallback` で `isFlickMode` フラグを更新し、既存ロジックとの整合性を維持。

## テスト方針

- ViewPager2のページ切替時にモードが正しく切り替わることを検証
- タッチ競合：フリックキーボードのキー操作がViewPager2のスワイプに干渉しないことを検証
- 既存の音声入力・フリック入力機能が正常に動作することを回帰テスト
