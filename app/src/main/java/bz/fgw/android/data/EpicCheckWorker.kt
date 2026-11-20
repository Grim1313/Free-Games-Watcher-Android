package bz.fgw.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import bz.fgw.android.watchRepository

class EpicCheckWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    internal var repository: AppRepository = context.watchRepository

    override suspend fun doWork(): Result = when (repository.refresh(backgroundRetry = runAttemptCount > 0)) {
        ScanOutcome.SUCCESS, ScanOutcome.STALE -> Result.success()
        ScanOutcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.success()
    }
}
