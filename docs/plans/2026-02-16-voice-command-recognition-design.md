# オンデバイスMFCC音声コマンド認識 設計

## 概要

登録済み音声コマンドをオンデバイスで認識・実行する。MFCC特徴量抽出+DTW距離照合を純Kotlin実装（外部依存なし）。音声入力後にコマンド判定し、マッチすれば即実行、しなければ通常Whisper→GPT変換へ。

## 変更点

- サンプル上限: 3→5個

## アーキテクチャ

```
録音完了 → WAV取得
  → MfccExtractor.extract(wavBytes) → FloatArray[]  (13次元×Nフレーム)
  → CommandMatcher.match(inputMfcc, registeredCommands) → MatchResult?
  → if match != null && distance < threshold:
      CommandExecutor.execute(command, inputConnection)
    else:
      既存 Whisper→GPT フロー
```

## コンポーネント

### MfccExtractor

WAV PCM 16kHz 16bit → MFCC特徴量ベクトル。

パイプライン:
1. WAVヘッダー解析 → PCMサンプル取得
2. プリエンファシス (係数0.97)
3. フレーム分割: 25ms窓 (400サンプル)、10msストライド (160サンプル)
4. ハミング窓適用
5. FFT 512点 → パワースペクトル
6. メルフィルタバンク 26バンド (300Hz〜8000Hz)
7. 対数変換
8. DCT → 13次元MFCC係数

入力: ByteArray (WAVファイル内容) または ShortArray (PCMサンプル)
出力: Array<FloatArray> — 各フレームの13次元MFCC

### CommandMatcher

登録コマンド群とのDTW距離照合。

- DTW (Dynamic Time Warping): 2つのMFCC系列間のユークリッド距離ベースの最適整列
- 各コマンドの全サンプル(最大5個)とDTW計算、最小距離を採用
- 全コマンド中の最小距離コマンドを候補に
- 距離 < threshold なら認識成功

入力: Array<FloatArray> (入力MFCC), List<RegisteredCommand> (コマンド+サンプルMFCC群)
出力: MatchResult? (command, distance) or null

### CommandExecutor

マッチしたコマンドのテキストを入力。

- `text`をcommitText
- `\n`はEnterキーイベント (ACTION_DOWN + ACTION_UP) として送信
- ステータスに「コマンド実行: {label}」表示

### MfccCache (VoiceCommandRepository拡張)

- サンプル録音時にMFCCを計算しキャッシュ
- キャッシュ形式: `filesDir/voice_samples/{id}_{index}.mfcc` (シリアライズFloatArray)
- 起動時にキャッシュ読み込み → WAV毎回解析不要

## VoiceInputIME変更

`onMicReleased()` のフロー分岐:

```kotlin
// 録音停止 → WAVファイル取得
val wavFile = proc.stopRecording()
val inputMfcc = mfccExtractor.extract(wavFile)
val matchResult = commandMatcher.match(inputMfcc, registeredCommands)

if (matchResult != null) {
    commandExecutor.execute(matchResult.command)
} else {
    // 既存の Whisper→GPT 変換フロー
    proc.processRecordedAudio(wavFile, corrections)
}
```

## パフォーマンス想定

| コマンド数 | サンプル/個 | DTW回数 | 概算時間 |
|---|---|---|---|
| 5 | 5 | 25 | ~50ms |
| 10 | 5 | 50 | ~100ms |
| 20 | 5 | 100 | ~200ms |

Whisper API通信 (~500ms〜2s) より十分高速。

## テスト戦略

- **MfccExtractor**: 既知サイン波→期待MFCC係数、無音→ゼロ近傍
- **DTW**: 同一系列→距離0、異なる系列→距離大、時間伸縮に頑健
- **CommandMatcher**: マッチあり/なし/閾値境界
- **CommandExecutor**: テキスト送信、\n→Enterキー変換
- **統合**: フロー分岐テスト
