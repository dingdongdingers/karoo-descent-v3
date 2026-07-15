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
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "DescentSegExt"
private const val EXTENSION_ID = "descentsegs"
const val DATATYPE_ID = "descent-segment-display"
const val DATATYPE_DISTANCE_ID = "descent-segment-distance"
private const val CYCLE_INTERVAL_MS = 2000L
private const val APPROACH_RADIUS_M = 300.0
private const val DEBUG_PREF = "debug_logging"

fun isDebugEnabled(ctx: Context): Boolean =
    ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getBoolean(DEBUG_PREF, false)

fun setDebugEnabled(ctx: Context, enabled: Boolean) =
    ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .edit().putBoolean(DEBUG_PREF, enabled).apply()

fun writeLog(appContext: Context, msg: String) {
    if (!isDebugEnabled(appContext)) return
    try {
        val logFile = File(appContext.filesDir, "app-log.txt")
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        logFile.appendText("$timestamp $msg\n")
    } catch (e: Exception) {
        Log.w(TAG, "Could not write log: ${e.message}")
    }
}

// Listeners that get called on every status update
object StatusBroadcaster {
    val listeners = CopyOnWriteArrayList<(SegmentStatus) -> Unit>()
    fun emit(status: SegmentStatus) = listeners.forEach { it(status) }
}

class DescentSegmentExtension : KarooExtension(EXTENSION_ID, "1") {

    private val tracker = SegmentTracker()
    private val repo by lazy { SegmentRepository(applicationContext) }
    private var karooSystem: KarooSystemService? = null
    private var segments = listOf<CachedSegment>()

