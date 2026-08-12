package io.hammerhead.descentsegs.segment

import io.hammerhead.descentsegs.data.CachedSegment

private const val APPROACH_RADIUS_M = 300.0
private const val TRIGGER_RADIUS_M = 50.0
private const val ABANDON_RADIUS_M = 150.0
private const val FINISH_RADIUS_M = 40.0
private const val APPROACH_TIMEOUT_MS = 180_000L // 3 minutes

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
    private var approachingStartMs = 0L
    private var beepedForSegmentId: Long? = null
    private var prevLat: Double? = null
    private var prevLng: Double? = null
    private var distanceTravelledM = 0.0

    fun onLocation(
        lat: Double,
        lng: Double,
        nowMs: Long,
        segments: List<CachedSegment>,
    ): SegmentStatus {
        val result = processLocation(lat, lng, nowMs, segments)
        prevLat = lat
        prevLng = lng
        return result
    }

    private fun processLocation(
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
                    dist <= TRIGGER_RADIUS_M && (dist < 15.0 || isMovingTowardsEnd(lat, lng, nearest)) -> {
                        state = SegmentState.ACTIVE
                        activeSegment = nearest
                        startTimeMs = nowMs
                        distanceTravelledM = 0.0
                        buildStatus(nearest, nowMs, 0.0, lat, lng)
                    }
                    dist <= APPROACH_RADIUS_M && isMovingTowardsStart(lat, lng, nearest) -> {
                        state = SegmentState.APPROACHING
                        activeSegment = nearest
                        approachingStartMs = nowMs
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

                // Timeout — stuck approaching too long, give up
                if (nowMs - approachingStartMs > APPROACH_TIMEOUT_MS) {
                    state = SegmentState.IDLE
                    activeSegment = null
                    beepedForSegmentId = null
                    return SegmentStatus(state = SegmentState.IDLE)
                }

                val dist = haversineMetres(lat, lng, seg.startLat, seg.startLng)
                return when {
                    dist <= TRIGGER_RADIUS_M && (dist < 15.0 || isMovingTowardsEnd(lat, lng, seg)) -> {
                        state = SegmentState.ACTIVE
                        startTimeMs = nowMs
                        distanceTravelledM = 0.0
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

                val pLat = prevLat
                val pLng = prevLng
                if (pLat != null && pLng != null) {
                    val step = haversineMetres(pLat, pLng, lat, lng)
                    if (step < 50.0) distanceTravelledM += step
                }

                val distToEnd = haversineMetres(lat, lng, seg.endLat, seg.endLng)
                val distToStart = haversineMetres(lat, lng, seg.startLat, seg.startLng)

                if (distToEnd <= FINISH_RADIUS_M) {
                    state = SegmentState.FINISHED
                    return buildStatus(seg, nowMs, 0.0, lat, lng)
                }

                if (distToStart > ABANDON_RADIUS_M && distToEnd > ABANDON_RADIUS_M) {
                    state = SegmentState.IDLE
                    activeSegment = null
                    startTimeMs = 0L
                    distanceTravelledM = 0.0
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
        approachingStartMs = 0L
        beepedForSegmentId = null
        prevLat = null
        prevLng = null
        distanceTravelledM = 0.0
    }

    private fun isMovingTowardsStart(lat: Double, lng: Double, seg: CachedSegment): Boolean {
        val pLat = prevLat ?: return true
        val pLng = prevLng ?: return true
        val prevDist = haversineMetres(pLat, pLng, seg.startLat, seg.startLng)
        val currDist = haversineMetres(lat, lng, seg.startLat, seg.startLng)
        return currDist < prevDist
    }

    private fun isMovingTowardsEnd(lat: Double, lng: Double, seg: CachedSegment): Boolean {
        val pLat = prevLat ?: return true
        val pLng = prevLng ?: return true
        val prevDist = haversineMetres(pLat, pLng, seg.endLat, seg.endLng)
        val currDist = haversineMetres(lat, lng, seg.endLat, seg.endLng)
        return currDist < prevDist
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
        val fractionComplete = if (seg.distanceMetres > 0)
            (distanceTravelledM / seg.distanceMetres).coerceIn(0.0, 1.0) else 0.0
        val remainingM = (seg.distanceMetres - distanceTravelledM).coerceAtLeast(0.0)
        val deltaVsKom = if ((state == SegmentState.ACTIVE || state == SegmentState.FINISHED)
            && seg.komSeconds != null) {
            val expectedKomAtThisPoint = (fractionComplete * seg.komSeconds).toInt()
            elapsed - expectedKomAtThisPoint
        } else null

        return SegmentStatus(
            state = state,
            segment = seg,
            distanceToStartMetres = distToStart.toInt(),
            distanceRemainingMetres = if (state == SegmentState.ACTIVE) remainingM else 0.0,
            deltaVsKomSeconds = deltaVsKom,
            triggerBeep = triggerBeep,
        )
    }
}
