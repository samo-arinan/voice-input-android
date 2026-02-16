# 音声コマンド学習機能 設計

## 概要

短い発声（ワオン、キャイン等）をコマンドに対応させる学習機能。IMEの🧠モードでコマンドを登録し、🎤モードで認識・実行する。

## Phase構成

- **Phase 1**: コマンド辞書 + 学習UI + 音声保存（本ドキュメント）
- **Phase 2**: TFLite分類器（MFCC特徴量抽出 + one-vs-rest分類）

## Phase 1 設計

### モード構成

ModeIconPagerAdapterを3ページに拡張:

- Page 0: 🎤 音声入力（既存 + 将来的にコマンド認識）
- Page 1: 🧠 学習モード（コマンド登録専用）
- Page 2: ⌨️ フリックキーボード（既存）

### 学習モード（🧠）UI

左側コンテンツ:
- 上部: コマンド一覧（ScrollView）— 登録済みコマンドの表示、録音ボタン、削除ボタン
- 下部: 英数字キーボード — コマンド入力用（a-z, 0-9, /, \n, space, backspace）
- 「＋追加」ボタン — コマンド名と送信文字列を入力して追加

登録フロー:
1. 英数字キーボードで「コマンド名」と「送信文字列」を入力
2. 「＋追加」で辞書に追加
3. コマンド横の「録音」タップ → 短い音声を録音（最大2秒、自動停止）
4. 3回録音して学習データを蓄積
5. 音声データはWAVとしてアプリ内ストレージに保存

### データ構造

#### コマンド辞書 (`files/voice_commands.json`)

```json
{
  "commands": [
    {
      "id": "exit",
      "label": "exit",
      "text": "/exit\n",
      "auto": false,
      "threshold": 0.98,
      "sampleCount": 3,
      "enabled": true
    }
  ]
}
```

- `id`: 一意識別子
- `label`: 表示名
- `text`: 送信する文字列（\nはエンターキー）
- `auto`: trueなら閾値超えで自動実行、falseなら候補表示のみ
- `threshold`: 分類の確信度閾値（Phase 2で使用）
- `sampleCount`: 録音済みサンプル数
- `enabled`: 有効/無効

#### 音声サンプル

保存先: `files/voice_samples/{command_id}_{index}.wav`

例:
- `files/voice_samples/exit_0.wav`
- `files/voice_samples/exit_1.wav`
- `files/voice_samples/exit_2.wav`

### コンポーネント

#### VoiceCommandRepository

コマンド辞書のCRUD。既存のCorrectionRepositoryと同じパターン（JSON + File I/O）。

- `getCommands(): List<VoiceCommand>`
- `addCommand(label: String, text: String): VoiceCommand`
- `deleteCommand(id: String)`
- `updateSampleCount(id: String, count: Int)`

#### VoiceCommand (data class)

```kotlin
data class VoiceCommand(
    val id: String,
    val label: String,
    val text: String,
    val auto: Boolean = false,
    val threshold: Float = 0.95f,
    val sampleCount: Int = 0,
    val enabled: Boolean = true
)
```

#### CommandLearningView (カスタムView)

学習モードの左側コンテンツ。コマンド一覧 + 英数字キーボード。

#### AlphanumericKeyboardView (カスタムView)

英数字入力用の簡易キーボード。a-z, 0-9, 記号（/, \n, space, backspace）。

### 音声録音

既存の`AudioRecorder`を再利用。短い録音に対応:
- タップで開始 → 最大2秒で自動停止
- 録音中は再タップで即停止
- WAVファイルとして保存（既存のAudioProcessor.encodeToWav()を使用）

### テスト方針

- VoiceCommandRepository: JSON読み書き、CRUD操作
- VoiceCommand: データクラスのシリアライズ/デシリアライズ
- AlphanumericKeyboardView: キー配置、入力コールバック
- ModeIconPagerAdapter: 3ページ対応

## Phase 2 概要（次回）

- TFLite依存追加
- MFCC特徴量抽出（AudioProcessorに追加）
- one-vs-rest分類器の学習・推論
- 🎤モードでの認識統合（短い発声検出→分類→候補表示/自動実行）
