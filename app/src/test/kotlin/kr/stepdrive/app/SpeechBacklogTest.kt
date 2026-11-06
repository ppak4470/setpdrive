package kr.stepdrive.app

import kr.stepdrive.app.speech.SpeechBacklog
import org.junit.Test
import kotlin.test.*

class SpeechBacklogTest {
    @Test fun expiredGuidanceIsDroppedAfterDelayedInitialization() {
        var now = 0L
        val queue = SpeechBacklog { now }
        queue.add("old turn", false)
        now = 9_000
        queue.add("current turn", false)
        assertEquals(listOf("current turn"), queue.drain().map { it.text })
    }
    @Test fun urgentGuidanceReplacesPendingRoutineSpeech() {
        val queue = SpeechBacklog { 1_000 }
        queue.add("routine", false); queue.add("warning", true)
        assertEquals(listOf("warning"), queue.drain().map { it.text })
    }
    @Test fun stoppingBeforeInitializationPreventsLaterPlayback() {
        val queue = SpeechBacklog { 1_000 }
        queue.add("cancelled practice", false); queue.clear()
        assertTrue(queue.drain().isEmpty())
    }
    @Test fun unavailableEngineCannotAccumulateAnUnboundedQueue() {
        val queue = SpeechBacklog { 1_000 }
        repeat(100) { queue.add("line-$it", false) }
        val lines = queue.drain()
        assertEquals(16, lines.size)
        assertEquals("line-99", lines.last().text)
    }
}
