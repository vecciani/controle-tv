package com.controletv

/** One subtitle line on screen between [start] and [end] (milliseconds). */
data class Caption(val start: Long, val end: Long, val text: String)

/**
 * Reads .srt, .vtt and .ass/.ssa into a sorted list of captions, so the player can draw them
 * itself and shift them in time (to fix out-of-sync subtitles) without reloading the video.
 */
object SubtitleParser {
    private val TIMING = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})"""
    )
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<Caption> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val captions = if ("[Script Info]" in clean || "\nDialogue:" in clean) parseAss(clean) else parseTimed(clean)
        return captions.filter { it.text.isNotBlank() && it.end > it.start }.sortedBy { it.start }
    }

    /** SRT and WebVTT: a timing line followed by text lines, blocks separated by blank lines. */
    private fun parseTimed(text: String): List<Caption> {
        val result = mutableListOf<Caption>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val match = TIMING.find(lines[i])
            if (match == null) { i++; continue }
            val g = match.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            val body = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank() && TIMING.find(lines[i]) == null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].replace(TAGS, "").trim())
                i++
            }
            result += Caption(start, end, body.toString())
        }
        return result
    }

    /** ASS/SSA: "Dialogue: layer,start,end,style,name,marginL,marginR,marginV,effect,text". */
    private fun parseAss(text: String): List<Caption> = text.lines()
        .filter { it.startsWith("Dialogue:") }
        .mapNotNull { line ->
            val parts = line.removePrefix("Dialogue:").split(',', limit = 10)
            if (parts.size < 10) return@mapNotNull null
            val start = assTime(parts[1].trim()) ?: return@mapNotNull null
            val end = assTime(parts[2].trim()) ?: return@mapNotNull null
            Caption(start, end, parts[9].replace("\\N", "\n").replace("\\n", "\n").replace(TAGS, "").trim())
        }

    private fun assTime(value: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,3})""").matchEntire(value) ?: return null
        return ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        // ".5" means 500 ms, ".50" means 500 ms, ".500" means 500 ms
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((h.ifEmpty { "0" }.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
