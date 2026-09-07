package dev.sieve.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sieve.Graph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Refreshes the downloaded lists in the background, roughly once a day on unmetered Wi-Fi. */
class BlocklistUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        Graph.ensure(applicationContext)
        if (!Graph.settings.preferences.first().autoUpdate) return Result.success()
        return if (Graph.blocklists.update() != null) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "sieve.blocklist-update"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
