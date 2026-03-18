package io.hammerhead.descentsegs.segment

import kotlin.math.abs

fun formatTime(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

fun formatDelta(deltaSeconds: Int): String {
    val sign = if (deltaSeconds <= 0) "-" else "+"
    return "$sign${formatTime(abs(deltaSeconds))}"
}
