package tw.timecam.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = UploadPrefs(applicationContext)
        val repo = UploadRepo(applicationContext)
        val items = repo.toUpload()
        if (items.isEmpty()) return Result.success()
        runCatching { setForeground(foreground("正在上傳到雲端硬碟…")) }
        val auth = DriveAuth.silentToken(applicationContext)
        val token = when (auth) {
            is DriveAuth.Outcome.Token -> {
                if (!auth.email.isNullOrBlank()) prefs.accountEmail = auth.email
                auth.accessToken
            }
            is DriveAuth.Outcome.Fail -> {
                prefs.lastError = auth.message
                return if (auth.needsSetup) Result.failure() else Result.retry()
            }
            is DriveAuth.Outcome.NeedsUi -> {
                prefs.lastError = DriveAuth.RELINK_MSG
                return Result.failure()
            }
        }
        val client = DriveClient(token)
        return try {
            val folderId = client.findOrCreateFolder(prefs.folder, prefs.folderId.ifBlank { null })
            prefs.folderId = folderId
            var lastErr: String? = null
            for (item in items) {
                val f = File(item.localPath)
                if (!f.isFile || f.length() == 0L) {
                    repo.markError(item, "本機暫存檔已遺失")
                    lastErr = "本機暫存檔已遺失"
                    continue
                }
                repo.markUploading(item.id)
                try {
                    val fid = client.upload(folderId, item.name, item.mime, f)
                    repo.markDone(item, fid)
                } catch (e: Exception) {
                    val msg = e.message ?: "上傳失敗"
                    repo.markError(item, msg)
                    lastErr = msg
                }
            }
            if (lastErr == null) {
                prefs.lastError = ""
                prefs.lastUploadAt = System.currentTimeMillis()
                Result.success()
            } else {
                prefs.lastError = lastErr
                Result.retry()
            }
        } catch (e: Exception) {
            prefs.lastError = e.message ?: "上傳失敗"
            Result.retry()
        }
    }

    private fun foreground(text: String): ForegroundInfo {
        val ch = "upload"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "雲端上傳", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, ch)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("時間相機")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(42, n)
    }
}
