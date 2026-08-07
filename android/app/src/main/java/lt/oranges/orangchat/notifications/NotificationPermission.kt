package lt.oranges.orangchat.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether this app may post notifications at all. Below Android 13 there is no
 * runtime permission to hold, so the answer is always yes.
 *
 * Free-standing rather than only a [NotificationHelper] method because the UI
 * asks the same question - the first-run prompt and the "notifications are
 * off" row in Privacy settings both need it, and neither has a reason to reach
 * for the whole helper.
 */
fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * [hasNotificationPermission] as composable state, re-read on every resume.
 *
 * The permission is granted and revoked in system settings, outside this
 * process, and nothing tells the app when it changes. Reading it once at
 * composition would leave a screen insisting notifications are off moments
 * after the user turned them on and walked straight back.
 */
@Composable
fun rememberNotificationPermissionState(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = hasNotificationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

/**
 * The system's notification screen for this app - where someone who denied the
 * permission, or later revoked it, can hand it back.
 *
 * Below Android 8 there is no per-app notification screen, so this falls back
 * to the app's details page, which has the same controls a version further in.
 */
fun appNotificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
