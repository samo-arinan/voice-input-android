# SSH tmux コンテキスト取得機能 設計

## 概要

Termius等のSSHクライアントアプリで音声入力を使う際、IMEからリモートサーバーにSSH接続し `tmux capture-pane` でターミナルの表示内容を取得。それをWhisperのpromptとGPTのコンテキストに渡して認識・変換精度を向上させる。

## 背景

- `getTextBeforeCursor()` はTermius等の端末アプリでは空を返す
- ユーザーは普段Tailscale経由でリモートサーバーにSSH接続しtmuxを使用
- ターミナルの表示内容があれば、コマンド名やパス名の認識精度が大幅に向上する

## コンポーネント

### SshContextProvider（新規）

SSH接続管理とtmuxコンテキスト取得を担当。

- JSch（Java SSH Library）を使用
- `fetchTmuxContext(): String?` — SSH接続 → `tmux capture-pane -p -S -80` → テキスト返却
- Sessionのキャッシュ/再利用でレイテンシ削減
- 接続失敗時はnullを返す（音声入力の処理は続行）

### PreferencesManager（変更）

SSH設定の保存/取得を追加。

- host: String（Tailscale IPまたはホスト名）
- port: Int（デフォルト22）
- username: String
- privateKey: String（EncryptedSharedPreferencesで暗号化保存）
- contextEnabled: Boolean（コンテキスト機能ON/OFF）

### GptConverter（変更）

端末コンテキストをユーザーメッセージに付加。

- `convertWithHistory` に `terminalContext: String?` パラメータ追加
- コンテキストがある場合、ユーザーメッセージを以下の形式に:
  ```
  [端末コンテキスト]
  <tmux出力>

  [音声入力テキスト]
  <Whisper認識結果>
  ```

### VoiceInputIME（変更）

音声入力フローにコンテキスト取得を統合。

- 録音停止後、コマンドマッチ不一致の場合にSSHコンテキスト取得
- 取得テキストをWhisper prompt（末尾20行）とGPT context（全80行）に渡す

## データフロー

```
録音停止
  ↓
コマンドマッチ → ヒットなら実行して終了
  ↓ マッチせず
SshContextProvider.fetchTmuxContext()
  ↓ (並行可能: Whisper APIと同時には不可、audioFile必要)
Whisper API（prompt = コンテキスト末尾20行）
  ↓
GPT API（ユーザーメッセージにコンテキスト全80行付加）
  ↓
結果をコミット
```

## SSH鍵の保存

- Android EncryptedSharedPreferences（既存のsecurity-crypto依存を活用）
- 設定画面から秘密鍵テキストをペースト入力
- パスフレーズ付き鍵もJSchでサポート

## コンテキストサイズ制限

- `tmux capture-pane -p -S -80`: 直近80行を取得
- Whisper prompt: 末尾20行（promptが長すぎると逆効果）
- GPT context: 全80行

## 依存追加

- `com.github.mwiede:jsch:0.2.21`（JSch fork、メンテナンス継続中）

## 非機能要件

- SSH接続失敗時は無視してコンテキストなしで処理続行
- コンテキスト取得タイムアウト: 3秒
- 設定画面でON/OFFトグル可能
