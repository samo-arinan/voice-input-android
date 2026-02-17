package com.example.voiceinput

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

class SshContextProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val privateKey: String,
    private val tmuxSession: String = ""
) {
    companion object {
        fun buildCommand(tmuxSession: String): String {
            val target = if (tmuxSession.isBlank()) "" else " -t $tmuxSession"
            return "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; tmux capture-pane$target -p -S -80"
        }
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val WHISPER_CONTEXT_LINES = 20

        fun parseOutput(raw: String): String? {
            val trimmed = raw.trimEnd('\n', ' ')
            return if (trimmed.isEmpty()) null else trimmed
        }

        fun extractWhisperContext(text: String?): String? {
            if (text == null) return null
            val lines = text.split("\n")
            return if (lines.size <= WHISPER_CONTEXT_LINES) {
                text
            } else {
                lines.takeLast(WHISPER_CONTEXT_LINES).joinToString("\n")
            }
        }

        fun extractGptContext(text: String?): String? {
            return text
        }
    }

    private var cachedSession: Session? = null

    fun fetchContext(): String? {
        return try {
            val session = getOrCreateSession()
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(buildCommand(tmuxSession))
            channel.inputStream.use { input ->
                channel.connect(CONNECT_TIMEOUT_MS)
                val output = input.bufferedReader().readText()
                channel.disconnect()
                parseOutput(output)
            }
        } catch (e: Exception) {
            cachedSession?.disconnect()
            cachedSession = null
            null
        }
    }

    private fun getOrCreateSession(): Session {
        cachedSession?.let {
            if (it.isConnected) return it
        }
        val jsch = JSch()
        jsch.addIdentity("key", privateKey.toByteArray(), null, null)
        val session = jsch.getSession(username, host, port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(CONNECT_TIMEOUT_MS)
        cachedSession = session
        return session
    }

    fun disconnect() {
        cachedSession?.disconnect()
        cachedSession = null
    }
}
