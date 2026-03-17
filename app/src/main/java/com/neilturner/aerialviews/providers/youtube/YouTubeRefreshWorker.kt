package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber

class YouTubeRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (!sharedPreferences.getBoolean(YouTubeSourceRepository.KEY_ENABLED, true)) {
            Timber.tag(TAG).i("Skipping YouTube refresh because the source is disabled")
            return Result.success()
        }

        return try {
            NewPipeHelper.init()
            val forceSearchRefresh = inputData.getBoolean(KEY_FORCE_SEARCH_REFRESH, false)
            YouTubeFeature.repository(applicationContext).warmCache(forceSearchRefresh)
            Result.success()
        } catch (exception: Exception) {
            Timber.tag(TAG).e(exception, "YouTube refresh failed")
            if (exception.isNetworkError()) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "YouTubeWorker"
        const val KEY_FORCE_SEARCH_REFRESH = "force_search_refresh"
    }
}
