package tw.timecam.upload

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object UploadScheduler {
    const val UNIQUE_NOW = "timecam-upload-now"
    const val UNIQUE_DAILY = "timecam-upload-daily"

    fun constraints(prefs: UploadPrefs): Constraints {
        val net = if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(net)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
    }

    /** 拍完馬上或按「立即上傳」：入列一次性工作，受 Wi‑Fi／充電條件限制 */
    fun enqueueNow(ctx: Context, prefs: UploadPrefs = UploadPrefs(ctx)) {
        if (!prefs.enabled && !prefs.granted) {
            // 立即上傳按鈕仍可在已連結時呼叫；未開自動上傳也允許手動立刻傳
        }
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(prefs))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun applySchedule(ctx: Context, prefs: UploadPrefs = UploadPrefs(ctx)) {
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.enabled) {
            wm.cancelUniqueWork(UNIQUE_DAILY)
            return
        }
        if (prefs.whenMode == "daily") {
            val delay = UploadTimes.delayToNext(System.currentTimeMillis(), prefs.time)
            val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints(prefs))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(UNIQUE_DAILY)
        }
    }

    fun afterCapture(ctx: Context, prefs: UploadPrefs = UploadPrefs(ctx)) {
        if (!prefs.enabled) return
        if (prefs.whenMode == "immediate") enqueueNow(ctx, prefs)
        // daily：已由週期工作處理，這裡只入列、等到點
    }
}
