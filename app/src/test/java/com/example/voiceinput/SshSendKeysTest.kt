package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class SshSendKeysTest {

    @Test
    fun `buildSendKeysCommand with session and simple key`() {
        val cmd = SshContextProvider.buildSendKeysCommand("dev", "Enter")
        assertEquals(
            "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; tmux send-keys -t dev Enter",
            cmd
        )
    }

    @Test
    fun `buildSendKeysCommand with digit key`() {
        val cmd = SshContextProvider.buildSendKeysCommand("dev", "5")
        assertEquals(
            "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; tmux send-keys -t dev 5",
            cmd
        )
    }

    @Test
    fun `buildSendKeysCommand without session`() {
        val cmd = SshContextProvider.buildSendKeysCommand("", "Up")
        assertEquals(
            "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; tmux send-keys Up",
            cmd
        )
    }

    @Test
    fun `buildCaptureCommand with 20 lines`() {
        val cmd = SshContextProvider.buildCaptureCommand("dev", 20)
        assertTrue(cmd.contains("tmux capture-pane -t dev -p -S -20"))
    }
}
