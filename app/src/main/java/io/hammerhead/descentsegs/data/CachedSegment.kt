package io.hammerhead.descentsegs.data

import kotlinx.serialization.Serializable

@Serializable
data class CachedSegment(
    val id: Long,
    val name: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val distanceMetres: Double,
    val prSeconds: Int?,
    val komSeconds: Int?,
)

@Serializable
data class SegmentCache(
    val segments: List<CachedSegment> = emptyList(),
    val lastFetchedEpochMillis: Long = 0L,
)
