package com.example.voiceinput

import io.mockk.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealtimeInputControllerTest {

    private lateinit var controller: RealtimeInputController
    private lateinit var capturedListener: RealtimeClient.Listener
    private val mockClient = mockk<RealtimeClient>(relaxed = true)
    private val mockStreamer = mockk<RealtimeAudioStreamer>(relaxed = true)
    private val callback = mockk<RealtimeInputController.Callback>(relaxed = true)

    @Before
    fun setup() {
        every { mockStreamer.start() } returns true
        every { mockStreamer.currentRms } returns 0f

        controller = RealtimeInputController(
            httpClient = mockk(relaxed = true),
            postToMain = { it() },
            createClient = { _, listener ->
                capturedListener = listener
                mockClient
            },
            createStreamer = { mockStreamer }
        )
        controller.callback = callback
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `start transitions to CONNECTING`() {
        controller.start("key", "model", "instructions")
        verify { callback.onStateChanged(RealtimeInputController.State.CONNECTING) }
    }

    @Test
    fun `start connects client with apiKey model and instructions`() {
        controller.start("sk-test", "gpt-4o-realtime", "be a transcriber")
        verify { mockClient.setInstructions("be a transcriber") }
        verify { mockClient.connect("sk-test", "gpt-4o-realtime") }
    }

    @Test
    fun `start when not IDLE does nothing`() {
        controller.start("key", "model", "instructions")
        capturedListener.onSessionReady()
        clearMocks(callback)

        controller.start("key", "model", "instructions")
        verify(exactly = 0) { callback.onStateChanged(any()) }
    }

    @Test
    fun `onSessionReady starts streamer and transitions to RECORDING`() {
        controller.start("key", "model", "instructions")
        capturedListener.onSessionReady()

        verify { mockStreamer.start() }
        assertEquals(RealtimeInputController.State.RECORDING, controller.state)
    }

    @Test
    fun `onSessionReady with streamer failure triggers error and cleanup`() {
        every { mockStreamer.start() } returns false
        controller.start("key", "model", "instructions")
        capturedListener.onSessionReady()

        verify { callback.onError("録音を開始できません") }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `onTextDelta accumulates composing text`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        capturedListener.onTextDelta("音")
        verify { callback.onComposingText("音") }

        capturedListener.onTextDelta("声")
        verify { callback.onComposingText("音声") }

        capturedListener.onTextDelta("入力")
        verify { callback.onComposingText("音声入力") }
    }

    @Test
    fun `onTextDone calls onCommitText`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        capturedListener.onTextDelta("音声")

        capturedListener.onTextDone("音声入力")
        verify { callback.onCommitText("音声入力") }
    }

    @Test
    fun `composing text is cleared after onTextDone`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        capturedListener.onTextDelta("first")
        capturedListener.onTextDone("first")

        // New deltas should start fresh
        capturedListener.onTextDelta("second")
        verify { callback.onComposingText("second") }
    }

    @Test
    fun `onSpeechStopped pauses streaming and transitions to PROCESSING`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        capturedListener.onSpeechStopped()
        verify { mockStreamer.pauseStreaming() }
        verify { mockStreamer.stop() }
        assertEquals(RealtimeInputController.State.PROCESSING, controller.state)
    }

    @Test
    fun `onResponseDone restarts streamer and returns to RECORDING`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        capturedListener.onSpeechStopped()

        capturedListener.onResponseDone()
        verify(exactly = 0) { mockClient.disconnect() }
        // streamer.start() called twice: initial + restart
        verify(exactly = 2) { mockStreamer.start() }
        assertEquals(RealtimeInputController.State.RECORDING, controller.state)
    }

    @Test
    fun `onResponseDone with streamer restart failure cleans up`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        capturedListener.onSpeechStopped()

        every { mockStreamer.start() } returns false
        capturedListener.onResponseDone()
        verify { mockClient.disconnect() }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `manual stop then responseDone disconnects`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        controller.stop()
        assertEquals(RealtimeInputController.State.PROCESSING, controller.state)

        capturedListener.onResponseDone()
        verify { mockClient.disconnect() }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `stop during PROCESSING causes disconnect on responseDone`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        capturedListener.onSpeechStopped()
        assertEquals(RealtimeInputController.State.PROCESSING, controller.state)

        controller.stop()  // during PROCESSING

        capturedListener.onResponseDone()
        verify { mockClient.disconnect() }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `stop commits audio and transitions to PROCESSING`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        controller.stop()
        verify { mockStreamer.pauseStreaming() }
        verify { mockClient.commitAudio() }
        assertEquals(RealtimeInputController.State.PROCESSING, controller.state)
    }

    @Test
    fun `stop when not RECORDING or PROCESSING does nothing`() {
        controller.stop()
        verify(exactly = 0) { mockClient.commitAudio() }
    }

    @Test
    fun `multiple VAD cycles commit text for each segment`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        // First VAD cycle
        capturedListener.onSpeechStopped()
        capturedListener.onTextDelta("first")
        capturedListener.onTextDone("first")
        capturedListener.onResponseDone()
        verify { callback.onCommitText("first") }
        assertEquals(RealtimeInputController.State.RECORDING, controller.state)

        // Second VAD cycle
        capturedListener.onSpeechStopped()
        capturedListener.onTextDelta("second")
        capturedListener.onTextDone("second")
        verify { callback.onCommitText("second") }
        // composing text should be fresh, not "firstsecond"
        verify { callback.onComposingText("second") }
    }

    @Test
    fun `error calls callback and cleans up`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        capturedListener.onError("server broke")
        verify { callback.onError("server broke") }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `cleanup disconnects and stops everything`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        controller.cleanup()
        verify { mockStreamer.stop() }
        verify { mockClient.disconnect() }
        assertEquals(RealtimeInputController.State.IDLE, controller.state)
    }

    @Test
    fun `amplitude returns streamer RMS`() {
        every { mockStreamer.currentRms } returns 0.42f
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()

        assertEquals(0.42f, controller.amplitude, 0.001f)
    }

    @Test
    fun `amplitude returns 0 when no streamer active`() {
        assertEquals(0f, controller.amplitude, 0.001f)
    }

    @Test
    fun `can start again after manual stop cycle`() {
        controller.start("key", "model", "inst")
        capturedListener.onSessionReady()
        controller.stop()
        capturedListener.onTextDone("test")
        capturedListener.onResponseDone()

        assertEquals(RealtimeInputController.State.IDLE, controller.state)

        // Should be able to start again
        controller.start("key", "model", "inst2")
        assertEquals(RealtimeInputController.State.CONNECTING, controller.state)
    }
}