    override val types by lazy {
        listOf(
            DescentSegmentDataType(EXTENSION_ID, applicationContext),
            DescentSegmentDistanceType(EXTENSION_ID, applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Extension created")
        writeLog(applicationContext, "Extension created")
        scheduleMonthlySync(applicationContext)

        segments = repo.getSegments()
        writeLog(applicationContext, "Loaded ${segments.size} segments")

        val ks = KarooSystemService(applicationContext)
        karooSystem = ks
        ks.connect { connected ->
            writeLog(applicationContext, "KarooSystem connected=$connected")
            if (!connected) return@connect
            var nearbyApproaching = mutableListOf<CachedSegment>()
            var cycleIndex = 0
            var lastCycleMs = 0L

            ks.addConsumer { event: OnLocationChanged ->
                val lat = event.lat
                val lng = event.lng
                val nowMs = System.currentTimeMillis()
                val status = tracker.onLocation(lat, lng, nowMs, segments)

                writeLog(applicationContext, "GPS state=${status.state} dist=${status.distanceToStartMetres} remaining=${"%.0f".format(status.distanceRemainingMetres)} delta=${status.deltaVsKomSeconds}")

                if (status.triggerBeep) {
                    writeLog(applicationContext, "Beep for ${status.segment?.name}")
                    ks.dispatch(PlayBeepPattern(listOf(
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                        PlayBeepPattern.Tone(frequency = null, durationMs = 50),
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                    )))
                }

                // Handle multiple approaching segments cycling
                if (status.state == SegmentState.APPROACHING) {
                    val currentNearby = segments.filter { seg ->
                        haversineMetres(lat, lng, seg.startLat, seg.startLng) <= APPROACH_RADIUS_M
                    }.toMutableList()
                    if (currentNearby.size > 1) {
                        nearbyApproaching = currentNearby
                        if (nowMs - lastCycleMs >= CYCLE_INTERVAL_MS) {
                            cycleIndex = (cycleIndex + 1) % nearbyApproaching.size
                            lastCycleMs = nowMs
                        }
                        val displaySeg = nearbyApproaching.getOrNull(cycleIndex) ?: nearbyApproaching[0]
                        val distToDisplay = haversineMetres(lat, lng, displaySeg.startLat, displaySeg.startLng).toInt()
                        StatusBroadcaster.emit(SegmentStatus(
                            state = SegmentState.APPROACHING,
                            segment = displaySeg,
                            distanceToStartMetres = distToDisplay,
                        ))
                        return@addConsumer
                    }
                } else {
                    nearbyApproaching.clear()
                }

                StatusBroadcaster.emit(status)
            }
        }
    }

    override fun onDestroy() {
        karooSystem?.disconnect()
        karooSystem = null
        super.onDestroy()
        Log.d(TAG, "Extension destroyed")
        writeLog(applicationContext, "Extension destroyed")
    }
}

// ─── Main graphical field ────────────────────────────────────────────────────

class DescentSegmentDataType(
    extension: String,
    private val appContext: Context,
) : DataTypeImpl(extension, DATATYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        writeLog(appContext, "Main startStream")
        emitter.onNext(StreamState.Streaming(
            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0))
        ))
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        writeLog(appContext, "Main startView preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.updateView(buildView(context, SegmentStatus()))
        if (config.preview) return

        var lastDeltaBucket = Int.MIN_VALUE
        var lastRemainingBucket = Int.MIN_VALUE
        var lastState = SegmentState.IDLE

        val listener: (SegmentStatus) -> Unit = { status ->
            val deltaBucket = status.deltaVsKomSeconds ?: 0
            val remainingBucket = (status.distanceRemainingMetres / 25).toInt()
            val stateChanged = status.state != lastState
            if (stateChanged || deltaBucket != lastDeltaBucket || remainingBucket != lastRemainingBucket) {
                lastState = status.state
                lastDeltaBucket = deltaBucket
                lastRemainingBucket = remainingBucket
                try {
                    emitter.updateView(buildView(context, status))
                } catch (e: Exception) {
                    writeLog(appContext, "Main view update failed: ${e.message}")
                }
            }
        }

        StatusBroadcaster.listeners.add(listener)
        emitter.setCancellable { StatusBroadcaster.listeners.remove(listener) }
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
                rv.setTextViewText(R.id.tv_line1, seg?.name ?: "")
                rv.setTextViewText(R.id.tv_line2, if (!hasKom) "NO KOM DATA" else if (ahead) "AHEAD OF KOM" else "BEHIND KOM")
                rv.setTextViewText(R.id.tv_line3, if (delta != null) formatDelta(delta) else "--:--")
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

// ─── Distance remaining field ─────────────────────────────────────────────────

class DescentSegmentDistanceType(
    extension: String,
    private val appContext: Context,
) : DataTypeImpl(extension, DATATYPE_DISTANCE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        writeLog(appContext, "Distance startStream")
        emitter.onNext(StreamState.Streaming(
            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0))
        ))
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        writeLog(appContext, "Distance startView preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.updateView(buildDistanceView(context, 0.0, false))
        if (config.preview) return

        var lastRemainingBucket = Int.MIN_VALUE

        val listener: (SegmentStatus) -> Unit = { status ->
            val isActive = status.state == SegmentState.ACTIVE
            val remainingKm = if (isActive) status.distanceRemainingMetres / 1000.0 else 0.0
            val bucket = (remainingKm * 20).toInt() // update every 50m
            if (bucket != lastRemainingBucket) {
                lastRemainingBucket = bucket
                try {
                    emitter.updateView(buildDistanceView(context, remainingKm, isActive))
                } catch (e: Exception) {
                    writeLog(appContext, "Distance view update failed: ${e.message}")
                }
            }
        }

        StatusBroadcaster.listeners.add(listener)
        emitter.setCancellable { StatusBroadcaster.listeners.remove(listener) }
    }

    private fun buildDistanceView(context: Context, remainingKm: Double, isActive: Boolean): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.datafield_distance)
        rv.setTextViewText(R.id.tv_distance_value,
            if (isActive && remainingKm > 0) String.format("%.2f", remainingKm) else "--")
        rv.setTextViewText(R.id.tv_distance_unit, "km remaining")
        rv.setTextColor(R.id.tv_distance_value, context.getColor(R.color.text_primary))
        rv.setTextColor(R.id.tv_distance_unit, context.getColor(R.color.text_secondary))
        return rv
    }
}
