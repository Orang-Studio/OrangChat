package lt.oranges.orangchat.feature.chat

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.util.absoluteUrl
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec

/**
 * Saving an attachment to the phone's Downloads folder.
 *
 * DownloadManager rather than a hand-rolled fetch: it survives the app being
 * backgrounded or killed, shows its own progress notification, and puts the
 * file somewhere other apps can open - all of which a 1GB attachment needs and
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
 * about to become a path - belt and braces, since the cost is a `replace`.
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

    // Enqueueing can fail outright - the Downloads app may be disabled, and the
    // external volume may not be mounted.
    val queued = runCatching { manager.enqueue(request) }
    Toast.makeText(
        context,
        if (queued.isSuccess) "Downloading ${attachment.filename}" else "Couldn't start the download",
        Toast.LENGTH_SHORT,
    ).show()
}

private const val MAX_INLINE_SEALED = 64L * 1024 * 1024

private fun decryptToCache(
    context: Context,
    attachment: Attachment,
    keystore: E2eeKeystore,
): File? {
    val ref = keystore.sealedAttachment(attachment.id) ?: return null
    val cached = File(context.cacheDir, "opened-${attachment.id}")
    if (cached.isFile && cached.length() == ref.size) return cached
    val absolute = absoluteUrl(attachment.url) ?: return null
    val temp = File.createTempFile("opening-", ".part", context.cacheDir)
    return try {
        val connection = URL(absolute).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.inputStream.use { encrypted ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(E2ee.fromBase64(ref.key), "AES"),
                GCMParameterSpec(128, E2ee.fromBase64(ref.nonce)),
            )
            cipher.updateAAD(E2ee.attachmentAad(ref.fileId))
            // Plaintext only becomes visible outside the private temp file after
            // EOF verifies the GCM tag successfully.
            CipherInputStream(encrypted, cipher).use { opened ->
                FileOutputStream(temp).use { output -> opened.copyTo(output, 64 * 1024) }
            }
        }
        if (!temp.renameTo(cached)) {
            temp.copyTo(cached, overwrite = true)
            temp.delete()
        }
        cached
    } catch (_: Exception) {
        temp.delete()
        null
    }
}

/**
 * Where an attachment's bytes can be read from right now.
 *
 * A sealed file has no url until it has been fetched and opened, which is work
 * and therefore takes time. "Not yet" and "never" have to be told apart by
 * anything that renders it: treating the gap before decryption as a failure is
 * what turned every encrypted image into a download card.
 */
internal data class AttachmentSource(val url: String?, val resolving: Boolean) {
    val unavailable: Boolean get() = url == null && !resolving
}

/** Local file URL for inline media up to 64 MiB; larger files stay download-only. */
@Composable
internal fun rememberAttachmentSource(attachment: Attachment): AttachmentSource {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keystore = remember(context) { E2eeKeystore.get(context) }
    val ref = remember(attachment.id) { keystore.sealedAttachment(attachment.id) }
    var source by remember(attachment.id, attachment.url) {
        mutableStateOf(
            if (ref == null) AttachmentSource(absoluteUrl(attachment.url), resolving = false)
            else AttachmentSource(null, resolving = ref.size <= MAX_INLINE_SEALED),
        )
    }
    LaunchedEffect(attachment.id, attachment.url) {
        source = if (ref == null) {
            AttachmentSource(absoluteUrl(attachment.url), resolving = false)
        } else if (ref.size > MAX_INLINE_SEALED) {
            AttachmentSource(null, resolving = false)
        } else {
            AttachmentSource(
                withContext(Dispatchers.IO) {
                    // Uri.fromFile, not File.toURI: the latter writes file:/path
                    // with one slash, which not every loader parses back.
                    decryptToCache(context, attachment, keystore)
                        ?.let { Uri.fromFile(it).toString() }
                },
                resolving = false,
            )
        }
    }
    return source
}

@Composable
internal fun rememberResolvedAttachmentUrl(attachment: Attachment): String? =
    rememberAttachmentSource(attachment).url

private suspend fun saveOpenedAttachment(context: Context, attachment: Attachment): Boolean =
    withContext(Dispatchers.IO) {
        val keystore = E2eeKeystore.get(context)
        val opened = decryptToCache(context, attachment, keystore) ?: return@withContext false
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(attachment.filename))
            put(MediaStore.Downloads.MIME_TYPE, attachment.contentType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return@withContext false
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                opened.inputStream().use { it.copyTo(output) }
            } ?: return@withContext false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            false
        }
    }

/**
 * Returns a callback that saves an attachment, asking for the legacy storage
 * permission first where that's still a thing and resuming the download once
 * it's answered.
 */
@Composable
fun rememberAttachmentDownloader(): (Attachment) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val e2eeKeystore = remember(context) { E2eeKeystore.get(context) }
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
            if (e2eeKeystore.sealedAttachment(attachment.id) != null) {
                scope.launch {
                    val saved = saveOpenedAttachment(context, attachment)
                    Toast.makeText(
                        context,
                        if (saved) "Saved ${attachment.filename}" else "Couldn't decrypt this file",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else if (needsLegacyStoragePermission(context)) {
                pending = attachment
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueue(context, attachment)
            }
        }
    }
}
