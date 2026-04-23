package io.hammerhead.descentsegs.data

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "StravaApi"

class StravaApiClient(private val creds: StravaCredentials) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun ensureValidToken(): String {
        if (creds.isAccessTokenValid()) return creds.accessToken
        Log.d(TAG, "Refreshing token")
        val body = FormBody.Builder()
            .add("client_id", creds.clientId)
            .add("client_secret", creds.clientSecret)
            .add("refresh_token", creds.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url("https://www.strava.com/oauth/token").post(body).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Token refresh failed: ${resp.code}" }
            val json = JSONObject(resp.body!!.string())
            creds.accessToken = json.getString("access_token")
            creds.refreshToken = json.getString("refresh_token")
            creds.accessTokenExpiry = json.getLong("expires_at")
        }
        return creds.accessToken
    }

    fun fetchStarredDescentSegments(): List<CachedSegment> {
        val token = ensureValidToken()
        val result = mutableListOf<CachedSegment>()
        var page = 1
        while (true) {
            val req = Request.Builder()
                .url("https://www.strava.com/api/v3/segments/starred?page=$page&per_page=100")
                .header("Authorization", "Bearer $token")
                .build()
            val arr = http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Starred fetch failed: ${resp.code}" }
                JSONArray(resp.body!!.string())
            }
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                parseSegment(arr.getJSONObject(i), token)?.let { result.add(it) }
            }
            if (arr.length() < 100) break
            page++
        }
        Log.d(TAG, "Found ${result.size} descent segments")
        return result
    }

    private fun parseSegment(seg: JSONObject, token: String): CachedSegment? {
        return try {
            if (seg.optDouble("average_grade", 0.0) >= 0.0) return null
            val start = seg.getJSONArray("start_latlng")
            val end = seg.getJSONArray("end_latlng")
            if (start.length() < 2 || end.length() < 2) return null
            val id = seg.getLong("id")
            val detail = fetchSegmentDetail(id, token)

            // Try every known location Strava puts KOM time
            val komSeconds = extractKom(detail) ?: extractKom(seg)

            val prSeconds = detail?.optJSONObject("athlete_segment_stats")
                ?.optInt("pr_elapsed_time", -1)?.takeIf { it > 0 }
                ?: seg.optJSONObject("athlete_segment_stats")
                    ?.optInt("pr_elapsed_time", -1)?.takeIf { it > 0 }

            Log.d(TAG, "Segment '${seg.optString("name")}' KOM=$komSeconds PR=$prSeconds " +
                "xoms=${detail?.optJSONObject("xoms")?.toString()}")

            CachedSegment(
                id = id,
                name = seg.getString("name"),
                startLat = start.getDouble(0),
                startLng = start.getDouble(1),
                endLat = end.getDouble(0),
                endLng = end.getDouble(1),
                distanceMetres = seg.getDouble("distance"),
                prSeconds = prSeconds,
                komSeconds = komSeconds,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip segment: ${e.message}")
            null
        }
    }

    private fun extractKom(json: JSONObject?): Int? {
        if (json == null) return null
        val xoms = json.optJSONObject("xoms") ?: return null
        // Try string format "6:34"
        val komStr = xoms.optString("kom", "")
        if (komStr.isNotBlank() && komStr != "null") {
            parseTimeString(komStr)?.let { return it }
        }
        // Try overall effort elapsed_time
        xoms.optJSONObject("overall")
            ?.optInt("elapsed_time", -1)
            ?.takeIf { it > 0 }
            ?.let { return it }
        // Try kom_efforts
        json.optJSONObject("kom_efforts")
            ?.optJSONObject("overall")
            ?.optInt("elapsed_time", -1)
            ?.takeIf { it > 0 }
            ?.let { return it }
        return null
    }

    private fun fetchSegmentDetail(segId: Long, token: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url("https://www.strava.com/api/v3/segments/$segId")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body!!.string())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch segment $segId: ${e.message}")
            null
        }
    }

    private fun parseTimeString(t: String): Int? {
        return try {
            val parts = t.trim().split(":").map { it.trim().toInt() }
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> null
            }
        } catch (e: Exception) { null }
    }
}
