package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>Canal Teste</title>
         <entry>
          <id>yt:video:RE3_PGwDwhw</id>
          <yt:videoId>RE3_PGwDwhw</yt:videoId>
          <yt:channelId>UCX6OQ3DkcsbYNE6H8uQQuVA</yt:channelId>
          <title>Bolo &amp; café</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=RE3_PGwDwhw"/>
          <author><name>Canal Teste</name></author>
          <published>2026-10-17T16:00:18+00:00</published>
          <media:group>
           <media:description>Linha 1
        Linha 2</media:description>
           <media:community><media:statistics views="1234567"/></media:community>
          </media:group>
         </entry>
         <entry>
          <yt:videoId>abcdefghijk</yt:videoId>
          <title>Um short</title>
          <link rel="alternate" href="https://www.youtube.com/shorts/abcdefghijk"/>
         </entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsVideosAndSkipsShorts() {
        val videos = YouTube.parseFeed(feed)
        assertEquals(1, videos.size)
        val v = videos[0]
        assertEquals("RE3_PGwDwhw", v.id)
        assertEquals("Bolo & café", v.title)
        assertEquals("Canal Teste", v.channel)
        assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", v.channelId)
        assertEquals("2026-10-17T16:00:18+00:00", v.published)
        assertEquals(1_234_567L, v.views)
        assertEquals("Linha 1\nLinha 2", v.description)
        assertNull(v.length)
    }

    @Test
    fun relativeDates() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse("2026-10-20T16:00:18+00:00")!!.time
        assertEquals("há 3 dias", YouTube.ago("2026-10-17T16:00:18+00:00", now))
        assertEquals("há 1 hora", YouTube.ago("2026-10-20T15:00:00+00:00", now))
        assertEquals("há 2 meses", YouTube.ago("2026-08-15T16:00:18+00:00", now))
        assertEquals("", YouTube.ago("ontem", now))
    }

    @Test
    fun viewCounts() {
        assertEquals("1,2 mi de visualizações", YouTube.viewsLabel(1_234_567))
        assertEquals("15 mil visualizações", YouTube.viewsLabel(15_300))
        assertEquals("2 mi de visualizações", YouTube.viewsLabel(2_000_000))
        assertEquals("1 visualização", YouTube.viewsLabel(1))
        assertEquals("950 visualizações", YouTube.viewsLabel(950))
    }
}
