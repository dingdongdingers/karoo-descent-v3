package io.hammerhead.descentsegs.segment

import io.hammerhead.descentsegs.data.CachedSegment

private const val APPROACH_RADIUS_M = 300.0
private const val TRIGGER_RADIUS_M = 50.0
private const val FINISH_RADIUS_M = 40.0

// Abandon detection: instead of a raw distance-to-endpoints check (which fires
// for every rider in the middle of any segment >300m long), track whether the
// rider is still making progress toward the end. Abandon only if distToEnd has
// regressed meaningfully past the closest point reached, and stayed regressed
// for several consecutive samples (filters single noisy GPS fixes and doesn't
// punish a rider stopped at a light mid-descent, since a stationary rider isn't
// regressing).
private const val REGRESSION_THRESHOLD_M = 30.0
private const val REGRESSION_TICK_LIMIT = 5

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
    private var prevLat: Double? = null
    private var prevLng: Double? = null
    // Cumulative distance travelled since segment start
    private var distanceTravelledM = 0.0
    // Closest distance-to-end reached so far this ACTIVE run, and how many
    // consecutive ticks distToEnd has been meaningfully worse than that best
    private var bestDistToEndM = Double.MAX_VALUE
    private var regressionTicks = 0

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
                    dist <= TRIGGER_RADIUS_M && isMovingTowardsEnd(lat, lng, nearest) -> {
                        state = SegmentState.ACTIVE
                        activeSegment = nearest
                        startTimeMs = nowMs
                        distanceTravelledM = 0.0
                        bestDistToEndM = Double.MAX_VALUE
                        regressionTicks = 0
                        buildStatus(nearest, nowMs, 0.0, lat, lng)
                    }
                    dist <= APPROACH_RADIUS_M && isMovingTowardsStart(lat, lng, nearest) -> {
                        if (state != SegmentState.APPROACHING || activeSegment?.id != nearest.id) {
                            state = SegmentState.APPROACHING
                            activeSegment = nearest
                            val beep = beepedForSegmentId != nearest.id
                            if (beep) beepedForSegmentId = nearest.id
                            buildStatus(nearest, nowMs, dist, lat, lng, triggerBeep = beep)
                        } else {
                            buildStatus(nearest, nowMs, dist, lat, lng)
                        }
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
                    dist <= TRIGGER_RADIUS_M && isMovingTowardsEnd(lat, lng, seg) -> {
                        state = SegmentState.ACTIVE
                        startTimeMs = nowMs
                        distanceTravelledM = 0.0
                        bestDistToEndM = Double.MAX_VALUE
                        regressionTicks = 0
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

                // Accumulate distance from GPS ticks
                val pLat = prevLat
                val pLng = prevLng
                if (pLat != null && pLng != null) {
                    val step = haversineMetres(pLat, pLng, lat, lng)
                    if (step < 50.0) { // ignore GPS jumps > 50m
                        distanceTravelledM += step
                    }
                }

                val distToEnd = haversineMetres(lat, lng, seg.endLat, seg.endLng)
                val distToStart = haversineMetres(lat, lng, seg.startLat, seg.startLng)

                if (distToEnd <= FINISH_RADIUS_M) {
                    state = SegmentState.FINISHED
                    return buildStatus(seg, nowMs, 0.0, lat, lng)
                }

                // Track the closest approach to the end reached so far.
                if (distToEnd < bestDistToEndM) {
                    bestDistToEndM = distToEnd
                }

                // Abandon only if the rider has meaningfully regressed from
                // their closest approach, for several consecutive samples —
                // i.e. they've actually turned back or left the route, not
                // just that they're 150m+ from both endpoints (true for the
                // entire middle of any segment) or briefly noisy/stopped.
                regressionTicks = if (distToEnd > bestDistToEndM + REGRESSION_THRESHOLD_M) {
                    regressionTicks + 1
                } else {
                    0
                }

                if (regressionTicks >= REGRESSION_TICK_LIMIT) {
                    state = SegmentState.IDLE
                    activeSegment = null
                    startTimeMs = 0L
                    distanceTravelledM = 0.0
                    bestDistToEndM = Double.MAX_VALUE
                    regressionTicks = 0
                    return SegmentStatus(state = SegmentState.IDLE)
                }

                return buildStatus(seg, nowMs, distToStart, lat, lng)
            }
        }
    }

    fun reset() {
        state = SegmentState.IDLE
        activeSegment = null
        startTimeMs = 0L
        beepedForSegmentId = null
        prevLat = null
        prevLng = null
        distanceTravelledM = 0.0
        bestDistToEndM = Double.MAX_VALUE
        regressionTicks = 0
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

        // Use cumulative distance travelled for fraction complete
        // This is more reliable than distance-to-end when GPS is jittery
        val fractionComplete = if (seg.distanceMetres > 0)
            (distanceTravelledM / seg.distanceMetres).coerceIn(0.0, 1.0) else 0.0

        val remainingM = (seg.distanceMetres - distanceTravelledM).coerceAtLeast(0.0)

        // Delta: your elapsed vs where KOM rider would be at same distance
        // At fraction=0: expectedKom=0, delta=0 ✓
        // At fraction=1: expectedKom=komSeconds, delta=elapsed-komSeconds ✓
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
