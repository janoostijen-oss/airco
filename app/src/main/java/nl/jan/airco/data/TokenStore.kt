package nl.jan.airco.data

import android.content.Context

/**
 * Bewaart de tokens in de privé-opslag van de app (niet leesbaar voor
 * andere apps, backup staat uit). Geen wachtwoord: dat zie de app nooit.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun load(): Tokens? {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return Tokens(
            accessToken = prefs.getString(KEY_ACCESS, "").orEmpty(),
            refreshToken = refresh,
            expiresAtMillis = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    fun save(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
    }
}
