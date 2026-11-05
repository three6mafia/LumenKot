package com.lumenkot.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LumenDownloadService: DownloadService(4104,1000L,"lumen_downloads",R.string.download_channel,0) {
    private val notifications by lazy {DownloadNotificationHelper(this,"lumen_downloads")}
    override fun getDownloadManager(): DownloadManager=HlsDownloads.get(this).manager
    override fun getScheduler(): Scheduler=PlatformScheduler(this,4105)
    override fun getForegroundNotification(downloads: List<Download>,notMetRequirements: Int): Notification {
        val pending=PendingIntent.getActivity(this,4104,Intent(this,MainActivity::class.java).putExtra("showDownloads",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notifications.buildProgressNotification(this,android.R.drawable.stat_sys_download,pending,getString(R.string.download_notification),downloads,notMetRequirements)
    }
    // Android 15 imposes a time budget on dataSync services. Persist the pause so the user can resume.
    override fun onTimeout(startId: Int,fgsType: Int) {
        downloadManager.setStopReason(null,HlsDownloads.STOP_REASON_USER)
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
}
