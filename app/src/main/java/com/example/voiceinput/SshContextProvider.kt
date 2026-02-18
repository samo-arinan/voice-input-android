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
        fun reformatPemKey(key: String): String {
            // Extract header and footer using regex
            val headerMatch = Regex("-----BEGIN [A-Z ]+-----").find(key) ?: return key
            val footerMatch = Regex("-----END [A-Z ]+-----").find(key) ?: return key
            val header = headerMatch.value
            val footer = footerMatch.value
            val body = key
                .substringAfter(header)
                .substringBefore(footer)
                .replace(Regex("\\s+"), "")
            if (body.isEmpty()) return key
            val formatted = body.chunked(64).joinToString("\n")
            return "$header\n$formatted\n$footer"
        }

        fun buildCommand(tmuxSession: String): String {
            val target = if (tmuxSession.isBlank()) "" else " -t $tmuxSession"
            return "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; tmux capture-pane$target -p -S -80"
        }
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val WHISPER_CONTEXT_LINES = 3

        fun parseOutput(raw: String): String? {
            val trimmed = raw.trimEnd('\n', ' ')
            return if (trimmed.isEmpty()) null else trimmed
        }

        fun extractWhisperContext(text: String?): String? {
            return null
        }

        fun extractGptContext(text: String?): String? {
            return text
        }
    }

    private var cachedSession: Session? = null

    fun fetchContext(): String? {
        return fetchContextDebug().first
    }

    fun fetchContextDebug(): Pair<String?, String> {
        return try {
            val session = getOrCreateSession()
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(buildCommand(tmuxSession))
            channel.setErrStream(System.err)
            val errStream = channel.errStream
            channel.inputStream.use { input ->
                channel.connect(CONNECT_TIMEOUT_MS)
                val output = input.bufferedReader().readText()
                val err = errStream.bufferedReader().readText()
                val exitStatus = channel.exitStatus
                channel.disconnect()
                val parsed = parseOutput(output)
                val debug = "exit=$exitStatus, rawLen=${output.length}, err=${err.take(200)}"
                Pair(parsed, debug)
            }
        } catch (e: Exception) {
            cachedSession?.disconnect()
            cachedSession = null
            Pair(null, "exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun getOrCreateSession(): Session {
        cachedSession?.let {
            if (it.isConnected) return it
        }
        val jsch = JSch()
        val formattedKey = reformatPemKey(privateKey)
        jsch.addIdentity("key", formattedKey.toByteArray(), null, null)
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
