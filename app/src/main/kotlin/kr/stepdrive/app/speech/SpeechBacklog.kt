package kr.stepdrive.app.speech

/** Bounded pre-initialization queue; old guidance must never be replayed after a long connection delay. */
internal class SpeechBacklog(private val clock: () -> Long = System::currentTimeMillis) {
    data class Line(val text: String, val urgent: Boolean, val atMs: Long)
    private val lines = ArrayDeque<Line>()
    @Synchronized fun add(text: String, urgent: Boolean) {
        if (urgent) lines.clear()
        while (lines.size >= 16) lines.removeFirst()
        lines.addLast(Line(text, urgent, clock()))
    }
    @Synchronized fun drain(): List<Line> = lines.filter { clock() - it.atMs in 0..8_000 }.also { lines.clear() }
    @Synchronized fun clear() = lines.clear()
}
