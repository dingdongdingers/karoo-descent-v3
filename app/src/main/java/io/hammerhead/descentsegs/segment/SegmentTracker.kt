package io.hammerhead.descentsegs.segment

import io.hammerhead.descentsegs.data.CachedSegment

private const val TRIGGER_RADIUS_M = 25.0
private const val FINISH_RADIUS_M = 40.0

enum class SegmentState { IDLE, ACTIVE, FINISHED }

data class SegmentStatus(
    val state: SegmentState = SegmentState.IDLE,
    val segment: CachedSegment? = null,
    val elapsedSeconds: Int = 0,
    /** Negative = ahead of PR, positive = behind PR, null = no PR */
    val deltaVsPrSeconds: Int? = null,
)

class SegmentTracker {
    private var state = SegmentState.IDLE
    private var activeSegment: CachedSegment? = null
    private var startTimeMs = 0L

    fun onLocation(
        lat: Double,
        lng: Double,
        nowMs: Long,
        segments: List<CachedSegment>,
    ): SegmentStatus {
        when (state) {
            SegmentState.IDLE, SegmentState.FINISHED -> {
                val nearby = segments.firstOrNull { seg ->
                    haversineMetres(lat, lng, seg.startLat, seg.startLng) <= TRIGGER_RADIUS_M
                }
                if (nearby != null) {
                    state = SegmentState.ACTIVE
                    activeSegment = nearby
                    startTimeMs = nowMs
                }
            }
            SegmentState.ACTIVE -> {
                val seg = activeSegment ?: run { state = SegmentState.IDLE; return SegmentStatus() }
                val elapsed = ((nowMs - startTimeMs) / 1000L).toInt()
                if (haversineMetres(lat, lng, seg.endLat, seg.endLng) <= FINISH_RADIUS_M) {
                    state = SegmentState.FINISHED
                }
                return SegmentStatus(
                    state = state,
                    segment = seg,
                    elapsedSeconds = elapsed,
                    deltaVsPrSeconds = seg.prSeconds?.let { elapsed - it },
                )
            }
        }
        return SegmentStatus(state = state, segment = activeSegment)
    }

    fun reset() {
        state = SegmentState.IDLE
        activeSegment = null
        startTimeMs = 0L
    }
}
