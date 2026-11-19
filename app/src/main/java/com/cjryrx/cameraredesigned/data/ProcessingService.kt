package com.cjryrx.cameraredesigned.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cjryrx.cameraredesigned.CameraApplication
import com.cjryrx.cameraredesigned.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue get() = (application as CameraApplication).processing
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "Photo processing", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun notification(count: Int) = NotificationCompat.Builder(this, "processing")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Camera Redesigned")
            .setContentText(if (count > 0) "Saving / processing: $count" else "Completing photo writes")
            .setContentIntent(launch).setOngoing(true).build()
        val serviceType = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(21, notification(0), serviceType)
        scope.launch {
            queue.state.collectLatest {
                if (it.count == 0) { delay(1500); queue.serviceStopping(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else manager.notify(21, notification(it.count))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { queue.serviceStopping(); scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
}
