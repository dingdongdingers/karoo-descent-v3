package io.hammerhead.descentsegs.data

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

const val WORK_NAME = "descent_segment_sync"

class SegmentSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val creds = StravaCredentials(applicationContext)
        if (!creds.isConfigured()) return Result.failure()
        return try {
            val segments = StravaApiClient(creds).fetchStarredDescentSegments()
            SegmentRepository(applicationContext).save(
                SegmentCache(segments = segments, lastFetchedEpochMillis = System.currentTimeMillis())
            )
            Log.d("SyncWorker", "Synced ${segments.size} segments")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

fun scheduleMonthlySync(ctx: Context) {
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SegmentSyncWorker>(30, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    )
}

fun syncNow(ctx: Context) {
    WorkManager.getInstance(ctx).enqueue(
        OneTimeWorkRequestBuilder<SegmentSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_NAME)
            .build()
    )
}
