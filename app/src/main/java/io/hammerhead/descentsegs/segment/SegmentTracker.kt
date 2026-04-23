package io.hammerhead.descentsegs.segment

import io.hammerhead.descentsegs.data.CachedSegment
import kotlin.math.min
import kotlin.math.sqrt

private const val APPROACH_RADIUS_M = 300.0
private const val TRIGGER_RADIUS_M = 50.0
private const val ABANDON_RADIUS_M = 150.0
private const val FINISH_RADIUS_M = 40.0

enum class SegmentState { IDLE, APPROACHING, ACTIVE, FINISHED }

data class SegmentStatus(
    val state: SegmentState = SegmentState.IDLE,
    val segment: CachedSegment? = null,
    val distanceToStartMetres: Int = 0,
    val distanceRemainingMetres: Double = 0.0,
    val deltaVsKomSeconds: Int? = null,
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
                val nearest = segments.minByOrNull { seg ->
                    haversineMetres(lat, lng, seg.startLat, seg.startLng)
                } ?: return SegmentStatus(state = SegmentState.IDLE)
                val dist = haversineMetres(lat, lng, nearest.startLat, nearest.startLng)
                return when {
                    dist <= TRIGGER_RADIUS_M -> {
                        state = SegmentState.ACTIVE
                        activeSegment = nearest
                        startTimeMs = nowMs
                        buildStatus(nearest, nowMs, 0.0, lat, lng)
                    }
                    dist <= APPROACH_RADIUS_M -> {
                        state = SegmentState.APPROACHING
                        activeSegment = nearest
                        val beep = beepedForSegmentId != nearest.id
                        if (beep) beepedForSegmentId = nearest.id
                        buildStatus(nearest, nowMs, dist, lat, lng, triggerBeep = beep)
                    }
                    else -> SegmentStatus(state = SegmentState.IDLE)
                }
            }

            SegmentState.APPROACHING -> {
                val seg = activeSegment ?: run {
                    state = SegmentState.IDLE
                    return SegmentStatus()
                }
                val dist = haversineMetres(lat, lng, seg.startLat, seg.startLng)
                return when {
                    dist <= TRIGGER_RADIUS_M -> {
                        state = SegmentState.ACTIVE
                        startTimeMs = nowMs
                        buildStatus(seg, nowMs, 0.0, lat, lng)
                    }
                    dist <= APPROACH_RADIUS_M -> buildStatus(seg, nowMs, dist, lat, lng)
                    else -> {
                        state = SegmentState.IDLE
                        activeSegment = null
                        beepedForSegmentId = null
                        SegmentStatus(state = SegmentState.IDLE)
                    }
                }
            }

            SegmentState.ACTIVE -> {
                val seg = activeSegment ?: run {
                    state = SegmentState.IDLE
                    return SegmentStatus()
                }
                val distToEnd = haversineMetres(lat, lng, seg.endLat, seg.endLng)

                if (distToEnd <= FINISH_RADIUS_M) {
                    state = SegmentState.FINISHED
                    return buildStatus(seg, nowMs, 0.0, lat, lng)
                }

                // Only abandon if far from BOTH start and end
                val distToStart = haversineMetres(lat, lng, seg.startLat, seg.startLng)
                if (distToStart > ABANDON_RADIUS_M && distToEnd > ABANDON_RADIUS_M) {
                    state = SegmentState.IDLE
                    activeSegment = null
                    startTimeMs = 0L
                    return SegmentStatus(state = SegmentState.IDLE)
                }

                return buildStatus(seg, nowMs, 0.0, lat, lng)
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
        lat: Double,
        lng: Double,
        triggerBeep: Boolean = false,
    ): SegmentStatus {
        val elapsed = if (state == SegmentState.ACTIVE || state == SegmentState.FINISHED)
            ((nowMs - startTimeMs) / 1000L).toInt() else 0
        val deltaVsKom = if ((state == SegmentState.ACTIVE || state == SegmentState.FINISHED) && seg.komSeconds != null)
            elapsed - seg.komSeconds else null
        val distToEnd = haversineMetres(lat, lng, seg.endLat, seg.endLng)
        return SegmentStatus(
            state = state,
            segment = seg,
            distanceToStartMetres = distToStart.toInt(),
            distanceRemainingMetres = if (state == SegmentState.ACTIVE) distToEnd else 0.0,
            deltaVsKomSeconds = deltaVsKom,
            triggerBeep = triggerBeep,
        )
    }
}
