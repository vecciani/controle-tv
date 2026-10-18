package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwardsTest {
    @Test
    fun oscarsAndTotals() {
        assertEquals("Indicado a 7 Oscars · 21 prêmios e 43 indicações",
            Awards.toPortuguese("Nominated for 7 Oscars. 21 wins & 43 nominations total"))
        assertEquals("Venceu 1 Oscar · 1 prêmio e 1 indicação",
            Awards.toPortuguese("Won 1 Oscar. 1 win & 1 nomination total"))
    }

    @Test
    fun otherPrizesAndShortForms() {
        assertEquals("Venceu 2 Emmys · 10 prêmios e 30 indicações",
            Awards.toPortuguese("Won 2 Primetime Emmys. 10 wins & 30 nominations total"))
        assertEquals("Indicado a 3 Globos de Ouro", Awards.toPortuguese("Nominated for 3 Golden Globes."))
        assertEquals("5 indicações", Awards.toPortuguese("5 nominations"))
        assertEquals("2 prêmios", Awards.toPortuguese("2 wins total"))
    }

    @Test
    fun emptyAndUnknown() {
        assertNull(Awards.toPortuguese(""))
        assertNull(Awards.toPortuguese("N/A"))
        assertEquals("Something else", Awards.toPortuguese("Something else."))
    }
}
