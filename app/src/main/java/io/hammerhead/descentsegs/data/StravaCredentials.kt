package io.hammerhead.descentsegs.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

private const val TAG = "StravaCreds"
private const val PREFS = "strava_creds"
// File on shared storage that survives reinstalls:
// /sdcard/DescentSegments/credentials.txt
private const val CREDS_FILE_PATH = "DescentSegments/credentials.txt"

class StravaCredentials(private val ctx: Context) {

    private val p get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Try external file first, fall back to SharedPreferences
    private fun getFromFile(key: String): String {
        return try {
            val file = File(Environment.getExternalStorageDirectory(), CREDS_FILE_PATH)
            if (!file.exists()) return ""
            file.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
                ?.removePrefix("$key=")
                ?.trim()
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Could not read credentials file: ${e.message}")
            ""
        }
    }

    var clientId: String
        get() = getFromFile("client_id").ifBlank { p.getString("client_id", "") ?: "" }
        set(v) = p.edit().putString("client_id", v).apply()

    var clientSecret: String
        get() = getFromFile("client_secret").ifBlank { p.getString("client_secret", "") ?: "" }
        set(v) = p.edit().putString("client_secret", v).apply()

    var refreshToken: String
        get() = getFromFile("refresh_token").ifBlank { p.getString("refresh_token", "") ?: "" }
        set(v) = p.edit().putString("refresh_token", v).apply()

    var accessToken: String
        get() = p.getString("access_token", "") ?: ""
        set(v) = p.edit().putString("access_token", v).apply()

    var accessTokenExpiry: Long
        get() = p.getLong("access_token_expiry", 0L)
        set(v) = p.edit().putLong("access_token_expiry", v).apply()

    fun isConfigured() = clientId.isNotBlank() && clientSecret.isNotBlank() && refreshToken.isNotBlank()

    fun isAccessTokenValid() =
        accessToken.isNotBlank() && System.currentTimeMillis() / 1000L < (accessTokenExpiry - 60)

    fun credentialsFilePath(): String =
        File(Environment.getExternalStorageDirectory(), CREDS_FILE_PATH).absolutePath
}
