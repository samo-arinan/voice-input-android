# Screen Assist - LG Assistant Button + Screen Capture + Voice Q&A

## Overview

LG V60 ThinQのAssistantボタン（音量ボタン下）を押すと、画面上に半透明オーバーレイが表示される。ユーザーは指で画面の一部をなぞりながら音声で質問し、GPT-4o Visionがなぞった範囲の画像と質問に基づいて回答する。

## Decisions

- **既存アプリに追加**: voice-input-android-app (com.example.voiceinput)
- **アプローチ**: VoiceInteractionService方式（正式なAssistant API）
- **AIバックエンド**: OpenAI GPT-4o Vision（既存のAPI基盤を活用）
- **回答表示**: オーバーレイにテキスト表示
- **操作フロー**: なぞりと音声入力の同時進行

## User Flow

```
1. Assistantボタン押下
2. 半透明オーバーレイ表示 + 音声録音自動開始
3. ユーザーが指で範囲をなぞりながら質問を話す
4. 無音検知で録音自動停止 or タップで停止
5. 矩形範囲のスクリーンショット取得 + 音声文字起こし
6. 画像 + 質問テキストをGPT-4o Vision APIに送信
7. 回答をオーバーレイ上に表示
8. タップ or Assistantボタン再押下で閉じる
```

## Architecture

```
[LG Assistant Button]
       |
       v
AssistInteractionService (VoiceInteractionService)
       |  onShowSession()
       v
AssistSession (VoiceInteractionSession)
  +-- Overlay Window (半透明、画面全体)
  |     +-- TouchCanvas (指なぞり -> 矩形範囲)
  |     +-- RecordingIndicator (録音中表示)
  |     +-- ResponseView (AI回答表示)
  +-- AudioRecorder (既存クラス再利用)
  +-- ScreenCapturer (MediaProjection + ImageReader)
  +-- WhisperClient (既存クラス再利用)
  +-- VisionClient (新規、GPT-4o Vision API)
```

## Components

### AssistInteractionService

`VoiceInteractionService`を継承。Assistantボタン押下で`AssistSession`を生成する。

### AssistSession

`VoiceInteractionSession`を継承。オーバーレイ表示、音声録音、画面キャプチャ、API呼び出しを管理する。

### TouchCanvas

カスタムView。`onTouchEvent`でフリーハンドのパスを記録し、外接矩形（bounding box）を選択範囲とする。なぞった軌跡と選択範囲を半透明ハイライトで表示。

### ScreenCapturer

MediaProjection APIで画面をキャプチャし、TouchCanvasの矩形座標でBitmapをクロップする。初回のみActivity経由で許可ダイアログを表示。

### VisionClient

GPT-4o Vision APIクライアント。Base64エンコードした画像と質問テキストを送信。ストリーミング応答対応。

### 既存コンポーネント再利用

- `AudioRecorder`: 音声録音（無音検知による自動停止を追加）
- `WhisperClient`: 音声文字起こし（gpt-4o-transcribe）
- `PreferencesManager`: APIキー管理

## AndroidManifest Changes

```xml
<!-- 追加権限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- 追加サービス -->
<service android:name=".AssistInteractionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <meta-data android:name="android.voice_interaction"
        android:resource="@xml/assist_config" />
</service>
```

## Error Handling

- **MediaProjection許可拒否**: オーバーレイに許可必要メッセージを表示
- **なぞりなし + 音声あり**: 画面全体をキャプチャ
- **なぞりあり + 音声なし**: デフォルト質問「この画像について説明してください」
- **API失敗**: オーバーレイにエラー表示、タップで閉じる
- **セッション中にボタン再押下**: セッションを閉じる（トグル）

## Testing

- **Unit**: ScreenCropper座標計算、VisionClientリクエスト構築、タッチ座標→矩形変換
- **Integration**: MediaProjection→クロップ→Base64変換
- **Manual**: 実機LG V60でAssistantボタン→全フロー確認
