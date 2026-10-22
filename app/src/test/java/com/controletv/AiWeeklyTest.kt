package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AiWeeklyTest {
    @Test
    fun weekStartsOnMonday() {
        fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { set(y, m - 1, d) }
        assertEquals("2026-W43", AiWeekly.weekKey(day(2026, 10, 19)))  // Monday
        assertEquals("2026-W43", AiWeekly.weekKey(day(2026, 10, 25)))  // Sunday
        assertEquals("2026-W44", AiWeekly.weekKey(day(2026, 10, 26)))  // next Monday
        assertEquals("2026-W53", AiWeekly.weekKey(day(2027, 1, 1)))    // ISO: still 2026's last week
    }

    @Test
    fun promptCarriesTasteAndExclusions() {
        val p = AiWeekly.prompt("suspense coreano", listOf("Parasita", "Oldboy"), listOf("Mother"))
        assertTrue(p.contains("suspense coreano"))
        assertTrue(p.contains("Parasita; Oldboy"))
        assertTrue(p.contains("Mother"))
        assertTrue(p.contains("array JSON"))
    }

    @Test
    fun readsTheAnswerEvenWithFences() {
        val answer = """```json
            [{"title":"The Handmaiden","year":"2016","type":"movie","why":"Suspense coreano elegante.","imdbId":"tt4016934"},
             {"title":"Dark","year":"2017–2020","type":"Série","why":"Mistério que faz pensar.","imdbId":"talvez"},
             {"title":"","year":"2000","type":"movie","why":"sem título"}]
            ```"""
        val list = AiWeekly.parse(answer)
        assertEquals(2, list.size)
        assertEquals("tt4016934", list[0].imdbId)
        assertEquals("movie", list[0].type)
        assertEquals("series", list[1].type)
        assertEquals("2017", list[1].year)
        assertNull(list[1].imdbId)
    }

    @Test
    fun nothingFromGarbage() {
        assertTrue(AiWeekly.parse("Desculpe, não posso ajudar").isEmpty())
    }

    @Test
    fun picksAFastTextModel() {
        val ranked = AiWeekly.rankModels(listOf(
            "gemini-2.0-flash", "gemini-2.5-pro", "gemini-2.5-flash-image", "gemini-3-flash", "gemini-2.5-flash-lite",
            "gemini-flash-latest", "embedding-001", "gemini-2.5-flash-preview-tts",
        ))
        assertEquals("gemini-flash-latest", ranked.first())
        assertEquals("gemini-3-flash", ranked[1])
        assertTrue("gemini-2.5-flash-image" !in ranked)
        assertTrue("embedding-001" !in ranked)
        assertTrue(ranked.indexOf("gemini-2.5-pro") > ranked.indexOf("gemini-2.0-flash"))
    }
}
