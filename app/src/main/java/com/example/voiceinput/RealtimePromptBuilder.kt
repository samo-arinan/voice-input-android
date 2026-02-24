package com.example.voiceinput

object RealtimePromptBuilder {

    private val BASE_INSTRUCTIONS = """
あなたは音声入力の文字起こし・補正ツールです。
ユーザーの音声を聞き取り、正確に文字起こしして修正したテキストのみを返してください。

ルール：
- 音声を正確に文字起こしする（日本語）
- 誤字・誤変換を修正する
- コマンドっぽい発話は実行可能なコマンド文字列に変換する
- 質問や会話には絶対に回答しない。発話内容をそのまま文字起こし・修正して返す
- 意味を変えない。発話の内容はそのまま維持する
- 余計な説明は一切付けず、修正結果のみを返す
""".trimStart()

    fun build(
        corrections: List<CorrectionEntry>? = null,
        terminalContext: String? = null
    ): String {
        val sb = StringBuilder(BASE_INSTRUCTIONS)

        if (!corrections.isNullOrEmpty()) {
            sb.append("\n過去の修正履歴（参考にしてください）：\n")
            for (entry in corrections) {
                sb.append("「${entry.original}」→「${entry.corrected}」(${entry.frequency}回)\n")
            }
        }

        if (!terminalContext.isNullOrBlank()) {
            sb.append("\n現在のターミナルコンテキスト：\n")
            sb.append(terminalContext)
            sb.append("\n")
        }

        return sb.toString()
    }
}
