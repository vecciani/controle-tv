package com.controletv

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/**
 * A phone pairs once by sending the PIN shown on the TV and receives a token.
 * Every API call must carry that token, so nobody else on the Wi-Fi can control the TV.
 */
class Pairing(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    val pin: String
        get() = prefs.getString(KEY_PIN, null) ?: newPin().also {
            prefs.edit().putString(KEY_PIN, it).apply()
        }

    fun pair(candidate: String): String? {
        if (candidate != pin) return null
        val token = UUID.randomUUID().toString()
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet())!! + token
        prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
        return token
    }

    fun isValid(token: String?): Boolean =
        token != null && token in prefs.getStringSet(KEY_TOKENS, emptySet())!!

    /** Changes the PIN and disconnects every paired phone. */
    fun reset() {
        prefs.edit().putString(KEY_PIN, newPin()).remove(KEY_TOKENS).apply()
    }

    private fun newPin() = "%04d".format(SecureRandom().nextInt(10000))

    private companion object {
        const val KEY_PIN = "pin"
        const val KEY_TOKENS = "tokens"
    }
}
