# Volume Indicator Ring Design

## Overview

録音中にマイクボタンの外周にリングを表示し、音量が十分かどうかを色で示す。
赤（音量不足）→ 黄（中間）→ 緑（十分）と変化し、ユーザーが声の大きさを調整できる。

## Components

### AudioRecorder — getAmplitude()

- `MediaRecorder.maxAmplitude` をラップする `getAmplitude(): Int` メソッドを追加
- 録音中でなければ 0 を返す
- `maxAmplitude` は呼び出しごとにリセットされる（Android API の仕様）

### MicButtonRingView — カスタム View

- `FrameLayout` を継承
- 子要素として既存のマイクボタン `ImageView` を内包
- `onDraw()` で `Canvas.drawArc()` を使い、ボタン外周にリング（幅 4dp）を描画
- `setAmplitude(level: Float)` で 0.0〜1.0 の正規化レベルを受け取る
- レベルに応じた色補間:
  - 0.0〜0.3: 赤 `#FF5252`
  - 0.3〜0.7: 赤→黄 `#FFC107` への補間
  - 0.7〜1.0: 黄→緑 `#4CAF50` への補間
- リングは常に全周（360度）描画、色のみ変化
- `isVisible` プロパティで表示/非表示を制御
- 録音していないときはリング非表示

### VoiceInputIME — ポーリング

- 録音開始時に `Handler.postDelayed` で 100ms 間隔のポーリング開始
- `AudioRecorder.getAmplitude()` の値を閾値 5000 で正規化（`min(amplitude / 5000f, 1.0f)`）
- `MicButtonRingView.setAmplitude()` に渡す
- 録音終了時にポーリング停止、リング非表示

### Layout — ime_voice_input.xml

- `ImageView`（マイクボタン）を `MicButtonRingView` でラップ
- `MicButtonRingView` のサイズ: 64x64dp（56dp ボタン + 4dp リング幅 × 2）
- 内部の `ImageView` は中央配置

## Threshold

- `MediaRecorder.maxAmplitude` の範囲: 0〜32767
- 閾値 5000: 通常の会話音量で緑になる程度
- 固定値で十分（ユーザー設定は不要）

## Test Strategy

- `AudioRecorder.getAmplitude()`: MediaRecorder mock で maxAmplitude の値を検証
- `MicButtonRingView`: amplitude→色の補間ロジックをユニットテスト
- ポーリング: VoiceInputIME のテストスコープ外（UI統合テスト）
