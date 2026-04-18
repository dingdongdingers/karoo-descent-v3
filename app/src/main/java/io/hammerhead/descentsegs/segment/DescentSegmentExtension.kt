package io.hammerhead.descentsegs.segment

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.descentsegs.R
import io.hammerhead.descentsegs.data.CachedSegment
import io.hammerhead.descentsegs.data.SegmentRepository
import io.hammerhead.descentsegs.data.scheduleMonthlySync
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DescentSegExt"
private const val EXTENSION_ID = "descentsegs"
const val DATATYPE_ID = "descent-segment-display"
private const val CYCLE_INTERVAL_MS = 2000L
private const val APPROACH_RADIUS_M = 300.0

fun writeLog(appContext: Context, msg: String) {
    try {
        val logFile = File(appContext.filesDir, "app-log.txt")
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        logFile.appendText("$timestamp $msg\n")
    } catch (e: Exception) {
        Log.w(TAG, "Could not write log: ${e.message}")
    }
}

class DescentSegmentExtension : KarooExtension(EXTENSION_ID, "1") {

    override val types by lazy {
        listOf(DescentSegmentDataType(EXTENSION_ID, applicationContext))
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Extension created")
        writeLog(applicationContext, "Extension created")
        scheduleMonthlySync(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Extension destroyed")
        writeLog(applicationContext, "Extension destroyed")
    }
}

class DescentSegmentDataType(
    extension: String,
    private val appContext: Context,
) : DataTypeImpl(extension, DATATYPE_ID) {

    private val tracker = SegmentTracker()
    private val repo = SegmentRepository(appContext)

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "startStream called")
        writeLog(appContext, "startStream called")
        emitter.onNext(StreamState.Streaming(
            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0))
        ))
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView called, preview=${config.preview}")
        writeLog(appContext, "startView called preview=${config.preview}")

        if (config.preview) {
            emitter.updateView(buildView(context, SegmentStatus()))
            return
        }

        val segments = repo.getSegments()
        Log.d(TAG, "Loaded ${segments.size} segments for tracking")
        writeLog(appContext, "Loaded ${segments.size} segments")

        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.updateView(buildView(context, SegmentStatus()))

        val karooSystem = KarooSystemService(appContext)
        var nearbyApproaching = mutableListOf<CachedSegment>()
        var cycleIndex = 0
        var lastCycleMs = 0L

        karooSystem.connect { connected ->
            Log.d(TAG, "KarooSystem connected=$connected")
            writeLog(appContext, "KarooSystem connected=$connected")
            if (!connected) return@connect

            var lastDistBucket = -1
            var lastDeltaBucket = -1
            var lastRemaining = -1
            var lastState = SegmentState.IDLE

            karooSystem.addConsumer { event: OnLocationChanged ->
                val lat = event.lat
                val lng = event.lng
                val nowMs = System.currentTimeMillis()
                val status = tracker.onLocation(lat, lng, nowMs, segments)

                writeLog(appContext, "GPS state=${status.state} dist=${status.distanceToStartMetres} remaining=${"%.0f".format(status.distanceRemainingMetres)} delta=${status.deltaVsKomSeconds}")

                if (status.triggerBeep) {
                    writeLog(appContext, "Beep for ${status.segment?.name}")
                    karooSystem.dispatch(PlayBeepPattern(listOf(
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                        PlayBeepPattern.Tone(frequency = null, durationMs = 50),
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                    )))
                }

                if (status.state == SegmentState.ACTIVE || status.state == SegmentState.FINISHED) {
                    nearbyApproaching.clear()
                    val deltaBucket = status.deltaVsKomSeconds ?: 0
                    val remainingBucket = (status.distanceRemainingMetres / 50).toInt()
                    val stateChanged = status.state != lastState
                    if (stateChanged || deltaBucket != lastDeltaBucket || remainingBucket != lastRemaining) {
                        lastState = status.state
                        lastDeltaBucket = deltaBucket
                        lastRemaining = remainingBucket
                        emitter.updateView(buildView(context, status))
                    }
                    return@addConsumer
                }

                val currentNearby = segments.filter { seg ->
                    haversineMetres(lat, lng, seg.startLat, seg.startLng) <= APPROACH_RADIUS_M
                }.toMutableList()

                if (currentNearby.size > 1 && status.state == SegmentState.APPROACHING) {
                    nearbyApproaching = currentNearby
                    if (nowMs - lastCycleMs >= CYCLE_INTERVAL_MS) {
                        cycleIndex = (cycleIndex + 1) % nearbyApproaching.size
                        lastCycleMs = nowMs
                    }
                    val displaySeg = nearbyApproaching.getOrNull(cycleIndex) ?: nearbyApproaching[0]
                    val distToDisplay = haversineMetres(lat, lng, displaySeg.startLat, displaySeg.startLng).toInt()
                    val cycleStatus = SegmentStatus(
                        state = SegmentState.APPROACHING,
                        segment = displaySeg,
                        distanceToStartMetres = distToDisplay,
                    )
                    emitter.updateView(buildView(context, cycleStatus))
                    lastState = status.state
                    return@addConsumer
                }

                val distBucket = status.distanceToStartMetres / 5
                val stateChanged = status.state != lastState
                val distChanged = distBucket != lastDistBucket
                if (stateChanged || distChanged) {
                    lastState = status.state
                    lastDistBucket = distBucket
                    emitter.updateView(buildView(context, status))
                }
            }
        }

        emitter.setCancellable { karooSystem.disconnect() }
    }

    private fun buildView(context: Context, status: SegmentStatus): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.datafield_unified)

        when (status.state) {
            SegmentState.IDLE, SegmentState.FINISHED -> {
                rv.setTextViewText(R.id.tv_line1, "↓ No descent segment")
                rv.setTextViewText(R.id.tv_line2, "")
                rv.setTextViewText(R.id.tv_line3, "")
                rv.setTextViewText(R.id.tv_line4, "")
                rv.setTextColor(R.id.tv_line1, context.getColor(R.color.text_secondary))
            }
            SegmentState.APPROACHING -> {
                val dist = if (status.distanceToStartMetres > 50)
                    "${status.distanceToStartMetres}m" else "GO!"
                rv.setTextViewText(R.id.tv_line1, "↓ ${status.segment?.name ?: ""}")
                rv.setTextViewText(R.id.tv_line2, "STARTING IN")
                rv.setTextViewText(R.id.tv_line3, dist)
                rv.setTextViewText(R.id.tv_line4,
                    "KOM ${status.segment?.komSeconds?.let { formatTime(it) } ?: "N/A"}" +
                    "  PR ${status.segment?.prSeconds?.let { formatTime(it) } ?: "--:--"}")
                rv.setTextColor(R.id.tv_line1, context.getColor(R.color.accent))
                rv.setTextColor(R.id.tv_line3, context.getColor(R.color.text_primary))
                rv.setTextColor(R.id.tv_line4, context.getColor(R.color.text_secondary))
            }
            SegmentState.ACTIVE -> {
                val seg = status.segment
                val delta = status.deltaVsKomSeconds
                val hasKom = seg?.komSeconds != null
                val ahead = delta != null && delta <= 0
                val deltaColor = if (hasKom && ahead) context.getColor(R.color.ahead)
                                 else if (hasKom) context.getColor(R.color.behind)
                                 else context.getColor(R.color.text_secondary)
                val deltaText = if (delta != null) formatDelta(delta) else "--:--"
                val deltaLabel = if (!hasKom) "NO KOM DATA"
                                 else if (ahead) "AHEAD OF KOM" else "BEHIND KOM"
                val remainingKm = String.format("%.1fkm", status.distanceRemainingMetres / 1000.0)

                rv.setTextViewText(R.id.tv_line1, seg?.name ?: "")
                rv.setTextViewText(R.id.tv_line2, "$deltaLabel  $remainingKm")
                rv.setTextViewText(R.id.tv_line3, deltaText)
                rv.setTextViewText(R.id.tv_line4,
                    "KOM ${seg?.komSeconds?.let { formatTime(it) } ?: "N/A"}" +
                    "  PR ${seg?.prSeconds?.let { formatTime(it) } ?: "--:--"}")
                rv.setTextColor(R.id.tv_line1, context.getColor(R.color.accent))
                rv.setTextColor(R.id.tv_line2, deltaColor)
                rv.setTextColor(R.id.tv_line3, deltaColor)
                rv.setTextColor(R.id.tv_line4, context.getColor(R.color.text_secondary))
            }
        }

        return rv
    }
}
