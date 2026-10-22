package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiscoverTest {
    private fun title(id: String, rating: Double, minutes: Int = 110, genres: List<String> = listOf("Drama"), country: String = "France") =
        Discover.Title(id, "movie", id, rating, minutes, genres, country, "2019", null)

    private val any = Discover.Choice(mood = "qualquer", time = "qualquer", company = "sozinho", far = false, type = "movie")

    @Test
    fun leavesOutObviousSeenAndLowRated() {
        val titles = listOf(title("famoso", 8.5), title("visto", 8.0), title("fraco", 6.2), title("joia", 7.6))
        val picked = Discover.pick(titles, any, exclude = setOf("famoso", "visto"), random = Random(1))
        assertEquals(listOf("joia"), picked.map { it.id })
    }

    @Test
    fun respectsTimeAndPlace() {
        val titles = listOf(
            title("longo", 7.8, minutes = 165),
            title("curto-eua", 7.5, minutes = 95, country = "United States"),
            title("curto-coreia", 7.4, minutes = 98, country = "South Korea"),
        )
        val short = Discover.pick(titles, any.copy(time = "curto"), emptySet(), Random(1)).map { it.id }.toSet()
        assertEquals(setOf("curto-eua", "curto-coreia"), short)
        val far = Discover.pick(titles, any.copy(time = "curto", far = true), emptySet(), Random(1)).map { it.id }
        assertEquals(listOf("curto-coreia"), far)
    }

    @Test
    fun familyNeedsFamilyGenresAndNoHorror() {
        val titles = listOf(
            title("animacao", 7.9, genres = listOf("Animation", "Comedy")),
            title("drama", 8.1, genres = listOf("Drama")),
            title("susto", 7.5, genres = listOf("Family", "Horror")),
        )
        val picked = Discover.pick(titles, any.copy(company = "familia"), emptySet(), Random(1)).map { it.id }
        assertEquals(listOf("animacao"), picked)
    }

    @Test
    fun drawsDiffer() {
        val titles = (1..40).map { title("t$it", 7.0 + it / 20.0) }
        val a = Discover.pick(titles, any, emptySet(), Random(1)).take(3).map { it.id }
        val b = Discover.pick(titles, any, emptySet(), Random(2)).take(3).map { it.id }
        assertTrue(a != b)
        assertEquals(30, Discover.pick(titles, any, emptySet(), Random(3)).size)
    }

    @Test
    fun explainsWhy() {
        val why = Discover.why(title("x", 7.8, minutes = 114, country = "South Korea, United States"))
        assertEquals("IMDb 7,8 · pouco conhecido · 1h54 · Coreia do Sul · 2019", why)
        assertFalse(Discover.country("United States").isNullOrEmpty())
    }
}
