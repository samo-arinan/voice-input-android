# Android音声入力アプリ 設計ドキュメント

## 概要

Android 11向けの音声入力アプリ。Accessibilityサービスとフローティングボタンを使い、任意のアプリのテキストフィールドに音声で入力できる。主な利用シーンはTermius（Claude Code）での作業。

## 要件

- **対象OS**: Android 11
- **形態**: Accessibilityサービス + フローティングボタン
- **操作**: Push-to-Talk（長押しで録音、離すと文字起こし＋変換＋入力）
- **音声認識**: OpenAI Whisper API
- **テキスト変換**: OpenAI GPT API（発話内容の文脈から自動判断）
- **APIキー管理**: アプリ内設定画面
- **開発言語**: Kotlin
- **開発環境**: Android Studio

## アーキテクチャ

シンプル直結型を採用。AccessibilityServiceが全体を統括し、録音→API→入力を直列に処理する。

```
[フローティングボタン長押し]
    → [AudioRecorder: 録音]
    → [WhisperClient: 音声→テキスト]
    → [GptConverter: 文脈判断して変換]
    → [AccessibilityService: テキストフィールドに入力]
```

## コンポーネント

### MainActivity.kt
- APIキーの入力・保存画面
- Accessibilityサービスの有効化案内（設定画面へ誘導）
- サービスの状態表示

### VoiceInputService.kt (AccessibilityService)
- Accessibilityサービス本体
- フォーカスされたテキストフィールドの検知
- テキストの入力（`performAction(ACTION_SET_TEXT)` or `ACTION_PASTE`）
- FloatingButtonManager、AudioRecorder、API クライアントの統括

### FloatingButtonManager.kt
- WindowManagerでフローティングボタンを表示
- 長押し検知（OnTouchListener）
- 録音中のビジュアルフィードバック（色変更等）

### AudioRecorder.kt
- MediaRecorderまたはAudioRecordで録音
- Whisper APIが受け付ける形式（WAV/m4a等）で保存
- 録音開始・停止のインターフェース

### WhisperClient.kt
- OpenAI Whisper APIへの音声ファイルアップロード
- レスポンスからテキスト抽出
- 言語指定: `ja`（日本語）

### GptConverter.kt
- Whisperの出力テキストをGPT APIに送信
- 文脈に基づき適切な出力に変換

システムプロンプト:
```
あなたは音声入力アシスタントです。
ユーザーの発話テキストを受け取り、適切な出力に変換してください。

ルール：
- 発話がコマンドの意図なら、実行可能なコマンド文字列のみを返す
- 発話が日本語の文章なら、自然な日本語としてそのまま返す
- 余計な説明は一切付けず、変換結果のみを返す

例：
入力: 「ファイル一覧を表示して」→ 出力: ls -la
入力: 「このバグを修正してください」→ 出力: このバグを修正してください
入力: 「ギットステータス」→ 出力: git status
入力: 「お疲れ様です、今日の進捗を報告します」→ 出力: お疲れ様です、今日の進捗を報告します
```

### PreferencesManager.kt
- EncryptedSharedPreferencesでAPIキーを安全に保存
- APIキーの読み書きインターフェース

## データフロー

```
1. ユーザーがフローティングボタンを長押し
2. AudioRecorder.start() → マイク録音開始
3. ボタンを離す → AudioRecorder.stop() → 音声ファイル生成
4. WhisperClient.transcribe(audioFile) → 生テキスト
5. GptConverter.convert(rawText) → 変換済みテキスト
6. VoiceInputService → アクティブなテキストフィールドに入力
```

## 必要なパーミッション

- `RECORD_AUDIO` - マイク録音
- `INTERNET` - API通信
- `SYSTEM_ALERT_WINDOW` - フローティングボタン表示
- `FOREGROUND_SERVICE` - バックグラウンド録音

## テスト方針

- `WhisperClient`, `GptConverter`: APIレスポンスのモックでユニットテスト
- `AudioRecorder`: 録音ファイル生成のテスト
- `PreferencesManager`: 保存・読み込みのテスト
- E2E: 実機でTermius上での動作確認
