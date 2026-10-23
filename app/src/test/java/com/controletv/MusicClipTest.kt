package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicClipTest {
    private fun song(id: String, title: String, channel: String) = YtMusicSearch.Song(id, title, "$channel • 1 mi de visualizações • 4:31", null)

    @Test
    fun picksTheOfficialClipLikeAuraMusic() {
        val results = listOf(
            song("mix", "Best of Coldplay Top Songs Playlist || Yellow", "Station F3"),
            song("lyrics", "Coldplay - Yellow (Lyrics)", "Lyric Channel"),
            song("legend", "Yellow - Coldplay - Subtitulada en español", "Start Dream"),
            song("live", "Coldplay - Yellow (Live on Letterman)", "Coldplay"),
            song("clip", "Yellow", "Coldplay"),
        )
        val picked = MusicClip.auraMusicClips("Yellow", "Coldplay") { results }
        assertEquals("clip", picked.first())
        assertTrue("live" !in picked)
        assertTrue("lyrics" !in picked)
        assertTrue("legend" !in picked)
    }

    @Test
    fun pageMutesAndStartsWhereTheSongIs() {
        val page = MusicClip.page("abcdefghijk", 42)
        assertTrue(page.contains("videoId:'abcdefghijk'"))
        assertTrue(page.contains("mute:1"))
        assertTrue(page.contains("start:42"))
    }
}
