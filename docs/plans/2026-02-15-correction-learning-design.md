# 音声入力IME 修正・学習システム設計

## Goal

音声認識の誤変換をテキストベースで修正し、修正履歴を学習してGPT後処理の精度を向上させる。

## 要件

1. **フリック入力キーボード**: IME内に隠れたフリックキーボード。必要時のみ表示してテキスト修正
2. **GPT漢字変換**: フリックで入力したひらがなをGPT APIで漢字変換
3. **修正履歴の自動学習**: 修正の差分を自動でローカルDBに保存
4. **学習データのGPT活用**: 過去の修正履歴をGPT後処理のコンテキストに注入

## アーキテクチャ

### 全体フロー

```
音声入力 → Whisper API → 生テキスト
                            ↓
                   修正履歴DB検索（類似20件）
                            ↓
                   GPT後処理（修正例をコンテキストに）
                            ↓
                   候補テキスト表示 → ユーザー確認
                            ↓
                   ユーザーがフリック入力で修正した場合
                            ↓
                   差分検出（TextDiffer）→ 修正履歴DBに自動保存
```

### UI状態遷移

```
[音声入力モード（デフォルト）]
  - 候補テキストエリア
  - マイクボタン
  - キーボード切替ボタン ⌨
       |
       | ⌨タップ
       v
[テキスト修正モード]
  - 候補テキストエリア（編集対象）
  - フリックキーボード（12キー）
  - [🎤戻る][変換][確定]
       |
       | 🎤戻る or 確定
       v
[音声入力モードに復帰]
```

## コンポーネント

### 1. CorrectionDatabase（新規）

SQLiteベースの修正履歴ストレージ。

```sql
CREATE TABLE corrections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    original TEXT NOT NULL,
    corrected TEXT NOT NULL,
    frequency INTEGER DEFAULT 1,
    last_used TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_original_corrected ON corrections(original, corrected);
```

- `saveCorrection(original, corrected)`: 保存。既存ペアなら`frequency++`
- `getRecentCorrections(limit: Int)`: 頻度×最終使用日時でソートして返す
- 最大500件保持。超過時は`frequency`と`last_used`が最も低いものを削除

### 2. FlickKeyboardView（新規）

12キーフリック入力のカスタムView。

キー配置:
```
[あ] [か] [さ] [た] [な]
[は] [ま] [や] [ら] [わ]
[小ﾞﾟ] [ABC] [変換] [確定]
```

フリック方向:
- 中央タップ: あ段（あ、か、さ...）
- 上フリック: い段
- 左フリック: う段
- 下フリック: え段
- 右フリック: お段

出力: コールバックで文字を返す。IME側で`InputConnection`に反映。

### 3. GptConverter（拡張）

既存の`convert()`に修正履歴コンテキストを追加。

```kotlin
fun convertWithHistory(
    rawText: String,
    corrections: List<CorrectionEntry>
): List<TextChunk>
```

システムプロンプト:
```
あなたは日本語音声認識の後処理を行います。
入力テキストの誤字・脱字を修正してください。

以下はユーザーの過去の修正履歴です。同様のパターンがあれば適用してください：
- 「{original}」→「{corrected}」({frequency}回)
...
```

漢字変換メソッド追加:
```kotlin
fun convertHiraganaToKanji(hiragana: String): List<String>
```

### 4. VoiceInputProcessor（拡張）

`stopAndProcess()`に修正履歴を渡す。

```kotlin
fun stopAndProcess(corrections: List<CorrectionEntry>): List<TextChunk>?
```

### 5. VoiceInputIME（拡張）

- キーボード切替ボタン追加
- フリックキーボード表示/非表示トグル
- 修正確定時にTextDifferで差分検出→CorrectionDatabase保存
- `convertWithHistory()`呼び出し時にDB検索結果を渡す

## 自動学習フロー

1. 音声入力→GPT後処理→候補テキスト表示
2. ユーザーがフリック入力でテキストを修正
3. 確定時、TextDifferで`(修正前テキスト, 修正後テキスト)`の差分を検出
4. 変更箇所ごとに`CorrectionDatabase.saveCorrection(original, corrected)`
5. 次回の音声入力時、`getRecentCorrections(20)`でGPTプロンプトに注入

## 技術的決定

- **フリック入力**: EditTextは使わない。カスタムViewでタッチイベント処理→InputConnection.commitText()
- **漢字変換**: GPT API（既存GptConverterの拡張）。専用APIキー不要
- **DB**: Android Room（SQLite）。シンプルなCRUD
- **差分検出**: 既存TextDifferを活用
- **学習トリガー**: 修正確定時に自動保存。確認ダイアログなし
