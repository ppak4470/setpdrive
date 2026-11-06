package kr.stepdrive.core

import kr.stepdrive.core.hazard.KoRoadParser
import kotlin.test.*

class KoRoadParserTest {
    private val item = """{"afos_fid":"123","spot_nm":"검증지점","la_crd":"37.5","lo_crd":"127.0","occrrnc_cnt":"3","caslt_cnt":4}"""
    @Test fun singleAndArrayRecordsParseThePublishedFields() {
        for (records in listOf(item, "[$item]")) {
            val page = KoRoadParser.parse("""{"resultCode":"00","totalCount":1,"items":{"item":$records}}""", 2024)
            val h = page.items.single()
            assertEquals("123", h.id); assertEquals(3, h.accidentCount); assertEquals(150f, h.radiusM)
        }
    }
    @Test fun nestedEnvelopeAndEmptySuccessAreAccepted() {
        val page = KoRoadParser.parse("""{"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":[$item]}}}}""", 2024)
        assertEquals(1, page.items.size)
        assertTrue(KoRoadParser.parse("""{"resultCode":"00","totalCount":0,"items":""}""", 2024).items.isEmpty())
    }
    @Test fun serviceErrorsAndHtmlDoNotBecomeAnEmptySuccessfulDownload() {
        for (body in listOf("<html>error</html>", """{"resultCode":"30","totalCount":0}""", """{"totalCount":0}"""))
            assertFails { KoRoadParser.parse(body, 2024) }
    }
    @Test fun missingRecordsInvalidCoordinatesAndNegativeCountsAreRejected() {
        val bodies = listOf("""{"resultCode":"00","totalCount":1}""",
            """{"resultCode":"00","totalCount":1,"items":{"item":${item.replace("37.5", "0.0")}}}""",
            """{"resultCode":"00","totalCount":1,"items":{"item":${item.replace("\"3\"", "\"-1\"")}}}""")
        bodies.forEach { assertFails { KoRoadParser.parse(it, 2024) } }
    }
}
