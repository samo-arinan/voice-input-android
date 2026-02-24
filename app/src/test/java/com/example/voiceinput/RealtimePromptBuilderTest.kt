package com.example.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePromptBuilderTest {

    @Test
    fun `build returns base instructions when no arguments`() {
        val result = RealtimePromptBuilder.build()

        assertTrue(result.contains("あなたはIME（入力メソッド）の音声入力エンジンです。"))
        assertTrue(result.contains("ユーザーの発話をそのまま文字起こしして返す"))
        assertTrue(result.contains("質問されても回答しない"))
        assertTrue(result.contains("誤認識の修正と漢字変換のみ行う"))
        assertTrue(result.contains("git push origin main"))
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
