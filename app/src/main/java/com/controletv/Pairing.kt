package com.controletv

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/**
 * A phone pairs once and receives a token that every API call must carry. When
 * [requirePin] is on, pairing needs the PIN shown on the TV, so nobody else on a
 * shared Wi-Fi can take control.
 */
class Pairing(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    val pin: String
        get() = prefs.getString(KEY_PIN, null) ?: newPin().also {
            prefs.edit().putString(KEY_PIN, it).apply()
        }

    /** Off by default: on a private network (like the phone's hotspot) any phone connects directly. */
    var requirePin: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_PIN, false)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_PIN, value).apply()

    fun pair(candidate: String): String? {
        if (requirePin && candidate != pin) return null
        val token = UUID.randomUUID().toString()
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet())!! + token
        prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
        return token
    }

    fun isValid(token: String?): Boolean =
        token != null && (token == localKey || token in prefs.getStringSet(KEY_TOKENS, emptySet())!!)

    /** Secret only the TV's own screen knows, so its page can use TV-only endpoints. */
    val localKey: String
        get() = prefs.getString(KEY_LOCAL, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_LOCAL, it).apply()
        }

    fun hasPhones() = prefs.getStringSet(KEY_TOKENS, emptySet())!!.isNotEmpty()

    /** Changes the PIN and disconnects every paired phone. */
    fun reset() {
        prefs.edit().putString(KEY_PIN, newPin()).remove(KEY_TOKENS).apply()
    }

    private fun newPin() = "%04d".format(SecureRandom().nextInt(10000))

    private companion object {
        const val KEY_PIN = "pin"
        const val KEY_TOKENS = "tokens"
        const val KEY_LOCAL = "local"
        const val KEY_REQUIRE_PIN = "requirePin"
    }
}
