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

/**
 * Keeps the process alive and the mic/camera usable while a call runs in the
 * background. Android 14+ refuses microphone capture from a backgrounded app
 * without a foreground service of the matching type, so this is not optional.
 */
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
        // Android 14/15 throws SecurityException (and kills the process) when a
        // camera FGS is promoted after the app loses foreground eligibility.
        // CallManager starts us synchronously from the permission-backed user
        // action; this guard is the final protection against a process crash.
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
        // The call, not the system, decides when this ends.
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
