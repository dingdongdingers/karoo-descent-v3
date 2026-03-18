package io.hammerhead.descentsegs.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SegmentRepository(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(ctx.filesDir, "segment_cache.json")

    fun save(cache: SegmentCache) {
        try { file.writeText(json.encodeToString(cache)) }
        catch (e: Exception) { Log.e("SegRepo", "Save failed: ${e.message}") }
    }

    fun load(): SegmentCache = try {
        if (file.exists()) json.decodeFromString(file.readText()) else SegmentCache()
    } catch (e: Exception) { SegmentCache() }

    fun getSegments() = load().segments
}
