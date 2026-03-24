package io.hammerhead.descentsegs.segment

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.descentsegs.R
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

private const val TAG = "DescentSegExt"
private const val EXTENSION_ID = "descentsegs"
const val DATATYPE_ID = "descent-segment-display"

class DescentSegmentExtension : KarooExtension(EXTENSION_ID, "1") {

    override val types by lazy {
        listOf(DescentSegmentDataType(EXTENSION_ID, applicationContext))
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Extension created")
        scheduleMonthlySync(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Extension destroyed")
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
        emitter.onNext(StreamState.Streaming(
            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0))
        ))
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView called, preview=${config.preview}")

        if (config.preview) {
            emitter.updateView(buildView(context, SegmentStatus()))
            return
        }

        val segments = repo.getSegments()
        Log.d(TAG, "Loaded ${segments.size} segments for tracking")

        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.updateView(buildView(context, SegmentStatus()))

        val karooSystem = KarooSystemService(appContext)
        karooSystem.connect { connected ->
            Log.d(TAG, "KarooSystem connected=$connected")
            if (!connected) return@connect

            var lastDistBucket = -1
            var lastElapsed = -1
            var lastState = SegmentState.IDLE

            karooSystem.addConsumer { event: OnLocationChanged ->
                val status = tracker.onLocation(
                    lat = event.lat,
                    lng = event.lng,
                    nowMs = System.currentTimeMillis(),
                    segments = segments,
                )

                if (status.triggerBeep) {
                    karooSystem.dispatch(PlayBeepPattern(listOf(
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                        PlayBeepPattern.Tone(frequency = null, durationMs = 50),
                        PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                    )))
                }

                // Throttle updates to avoid overwhelming the emitter
                val distBucket = status.distanceToStartMetres / 5
                val stateChanged = status.state != lastState
                val distChanged = distBucket != lastDistBucket
                val elapsedChanged = status.elapsedSeconds != lastElapsed

                if (stateChanged || distChanged || elapsedChanged) {
                    lastState = status.state
                    lastDistBucket = distBucket
                    lastElapsed = status.elapsedSeconds
                    emitter.updateView(buildView(context, status))
                }
            }
        }

        emitter.setCancellable { karooSystem.disconnect() }
    }

    /**
     * Single layout for all states — avoids RemoteViews swap crash at state transitions.
     */
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
                rv.setTextViewText(R.id.tv_line4, "")
                rv.setTextColor(R.id.tv_line1, context.getColor(R.color.accent))
                rv.setTextColor(R.id.tv_line3, context.getColor(R.color.text_primary))
            }
            SegmentState.ACTIVE -> {
                val seg = status.segment
                val delta = status.deltaVsPrSeconds
                val deltaText = if (delta != null) formatDelta(delta) else "--:--"
                val deltaLabel = if (delta != null && delta <= 0) "AHEAD" else "BEHIND"
                val deltaColor = if (delta != null && delta <= 0)
                    context.getColor(R.color.ahead) else context.getColor(R.color.behind)

                rv.setTextViewText(R.id.tv_line1, seg?.name ?: "")
                rv.setTextViewText(R.id.tv_line2, formatTime(status.elapsedSeconds))
                rv.setTextViewText(R.id.tv_line3, deltaText)
                rv.setTextViewText(R.id.tv_line4,
                    "PR ${seg?.prSeconds?.let { formatTime(it) } ?: "--:--"}  KOM ${seg?.komSeconds?.let { formatTime(it) } ?: "--:--"}")
                rv.setTextColor(R.id.tv_line1, context.getColor(R.color.accent))
                rv.setTextColor(R.id.tv_line2, context.getColor(R.color.text_primary))
                rv.setTextColor(R.id.tv_line3, deltaColor)
                rv.setTextColor(R.id.tv_line4, context.getColor(R.color.text_secondary))
            }
        }

        return rv
    }
}
