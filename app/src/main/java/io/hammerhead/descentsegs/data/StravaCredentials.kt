package io.hammerhead.descentsegs.data

import android.content.Context
import androidx.core.content.edit

private const val PREFS = "strava_creds"

class StravaCredentials(private val ctx: Context) {
    private val p get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("client_id", "") ?: ""
        set(v) = p.edit { putString("client_id", v) }

    var clientSecret: String
        get() = p.getString("client_secret", "") ?: ""
        set(v) = p.edit { putString("client_secret", v) }

    var refreshToken: String
        get() = p.getString("refresh_token", "") ?: ""
        set(v) = p.edit { putString("refresh_token", v) }

    var accessToken: String
        get() = p.getString("access_token", "") ?: ""
        set(v) = p.edit { putString("access_token", v) }

    var accessTokenExpiry: Long
        get() = p.getLong("access_token_expiry", 0L)
        set(v) = p.edit { putLong("access_token_expiry", v) }

    fun isConfigured() = clientId.isNotBlank() && clientSecret.isNotBlank() && refreshToken.isNotBlank()

    fun isAccessTokenValid() =
        accessToken.isNotBlank() && System.currentTimeMillis() / 1000L < (accessTokenExpiry - 60)
}
