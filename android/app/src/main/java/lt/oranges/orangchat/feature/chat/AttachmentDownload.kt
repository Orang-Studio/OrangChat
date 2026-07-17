package lt.oranges.orangchat.feature.chat

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.util.absoluteUrl

/**
 * Saving an attachment to the phone's Downloads folder.
 *
 * DownloadManager rather than a hand-rolled fetch: it survives the app being
 * backgrounded or killed, shows its own progress notification, and puts the
 * file somewhere other apps can open — all of which a 1GB attachment needs and
 * none of which is worth rebuilding here.
 *
 * Attachment URLs carry no session (nginx serves `/attachments/` and the
 * OrangMove proxy straight off disk), so handing one to another process
 * downloads what it says and leaks nothing.
 */

/** Before Android 10, writing to the public Downloads folder is a permission. */
private fun needsLegacyStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED

/**
 * The server already strips separators from uploaded names, but this one is
 * about to become a path — belt and braces, since the cost is a `replace`.
 */
private fun safeName(filename: String): String {
    val cleaned = filename.replace(Regex("""[/\\]"""), "_").trim()
    return cleaned.ifEmpty { "attachment" }
}

private fun enqueue(context: Context, attachment: Attachment) {
    val url = absoluteUrl(attachment.url)
    if (url == null) {
        Toast.makeText(context, "This file is no longer available", Toast.LENGTH_SHORT).show()
        return
    }
    val manager = context.getSystemService(DownloadManager::class.java)
    if (manager == null) {
        Toast.makeText(context, "Downloads are unavailable on this device", Toast.LENGTH_SHORT).show()
        return
    }

    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(attachment.filename)
        .setDescription("OrangChat attachment")
        .setMimeType(attachment.contentType)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Name collisions are DownloadManager's problem; it appends a counter.
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName(attachment.filename))

    // Enqueueing can fail outright — the Downloads app may be disabled, and the
    // external volume may not be mounted.
    val queued = runCatching { manager.enqueue(request) }
    Toast.makeText(
        context,
        if (queued.isSuccess) "Downloading ${attachment.filename}" else "Couldn't start the download",
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * Returns a callback that saves an attachment, asking for the legacy storage
 * permission first where that's still a thing and resuming the download once
 * it's answered.
 */
@Composable
fun rememberAttachmentDownloader(): (Attachment) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<Attachment?>(null) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pending
        pending = null
        when {
            target == null -> Unit
            granted -> enqueue(context, target)
            else -> Toast.makeText(
                context,
                "Storage access is needed to save files",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    return remember(context) {
        { attachment ->
            if (needsLegacyStoragePermission(context)) {
                pending = attachment
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueue(context, attachment)
            }
        }
    }
}
