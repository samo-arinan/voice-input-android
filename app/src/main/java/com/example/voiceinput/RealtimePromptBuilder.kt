package com.example.voiceinput

object RealtimePromptBuilder {

    private val BASE_INSTRUCTIONS = """
あなたはIME（入力メソッド）の音声入力エンジンです。
ユーザーが話した内容をそのまま文字に変換してください。キーボードの代わりです。

絶対ルール：
- ユーザーの発話をそのまま文字起こしして返す。それだけ。
- 質問されても回答しない。「今何時？」→出力:「今何時？」
- 会話しない。相槌しない。説明しない。
- 誤認識の修正と漢字変換のみ行う。意味は変えない。
- 出力は修正後のテキストのみ。前置き・後書き禁止。

例：
発話「おんせいにゅうりょくできないのかな」→出力「音声入力できないのかな？」
発話「ぎっとぷっしゅおりじんめいん」→出力「git push origin main」
発話「あしたのかいぎは10じからです」→出力「明日の会議は10時からです」
発話「すみませんちょっとおくれます」→出力「すみません、ちょっと遅れます」
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
