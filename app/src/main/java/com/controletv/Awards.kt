package com.controletv

/**
 * Puts the awards line from the film catalog (in English, from IMDb) into Portuguese:
 * "Won 3 Oscars. 45 wins & 120 nominations total" → "Venceu 3 Oscars · 45 prêmios e 120 indicações".
 * Anything it doesn't recognize is left as it came.
 */
object Awards {
    private val prizes = listOf(
        "Oscar" to "Oscar",
        "Primetime Emmy" to "Emmy",
        "Emmy" to "Emmy",
        "Golden Globe" to "Globo de Ouro",
        "BAFTA Film Award" to "BAFTA",
        "BAFTA Award" to "BAFTA",
        "BAFTA" to "BAFTA",
    )

    fun toPortuguese(raw: String?): String? {
        val text = raw?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() && it != "N/A" } ?: return null
        return text.split(Regex("\\.\\s*")).filter { it.isNotBlank() }.joinToString(" · ") { sentence(it.trim()) }
    }

    private fun sentence(s: String): String {
        Regex("(?i)^(won|nominated for) (\\d+) (.+?)s?$").find(s)?.let { m ->
            val (verb, count, prize) = m.destructured
            val name = prizes.firstOrNull { prize.equals(it.first, true) }?.second ?: return s
            val n = count.toInt()
            val plural = if (n > 1 && name != "Globo de Ouro") "${name}s" else if (n > 1) "Globos de Ouro" else name
            return if (verb.equals("won", true)) "Venceu $n $plural" else "Indicado a $n $plural"
        }
        Regex("(?i)^(\\d+) wins? & (\\d+) nominations?( total)?$").find(s)?.let { m ->
            val (wins, noms) = m.destructured
            return "${count(wins.toInt(), "prêmio", "prêmios")} e ${count(noms.toInt(), "indicação", "indicações")}"
        }
        Regex("(?i)^(\\d+) wins?( total)?$").find(s)?.let { return count(it.groupValues[1].toInt(), "prêmio", "prêmios") }
        Regex("(?i)^(\\d+) nominations?( total)?$").find(s)?.let { return count(it.groupValues[1].toInt(), "indicação", "indicações") }
        return s
    }

    private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
