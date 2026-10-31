package com.lumenkot.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadExportWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    companion object {
        fun schedule(context: Context,id: String,replace: Boolean=false) {
            val current=state(context,id)
            if(current.optBoolean("deleted") || (!replace && current.optString("state")=="ready")) return
            val work=OneTimeWorkRequestBuilder<DownloadExportWorker>().setInputData(workDataOf("download" to id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("export:$id",if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,work)
        }
        fun state(context: Context,id: String): JSONObject = JSONObject(Settings(context).prefs.getString("export:$id","{}")!!)
        @Synchronized fun save(context: Context,id: String,value: JSONObject): Boolean {
            if(state(context,id).optBoolean("deleted")) return false
            return Settings(context).prefs.edit().putString("export:$id",value.toString()).commit()
        }
        @Synchronized fun markDeleted(context: Context,id: String): JSONObject {
            val value=state(context,id).put("deleted",true)
            Settings(context).prefs.edit().putString("export:$id",value.toString()).commit()
            return value
        }
        fun cancel(context: Context,id: String) { WorkManager.getInstance(context).cancelUniqueWork("export:$id") }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel("video_export","Сохранение видео",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"video_export").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Сохранение видео в папку").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(7100+id.hashCode().and(0xffff),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7100+id.hashCode().and(0xffff),notification)
    }
    override suspend fun doWork(): Result {
        val downloadId=inputData.getString("download") ?: return Result.failure()
        var status=state(applicationContext,downloadId)
        return try {
            if(status.optBoolean("deleted") || status.optString("state")=="ready") return Result.success()
            val download=withContext(Dispatchers.Main) { OfflineDownloads.manager(applicationContext).downloadIndex.getDownload(downloadId) } ?: return Result.success()
            val metadata=JSONObject(download.request.data.toString(Charsets.UTF_8))
            val tree=metadata.optString("tree")
            if(tree.isBlank()) return Result.success() // Older cache-only downloads stay compatible.
            setForeground(getForegroundInfo())
            status=JSONObject().put("state","saving")
            check(save(applicationContext,downloadId,status)) { "Сохранение отменено" }
            val ready=withContext(Dispatchers.IO) {
                SavedMediaExporter(applicationContext,OfflineDownloads.playback(applicationContext)) { isStopped || state(applicationContext,downloadId).optBoolean("deleted") }
                    .export(downloadId,download.request.uri.toString(),metadata) { folder ->
                        status.put("folder",folder.toString())
                        check(save(applicationContext,downloadId,status)) { "Сохранение отменено" }
                    }
            }
            status.put("state","ready").put("media",ready)
            check(save(applicationContext,downloadId,status)) { "Сохранение отменено" }
            Result.success()
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            if(state(applicationContext,downloadId).optBoolean("deleted")) {
                status.optString("folder").takeIf { it.isNotBlank() }?.let { DocumentFile.fromSingleUri(applicationContext,Uri.parse(it))?.delete() }
                return Result.success()
            }
            status.put("state","error").put("error",e.message ?: "Ошибка сохранения")
            save(applicationContext,downloadId,status)
            if(runAttemptCount<2 && !isStopped) Result.retry() else Result.failure()
        }
    }
}
