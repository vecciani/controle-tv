package com.controletv

/**
 * Turns live speech recognition (text that grows word by word and gets revised) into
 * film-style subtitles: whole blocks of up to [MAX_WORDS] words, laid out in at most two
 * lines, each kept on screen long enough to read.
 *
 * Pure logic (no Android), so it's unit-tested; times are passed in milliseconds.
 */
class CaptionChunker {
    private var shownWords = 0          // words of the current utterance already shown
    private var block = ""              // what's on screen
    private var blockAt = 0L            // when it appeared
    private var blockUntil = 0L         // when it may disappear

    /** The recognizer's running guess for the current utterance. */
    fun partial(text: String, now: Long) {
        val pending = words(text).drop(shownWords)
        // Show a block when there's a full one, or when the speaker is slow and a few words are waiting
        val slow = now - blockAt > SLOW_MS && pending.size >= MIN_WORDS
        if (pending.size >= MAX_WORDS || slow) {
            val take = cut(pending)
            show(pending.take(take), now)
            shownWords += take
        }
    }

    /** The recognizer finished the utterance: show what's left of it. */
    fun final(text: String, now: Long) {
        var pending = words(text).drop(shownWords)
        // A slightly long leftover fits in one two-line block; a much longer one is trimmed to its
        // last block (never leaving a lonely word or two at the end)
        while (pending.size > MAX_WORDS + OVERFLOW_WORDS) pending = pending.drop(cut(pending))
        if (pending.isNotEmpty()) show(pending, now)
        shownWords = 0
    }

    /** Recognizer reset (seek, new video). */
    fun clear() {
        shownWords = 0
        block = ""
        blockUntil = 0
    }

    /** What to draw now (empty when nothing should be on screen). */
    fun text(now: Long): String = if (now <= blockUntil) block else ""

    private fun show(words: List<String>, now: Long) {
        val sentence = words.joinToString(" ").replaceFirstChar { it.uppercase() }
        block = layout(sentence)
        blockAt = now
        // Long enough to read (about 3 words per second), never shorter than 1.5 s
        blockUntil = now + maxOf(MIN_SHOW_MS, words.size * MS_PER_WORD) + LINGER_MS
    }

    /** How many words to put in the next block: end at punctuation if there's one in a good spot. */
    private fun cut(pending: List<String>): Int {
        val limit = minOf(MAX_WORDS, pending.size)
        for (i in limit downTo MIN_WORDS) {
            if (pending[i - 1].last() in ".?!,;:") return i
        }
        return limit
    }

    companion object {
        const val MAX_WORDS = 12
        const val MIN_WORDS = 4
        private const val OVERFLOW_WORDS = 3
        const val LINE_CHARS = 42
        private const val SLOW_MS = 2_200L
        private const val MIN_SHOW_MS = 1_500L
        private const val MS_PER_WORD = 330L
        private const val LINGER_MS = 600L

        private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** Splits a sentence into at most two balanced lines, breaking between words. */
        fun layout(sentence: String): String {
            if (sentence.length <= LINE_CHARS) return sentence
            val middle = sentence.length / 2
            val before = sentence.lastIndexOf(' ', middle)
            val after = sentence.indexOf(' ', middle)
            val at = listOf(before, after).filter { it > 0 }.minByOrNull { kotlin.math.abs(it - middle) } ?: return sentence
            return sentence.substring(0, at) + "\n" + sentence.substring(at + 1)
        }
    }
}
