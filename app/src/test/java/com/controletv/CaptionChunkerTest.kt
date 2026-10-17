package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionChunkerTest {
    @Test
    fun doesNotTypeWordByWord() {
        val c = CaptionChunker()
        c.partial("hey", 0)
        c.partial("hey are", 100)
        c.partial("hey are you", 200)
        // A few words in quick succession are not shown yet
        assertEquals("", c.text(250))
    }

    @Test
    fun showsWholeSentenceWhenFinished() {
        val c = CaptionChunker()
        c.partial("hey are you", 0)
        c.final("hey are you coming with us tonight?", 900)
        assertEquals("Hey are you coming with us tonight?", c.text(1_000))
    }

    @Test
    fun longSpeechComesOutInBlocksEndingAtPunctuation() {
        val c = CaptionChunker()
        c.partial("I told you already, I can't. My brother's flight lands at nine and somebody", 0)
        // First block stops at the punctuation inside the first 12 words
        assertEquals("I told you already, I can't.", c.text(10))
        c.final("I told you already, I can't. My brother's flight lands at nine and somebody has to pick him up.", 3_000)
        // The rest of the sentence comes out whole, in two balanced lines
        assertEquals("My brother's flight lands at nine\nand somebody has to pick him up.", c.text(3_010))
    }

    @Test
    fun slowSpeakerStillGetsCaptions() {
        val c = CaptionChunker()
        c.partial("well I guess we could", 0)
        assertEquals("", c.text(100))
        c.partial("well I guess we could", 2_500)
        assertEquals("Well I guess we could", c.text(2_510))
    }

    @Test
    fun captionDisappearsAfterReadingTime() {
        val c = CaptionChunker()
        c.final("see you tomorrow", 0)
        assertEquals("See you tomorrow", c.text(1_000))
        assertEquals("", c.text(5_000))
    }

    @Test
    fun layoutKeepsTwoBalancedLines() {
        val lines = CaptionChunker.layout("This sentence is definitely longer than forty two characters").split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= CaptionChunker.LINE_CHARS })
        assertEquals("Short line", CaptionChunker.layout("Short line"))
    }
}
