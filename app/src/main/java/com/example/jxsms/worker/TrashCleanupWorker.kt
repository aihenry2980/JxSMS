package com.example.jxsms.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.jxsms.JxSmsApplication

class TrashCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as JxSmsApplication).container.trash.cleanupExpired()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
