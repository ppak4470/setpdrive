package kr.stepdrive.app

import kr.stepdrive.app.speech.CommandParser
import kr.stepdrive.app.speech.VoiceCommand
import kotlin.test.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun negationDoesNotAdvanceParking() {
        for (text in listOf("다음으로 넘어가지 마", "다음 말고 다시", "다시 하지 마", "출발하지 마")) {
            assertNull(CommandParser.parse(text), text)
        }
    }
    @Test fun unrelatedSpeechDoesNotBecomeYesOrNext() {
        for (text in listOf("어디야", "네비게이션", "다음 주에 만나", "시작점이 어디야")) {
            assertNull(CommandParser.parse(text), text)
        }
    }
    @Test fun supportedShortCommandsAreRecognized() {
        assertEquals(VoiceCommand.NEXT, CommandParser.parse("다음 단계"))
        assertEquals(VoiceCommand.REPEAT, CommandParser.parse("다시 말해줘"))
        assertEquals(VoiceCommand.HELP, CommandParser.parse("도와줘"))
    }
}
