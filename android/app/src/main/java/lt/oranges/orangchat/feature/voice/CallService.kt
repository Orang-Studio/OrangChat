package lt.oranges.orangchat.feature.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import lt.oranges.orangchat.notifications.NotificationHelper
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var notificationHelper: NotificationHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.buildOngoingCallNotification()
        val cameraRequested = intent?.getBooleanExtra(EXTRA_CAMERA, false) == true
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (cameraRequested && cameraGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ONGOING_CALL_ID,
                notification,
                type,
            )
        } catch (_: SecurityException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_CAMERA = "camera"

        fun start(context: Context, camera: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra(EXTRA_CAMERA, camera),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
