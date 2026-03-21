package io.hammerhead.descentsegs.segment

import io.hammerhead.descentsegs.data.CachedSegment

private const val APPROACH_RADIUS_M = 300.0
private const val TRIGGER_RADIUS_M = 15.0
private const val FINISH_RADIUS_M = 40.0

enum class SegmentState { IDLE, APPROACHING, ACTIVE, FINISHED }

data class SegmentStatus(
    val state: SegmentState = SegmentState.IDLE,
    val segment: CachedSegment? = null,
    val elapsedSeconds: Int = 0,
    val distanceToStartMetres: Int = 0,
    val deltaVsPrSeconds: Int? = null,
    val triggerBeep: Boolean = false,
)

class SegmentTracker {
    private var state = SegmentState.IDLE
    private var activeSegment: CachedSegment? = null
    private var startTimeMs = 0L
    private var beepedForSegmentId: Long? = null

    fun onLocation(
        lat: Double,
        lng: Double,
        nowMs: Long,
        segments: List<CachedSegment>,
    ): SegmentStatus {
        when (state) {
            SegmentState.IDLE, SegmentState.FINISHED -> {
                // Find nearest segment start
                val nearest = segments.minByOrNull { seg ->
                    haversineMetres(lat, lng, seg.startLat, seg.startLng)
                } ?: return SegmentStatus(state = state)

                val dist = haversineMetres(lat, lng, nearest.startLat, nearest.startLng)

                return when {
                    dist <= TRIGGER_RADIUS_M -> {
                        state = SegmentState.ACTIVE
                        activeSegment = nearest
                        startTimeMs = nowMs
                        beepedForSegmentId = null
                        buildStatus(nearest, nowMs, dist)
                    }
                    dist <= APPROACH_RADIUS_M -> {
                        state = SegmentState.APPROACHING
                        activeSegment = nearest
                        val shouldBeep = beepedForSegmentId != nearest.id
                        if (shouldBeep) beepedForSegmentId = nearest.id
                        buildStatus(nearest, nowMs, dist, triggerBeep = shouldBeep)
                    }
                    else -> {
                        if (state == SegmentState.APPROACHING) {
                            state = SegmentState.IDLE
                            activeSegment = null
                        }
                        SegmentStatus(state = SegmentState.IDLE)
                    }
                }
            }

            SegmentState.APPROACHING -> {
                val seg = activeSegment ?: run { state = SegmentState.IDLE; return SegmentStatus() }
                val dist = haversineMetres(lat, lng, seg.startLat, seg.startLng)
                return when {
                    dist <= TRIGGER_RADIUS_M -> {
                        state = SegmentState.ACTIVE
                        startTimeMs = nowMs
                        buildStatus(seg, nowMs, dist)
                    }
                    dist <= APPROACH_RADIUS_M -> buildStatus(seg, nowMs, dist)
                    else -> {
                        state = SegmentState.IDLE
                        activeSegment = null
                        beepedForSegmentId = null
                        SegmentStatus(state = SegmentState.IDLE)
                    }
                }
            }

            SegmentState.ACTIVE -> {
                val seg = activeSegment ?: run { state = SegmentState.IDLE; return SegmentStatus() }
                val distToEnd = haversineMetres(lat, lng, seg.endLat, seg.endLng)
                if (distToEnd <= FINISH_RADIUS_M) state = SegmentState.FINISHED
                return buildStatus(seg, nowMs, 0.0)
            }
        }
    }

    fun reset() {
        state = SegmentState.IDLE
        activeSegment = null
        startTimeMs = 0L
        beepedForSegmentId = null
    }

private fun buildStatus(
        seg: CachedSegment,
        nowMs: Long,
        distToStart: Double,
        triggerBeep: Boolean = false,
    ): SegmentStatus {
        val elapsed = if (state == SegmentState.ACTIVE || state == SegmentState.FINISHED)
            ((nowMs - startTimeMs) / 1000L).toInt() else 0
        return SegmentStatus(
            state = state,
            segment = seg,
            elapsedSeconds = elapsed,
            distanceToStartMetres = distToStart.toInt(),
            deltaVsPrSeconds = if (state == SegmentState.ACTIVE || state == SegmentState.FINISHED)
                seg.prSeconds?.let { elapsed - it } else null,
            triggerBeep = triggerBeep,
        )
    }
}
