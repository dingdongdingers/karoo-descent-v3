package io.hammerhead.descentsegs.segment

import android.app.RemoteViews
import android.util.Log
import io.hammerhead.descentsegs.R
import io.hammerhead.descentsegs.data.SegmentRepository
import io.hammerhead.descentsegs.data.scheduleMonthlySync
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "DescentSegExt"
const val DATATYPE_ID = "descent-segment-display"

class DescentSegmentExtension : KarooExtension("io.hammerhead.descentsegs", "1") {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val tracker = SegmentTracker()
    private lateinit var karooSystem: KarooSystemService
    private val repo by lazy { SegmentRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                Log.d(TAG, "Connected to Karoo system")
                startTracking()
            }
        }
        scheduleMonthlySync(applicationContext)
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Karoo OS calls this to request a RemoteViews for our graphical data field.
     * We return an initial idle view here; updates are pushed via karooSystem.dispatch
     * with UpdateRemoteViews.
     */
    override fun startView(dataTypeId: String, context: android.content.Context): RemoteViews? {
        if (dataTypeId != DATATYPE_ID) return null
        return buildIdleViews()
    }

    private fun startTracking() {
        scope.launch(Dispatchers.IO) {
            val segments = repo.getSegments()
            Log.d(TAG, "Loaded ${segments.size} descent segments")

            karooSystem.addConsumer { event: OnLocationChanged ->
                val status = tracker.onLocation(
                    lat = event.lat,
                    lng = event.lng,
                    nowMs = System.currentTimeMillis(),
                    segments = segments,
                )
                val views = when (status.state) {
                    SegmentState.ACTIVE, SegmentState.FINISHED -> buildActiveViews(status)
                    else -> buildIdleViews()
                }
                // Push updated RemoteViews to the data field
                karooSystem.dispatch(
                    io.hammerhead.karooext.models.UpdateRemoteViews(DATATYPE_ID, views)
                )
            }

            karooSystem.addConsumer { event: RideState ->
                if (event == RideState.IDLE) {
                    tracker.reset()
                    karooSystem.dispatch(
                        io.hammerhead.karooext.models.UpdateRemoteViews(DATATYPE_ID, buildIdleViews())
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // RemoteViews builders
    // -------------------------------------------------------------------------

    private fun buildIdleViews(): RemoteViews {
        return RemoteViews(packageName, R.layout.datafield_idle)
    }

    private fun buildActiveViews(status: SegmentStatus): RemoteViews {
        val rv = RemoteViews(packageName, R.layout.datafield_active)
        val seg = status.segment ?: return buildIdleViews()

        rv.setTextViewText(R.id.tv_segment_name, seg.name)
        rv.setTextViewText(R.id.tv_elapsed, formatTime(status.elapsedSeconds))
        rv.setTextViewText(R.id.tv_pr, seg.prSeconds?.let { formatTime(it) } ?: "--:--")
        rv.setTextViewText(R.id.tv_kom, seg.komSeconds?.let { formatTime(it) } ?: "--:--")

        val delta = status.deltaVsPrSeconds
        if (delta != null) {
            val ahead = delta <= 0
            val color = applicationContext.getColor(if (ahead) R.color.ahead else R.color.behind)
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
