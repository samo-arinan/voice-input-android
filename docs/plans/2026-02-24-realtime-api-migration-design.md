# Realtime API Migration Design

## Purpose

- Whisper API + GPT補正の2段パイプラインをOpenAI Realtime APIに統合
- レイテンシ3-5秒 → ストリーミングでリアルタイム表示 + 高速確定
- VOICEタブUIをミニマルに刷新

## Architecture

### Current

```
AudioRecorder → AudioProcessor → WhisperClient → GptConverter → テキスト挿入
```

### New

```
AudioRecorder → RealtimeClient(WebSocket) → テキスト挿入
```

## Realtime API Integration

- **Protocol:** WebSocket (`wss://api.openai.com/v1/realtime`)
- **Model:** gpt-4o-realtime (or gpt-realtime-1.5 if available)
- **Audio input:** PCM 16kHz 16bit mono, streamed in chunks
- **VAD:** Server-side VAD enabled. Auto-detect end of speech
- **Output:** Text only (audio output disabled)
- **System prompt:** Migrate Japanese correction prompt from GptConverter. Transcription + correction in one pass
- **Context:** SSH/Tmux context, correction history, input field context included in session config

## User Flow

1. User taps mic button → WebSocket connection + recording start
2. Audio streamed in real-time
3. API returns text incrementally → inserted directly into text field
4. VAD detects silence → auto-stop and commit
5. Undo strip appears temporarily (5s auto-dismiss)
6. Manual stop: tap again to commit immediately

## VOICE Tab UI

```
┌─────────────────────────────┐
│  VOICE | COMMAND | TMUX     │  ← tabs (top)
├─────────────────────────────┤
│  [undo strip: post-input]   │  ← hidden by default, appears after input
├─────────────────────────────┤
│                             │
│         ◯                   │  ← idle: accent color circle
│    amplitude-reactive       │  ← recording: concentric ripple rings
│        ripple               │  ← processing: gentle pulse
│                             │
└─────────────────────────────┘
```

### Mic Button

- 40-48dp circle, accent color (blue/teal)
- Idle: clean circle, minimal or no mic icon
- Recording: 2-3 concentric ripple rings expand from center, amplitude-reactive
- Processing: calm pulse animation
- Canvas + ValueAnimator implementation

### Undo Strip

- Appears below tab bar immediately after text is committed
- Layout: `[text preview...] [↶ undo]`
- Auto-dismiss after 5s or next user action
- No permanent screen space occupied

### Removed UI Elements

- Diff display (TextDiffer)
- Approve/reject buttons
- Candidate text display area
- Status indicator (green/orange) — replaced by ripple animation states

## Components

### Remove

- `AudioProcessor.kt` — API handles audio processing
- `WhisperClient.kt` — replaced by Realtime API
- `GptConverter.kt` — integrated into Realtime API (prompts migrated)
- `TextDiffer.kt` — diff display removed
- `ConversionChunk.kt` — no longer needed

### New

- `RealtimeClient.kt` — WebSocket management, audio send, text receive
- `RealtimeSession.kt` — Session config, prompts, VAD settings
- `RippleAnimationView.kt` — Amplitude-reactive ripple drawing
- `UndoStripView.kt` — Temporary undo bar

### Keep (with modifications)

- `AudioRecorder.kt` — PCM recording still used
- `CorrectionRepository.kt` — correction history fed into system prompt
- `VoiceInputProcessor.kt` — refactor to coordinate with RealtimeClient
- `SshContextProvider.kt` — context passed to Realtime session
- `InputContextReader.kt` — context passed to Realtime session
- COMMAND / TMUX tabs — unchanged

## Context Passing

Same context sources as current implementation, passed via Realtime API session config:

1. **SSH/Tmux context** — from `SshContextProvider`
2. **Correction history** — from `CorrectionRepository`
3. **Input field context** — from `InputContextReader`

System prompt updated on session start. Session refreshed when context changes.

## Cost Considerations

- Realtime API charges per minute of audio input + text token output
- Likely higher than current Whisper + GPT
- Model selection in settings UI for user control
