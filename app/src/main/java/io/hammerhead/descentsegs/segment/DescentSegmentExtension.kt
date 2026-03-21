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
            emitter.updateView(buildIdleViews(context))
            return
        }

        val segments = repo.getSegments()
        Log.d(TAG, "Loaded ${segments.size} segments for tracking")

        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.updateView(buildIdleViews(context))

        val karooSystem = KarooSystemService(appContext)
        karooSystem.connect { connected ->
            Log.d(TAG, "KarooSystem connected=$connected")
            if (!connected) return@connect

            var lastState = SegmentState.IDLE
            var lastDistBucket = -1

            karooSystem.addConsumer { event: OnLocationChanged ->
                val status = tracker.onLocation(
                    lat = event.lat,
                    lng = event.lng,
                    nowMs = System.currentTimeMillis(),
                    segments = segments,
                )

                // Fire beep on approach
                if (status.triggerBeep) {
                    karooSystem.dispatch(PlayBeepPattern(
                        listOf(
                            PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                            PlayBeepPattern.Tone(frequency = null, durationMs = 50),
                            PlayBeepPattern.Tone(frequency = 1800, durationMs = 100),
                        )
                    ))
                }

                // Only update view when something meaningful changes
                val distBucket = status.distanceToStartMetres / 5
                val stateChanged = status.state != lastState
                val shouldUpdate = stateChanged ||
                    (status.state == SegmentState.APPROACHING && distBucket != lastDistBucket) ||
                    (status.state == SegmentState.ACTIVE || status.state == SegmentState.FINISHED)

                if (shouldUpdate) {
                    lastState = status.state
                    lastDistBucket = distBucket
                    val views = when (status.state) {
                        SegmentState.APPROACHING -> buildApproachingViews(context, status)
                        SegmentState.ACTIVE, SegmentState.FINISHED -> buildActiveViews(context, status)
                        else -> buildIdleViews(context)
                    }
                    emitter.updateView(views)
                }
            }
        }

        emitter.setCancellable { karooSystem.disconnect() }
    }

    private fun buildIdleViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.datafield_idle)

    private fun buildApproachingViews(context: Context, status: SegmentStatus): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.datafield_approaching)
        rv.setTextViewText(R.id.tv_approach_name, "↓ ${status.segment?.name ?: ""}")
        rv.setTextViewText(R.id.tv_approach_dist, "${status.distanceToStartMetres}m")
        return rv
    }

    private fun buildActiveViews(context: Context, status: SegmentStatus): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.datafield_active)
        val seg = status.segment ?: return buildIdleViews(context)
        rv.setTextViewText(R.id.tv_segment_name, seg.name)
        rv.setTextViewText(R.id.tv_elapsed, formatTime(status.elapsedSeconds))
        rv.setTextViewText(R.id.tv_pr, seg.prSeconds?.let { formatTime(it) } ?: "--:--")
        rv.setTextViewText(R.id.tv_kom, seg.komSeconds?.let { formatTime(it) } ?: "--:--
