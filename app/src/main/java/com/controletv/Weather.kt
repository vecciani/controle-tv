package com.controletv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather for the TV home: where the TV is (roughly, from its internet address) and the
 * current conditions and today's range from Open-Meteo (free, no account or key).
 */
object Weather {
    /** One of the next days: weekday ("seg"), its weather and range. */
    data class Day(val weekday: String, val code: Int, val min: Int, val max: Int)

    data class Now(val city: String, val temp: Int, val min: Int, val max: Int, val code: Int, val day: Boolean, val next: List<Day>) {
        val label get() = label(code)
        val icon get() = icon(code, day)
    }

    @Volatile private var place: Triple<String, Double, Double>? = null
    @Volatile private var placeAt = 0L
    @Volatile private var cached: Now? = null
    @Volatile private var cachedAt = 0L

    /** Current weather, refreshed every 20 minutes; null when it can't be had. Blocking. */
    fun now(): Now? {
        if (cached != null && System.currentTimeMillis() - cachedAt < 20 * 60_000L) return cached
        return runCatching {
            val (city, lat, lon) = location()
            val json = JSONObject(get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5",
            ))
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            Now(
                city = city,
                temp = Math.round(current.getDouble("temperature_2m")).toInt(),
                min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                code = current.getInt("weather_code"),
                day = current.optInt("is_day", 1) == 1,
                next = (1 until daily.getJSONArray("time").length()).map { i ->
                    Day(
                        weekday = weekday(daily.getJSONArray("time").getString(i)),
                        code = daily.getJSONArray("weather_code").getInt(i),
                        min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                        max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                    )
                },
            )
        }.getOrNull()?.also { cached = it; cachedAt = System.currentTimeMillis() } ?: cached
    }

    /** City and coordinates from the internet address, kept for 12 hours. */
    private fun location(): Triple<String, Double, Double> {
        place?.let { if (System.currentTimeMillis() - placeAt < 12 * 3600_000L) return it }
        val json = JSONObject(get("http://ip-api.com/json/?fields=status,city,lat,lon&lang=pt-BR"))
        if (json.optString("status") != "success") throw IllegalStateException("Sem localização")
        return Triple(json.optString("city"), json.getDouble("lat"), json.getDouble("lon")).also { place = it; placeAt = System.currentTimeMillis() }
    }

    /** "2026-10-26" → "seg". */
    fun weekday(date: String): String = runCatching {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!
        java.text.SimpleDateFormat("EEE", java.util.Locale("pt", "BR")).format(d).removeSuffix(".").lowercase()
    }.getOrDefault("")

    /** WMO weather codes in Portuguese. */
    fun label(code: Int) = when (code) {
        0 -> "Céu limpo"
        1 -> "Quase limpo"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Neblina"
        51, 53, 55, 56, 57 -> "Garoa"
        61, 63, 66 -> "Chuva"
        65, 67 -> "Chuva forte"
        71, 73, 75, 77 -> "Neve"
        80, 81 -> "Pancadas de chuva"
        82 -> "Pancadas fortes"
        85, 86 -> "Pancadas de neve"
        95, 96, 99 -> "Tempestade"
        else -> "Tempo"
    }

    fun icon(code: Int, day: Boolean) = when (code) {
        0, 1 -> if (day) R.drawable.ic_w_sun else R.drawable.ic_w_moon
        2 -> if (day) R.drawable.ic_w_cloud_sun else R.drawable.ic_w_cloud_moon
        3 -> R.drawable.ic_w_cloud
        45, 48 -> R.drawable.ic_w_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_w_snow
        95, 96, 99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloud
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
        return conn.inputStream.use { it.readBytes().decodeToString() }.also { conn.disconnect() }
    }
}
