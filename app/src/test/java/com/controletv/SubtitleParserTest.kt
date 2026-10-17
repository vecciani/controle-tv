package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {
    @Test
    fun srt() {
        val captions = SubtitleParser.parse(
            "1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i> there\r\nsecond line\r\n\r\n" +
                "2\r\n01:02:03,040 --> 01:02:04,000\r\nBye\r\n"
        )
        assertEquals(2, captions.size)
        assertEquals(Caption(1_500, 3_000, "Hello there\nsecond line"), captions[0])
        assertEquals(3_723_040L, captions[1].start)
    }

    @Test
    fun vttWithoutHours() {
        val captions = SubtitleParser.parse("WEBVTT\n\n00:05.250 --> 00:07.000 align:start\nHi\n\nNOTE x\n\n01:00.5 --> 01:02.000\nLater\n")
        assertEquals(listOf(Caption(5_250, 7_000, "Hi"), Caption(60_500, 62_000, "Later")), captions)
    }

    @Test
    fun ass() {
        val captions = SubtitleParser.parse(
            "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,{\\i1}One, two{\\i0}\\Nthree\n"
        )
        assertEquals(listOf(Caption(2_500, 4_000, "One, two\nthree")), captions)
    }

    @Test
    fun skipsEmptyAndSorts() {
        val captions = SubtitleParser.parse("2\n00:00:05,000 --> 00:00:06,000\nB\n\n1\n00:00:01,000 --> 00:00:02,000\nA\n\n3\n00:00:07,000 --> 00:00:08,000\n\n")
        assertEquals(listOf("A", "B"), captions.map { it.text })
    }
}
