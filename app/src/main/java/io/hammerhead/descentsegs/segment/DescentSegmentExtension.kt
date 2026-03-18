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
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

private const val TAG = "DescentSegExt"
const val DATATYPE_ID = "descent-segment-display"
const val EXTENSION_ID = "io.hammerhead.descentsegs"

class DescentSegmentExtension : KarooExtension(EXTENSION_ID, "1") {

    private lateinit var karooSystem: KarooSystemService
    private val tracker = SegmentTracker()
    private val repo by lazy { SegmentRepository(applicationContext) }

    override val types by lazy {
        listOf(DescentSegmentDataType(EXTENSION_ID, karooSystem, tracker, repo, applicationContext))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { Log.d(TAG, "Karoo system connected") }
        scheduleMonthlySync(applicationContext)
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }
}

class DescentSegmentDataType(
    extension: String,
    private val karooSystem: KarooSystemService,
    private val tracker: SegmentTracker,
    private val repo: SegmentRepository,
    private val ctx: Context,
) : DataTypeImpl(extension, DATATYPE_ID) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(io.hammerhead.karooext.models.DataPoint(
            dataTypeId, mapOf(io.hammerhead.karooext.models.DataType.Field.SINGLE to 0.0)
        )))
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val segments = repo.getSegments()

        // Show idle state immediately
        emitter.updateView(buildIdleViews(context))

        karooSystem.addConsumer { event: OnLocationChanged ->
            val status = tracker.onLocation(
                lat = event.lat,
                lng = event.lng,
                nowMs = System.currentTimeMillis(),
                segments = segments,
            )
            val views = when (status.state) {
                SegmentState.ACTIVE, SegmentState.FINISHED -> buildActiveViews(context, status)
                else -> buildIdleViews(context)
            }
            emitter.updateView(views)
        }

   
        emitter.setCancellable { scope.cancel() }
    }

    private fun buildIdleViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.datafield_idle)

    private fun buildActiveViews(context: Context, status: SegmentStatus): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.datafield_active)
        val seg = status.segment ?: return buildIdleViews(context)

        rv.setTextViewText(R.id.tv_segment_name, seg.name)
        rv.setTextViewText(R.id.tv_elapsed, formatTime(status.elapsedSeconds))
        rv.setTextViewText(R.id.tv_pr, seg.prSeconds?.let { formatTime(it) } ?: "--:--")
        rv.setTextViewText(R.id.tv_kom, seg.komSeconds?.let { formatTime(it) } ?: "--:--")

        val delta = status.deltaVsPrSeconds
        if (delta != null) {
            val ahead = delta <= 0
            val color = context.getColor(if (ahead) R.color.ahead else R.color.behind)
            rv.setTextViewText(R.id.tv_delta_label, if (ahead) "AHEAD" else "BEHIND")
            rv.setTextColor(R.id.tv_delta_label, color)
            rv.setTextViewText(R.id.tv_delta, formatDelta(delta))
            rv.setTextColor(R.id.tv_delta, color)
        } else {
            rv.setTextViewText(R.id.tv_delta_label, "")
            rv.setTextViewText(R.id.tv_delta, "--:--")
        }
        return rv
    }
}
