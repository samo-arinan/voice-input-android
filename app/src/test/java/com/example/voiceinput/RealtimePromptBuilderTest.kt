package com.example.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePromptBuilderTest {

    @Test
    fun `build returns base instructions when no arguments`() {
        val result = RealtimePromptBuilder.build()

        assertTrue(result.contains("あなたは音声入力の文字起こし・補正ツールです。"))
        assertTrue(result.contains("音声を正確に文字起こしする（日本語）"))
        assertTrue(result.contains("誤字・誤変換を修正する"))
        assertTrue(result.contains("コマンドっぽい発話は実行可能なコマンド文字列に変換する"))
        assertTrue(result.contains("質問や会話には絶対に回答しない。発話内容をそのまま文字起こし・修正して返す"))
        assertTrue(result.contains("意味を変えない。発話の内容はそのまま維持する"))
        assertTrue(result.contains("余計な説明は一切付けず、修正結果のみを返す"))
    }

    @Test
    fun `build with corrections includes history section with entries`() {
        val corrections = listOf(
            CorrectionEntry("きょう", "今日", 3),
            CorrectionEntry("おねがい", "お願い", 5)
        )

        val result = RealtimePromptBuilder.build(corrections = corrections)

        assertTrue(result.contains("「きょう」→「今日」(3回)"))
        assertTrue(result.contains("「おねがい」→「お願い」(5回)"))
    }

    @Test
    fun `build with terminalContext includes terminal context section`() {
        val result = RealtimePromptBuilder.build(terminalContext = "user@host:~/project$ git status")

        assertTrue(result.contains("user@host:~/project$ git status"))
    }

    @Test
    fun `build with empty corrections list omits history section`() {
        val result = RealtimePromptBuilder.build(corrections = emptyList())
        val baseOnly = RealtimePromptBuilder.build()

        assertEquals(baseOnly, result)
    }

    @Test
    fun `build with null terminalContext omits terminal section`() {
        val result = RealtimePromptBuilder.build(terminalContext = null)
        val baseOnly = RealtimePromptBuilder.build()

        assertEquals(baseOnly, result)
    }

    @Test
    fun `build with both corrections and terminalContext includes both sections`() {
        val corrections = listOf(
            CorrectionEntry("てすと", "テスト", 2)
        )

        val result = RealtimePromptBuilder.build(
            corrections = corrections,
            terminalContext = "user@host:~$ ls"
        )

        assertTrue(result.contains("「てすと」→「テスト」(2回)"))
        assertTrue(result.contains("user@host:~$ ls"))
    }

    @Test
    fun `build with blank terminalContext omits terminal section`() {
        val result = RealtimePromptBuilder.build(terminalContext = "   ")
        val baseOnly = RealtimePromptBuilder.build()

        assertEquals(baseOnly, result)
    }
}
