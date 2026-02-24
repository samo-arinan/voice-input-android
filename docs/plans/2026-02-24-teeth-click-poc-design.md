# 歯カチカチ検出 PoC 設計

## 目的

Shokzの骨伝導マイクで歯のカチカチ音を録音し、波形・RMSを可視化する。検出可能かどうかの判断材料を得る。

## 概要

新しいActivity `TeethClickPocActivity` を追加し、Bluetooth SCO経由でShokzマイクから音声を取得、リアルタイムで波形とRMSを表示する。

## アーキテクチャ

```
Shokz (Bluetooth SCO)
    ↓
AudioRecord (8kHz or 16kHz mono PCM)
    ↓
リングバッファ (直近3秒分)
    ↓
├── WaveformView (PCM振幅の波形描画)
├── RmsGraphView (RMS値の時系列折れ線)
└── ログTextView (ピーク検出時のRMS値+タイムスタンプ)
```

## コンポーネント

### TeethClickPocActivity

- Bluetooth SCO接続管理 (`AudioManager.startBluetoothSco()`)
- `AudioRecord` でリアルタイム音声キャプチャ
- バッファからRMS計算、ピーク検出
- UI更新のコーディネーション

### WaveformView (カスタムView)

- 横軸: 時間（直近3秒間スクロール）
- 縦軸: PCM振幅 (-32768 ~ 32767)
- Canvas + Paint で描画、16ms間隔で invalidate

### RmsGraphView (カスタムView)

- RMS値の時系列を折れ線グラフで表示
- 窓幅20msでRMS計算
- 歯カチカチがスパイクとして見えるかを確認する

### ログ表示

- TextViewにRMSピーク値をリアルタイムで流す
- ピーク検出: RMSが直前の平均の3倍以上でスパイク判定
- タイムスタンプ + RMS値 + 直前ピークからの間隔を表示

## 技術的なポイント

- Bluetooth SCOは8kHz or 16kHz（端末依存）
- Android 12では `BLUETOOTH_CONNECT` ランタイムパーミッションが必要
- 既存の `AudioRecorder` は使わず、直接 `AudioRecord` を使用（柔軟性のため）
- 描画スレッドとオーディオスレッドの分離（AudioRecordは専用スレッドで読み取り）

## ファイル構成

```
app/src/main/java/.../poc/
  TeethClickPocActivity.kt    — Activity本体 + Bluetooth SCO管理
  WaveformView.kt             — リアルタイム波形描画View
  RmsGraphView.kt             — RMS時系列グラフView

app/src/main/res/layout/
  activity_teeth_click_poc.xml — レイアウト

AndroidManifest.xml            — Activity追加 + BLUETOOTH_CONNECT パーミッション
```

## スコープ外

- パターン検知ロジック
- 通知やトリガー
- 既存IME機能との統合
- WAVファイル保存
