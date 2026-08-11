package lt.oranges.orangchat.feature.chat

import lt.oranges.orangchat.R
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
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.crypto.SealedAttachmentRef
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.inlineUrl
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


private fun needsLegacyStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED

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
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName(attachment.filename))

    val queued = runCatching { manager.enqueue(request) }
    Toast.makeText(
        context,
        if (queued.isSuccess) "Downloading ${attachment.filename}" else "Couldn't start the download",
        Toast.LENGTH_SHORT,
    ).show()
}

private const val MAX_INLINE_SEALED = 64L * 1024 * 1024

private const val MAX_FETCH_ATTEMPTS = 5

private const val DECRYPT_CHUNK = 1 shl 20

internal enum class SealedPhase { DOWNLOAD, DECRYPT }

internal data class SealedProgress(
    val phase: SealedPhase = SealedPhase.DOWNLOAD,
    val fraction: Float = 0f,
)

internal object SealedFiles {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<File?>>()

    private val _progress = MutableStateFlow<Map<String, SealedProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SealedProgress>> = _progress.asStateFlow()

    suspend fun open(context: Context, attachment: Attachment, ref: SealedAttachmentRef): File? {
        val opened = File(context.cacheDir, "opened-${attachment.id}")
        if (opened.isFile && opened.length() == ref.size) return opened

        val job = lock.withLock {
            inFlight.getOrPut(attachment.id) {
                val app = context.applicationContext
                scope.async {
                    try {
                        fetchAndOpen(app, attachment, ref, opened)
                    } finally {
                        lock.withLock { inFlight.remove(attachment.id) }
                        _progress.update { it - attachment.id }
                    }
                }
            }
        }
        return job.await()
    }

    private fun fetchAndOpen(
        context: Context,
        attachment: Attachment,
        ref: SealedAttachmentRef,
        opened: File,
    ): File? {
        val absolute = absoluteUrl(attachment.url) ?: return null
        val part = File(context.cacheDir, "sealed-${attachment.id}.part")
        if (!fetch(absolute, part) { fraction ->
                _progress.update {
                    it + (attachment.id to SealedProgress(SealedPhase.DOWNLOAD, fraction))
                }
            }
        ) {
            return null
        }
        val result = decrypt(part, ref, opened, context.cacheDir) { fraction ->
            _progress.update {
                it + (attachment.id to SealedProgress(SealedPhase.DECRYPT, fraction))
            }
        }
        part.delete()
        return result
    }

    private fun fetch(url: String, part: File, onProgress: (Float) -> Unit): Boolean {
        var total = -1L
        repeat(MAX_FETCH_ATTEMPTS) {
            var have = if (part.isFile) part.length() else 0L
            if (total > 0 && have >= total) return true

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            var drained = false
            try {
                val code = connection.responseCode
                val resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (code != HttpURLConnection.HTTP_OK && !resuming) return false
                total = if (resuming) {
                    connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: -1L
                } else {
                    if (have > 0) {
                        part.delete()
                        have = 0
                    }
                    connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                }

                var written = have
                FileOutputStream(part, resuming).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                drained = true
            } catch (_: Exception) {
            } finally {
                connection.disconnect()
            }

            if (total > 0) {
                if (part.isFile && part.length() >= total) return true
            } else if (drained) {
                return part.isFile && part.length() > 0
            }
        }
        return false
    }

    private fun decrypt(
        part: File,
        ref: SealedAttachmentRef,
        opened: File,
        cacheDir: File,
        onProgress: (Float) -> Unit,
    ): File? {
        val temp = File.createTempFile("opening-", ".part", cacheDir)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(E2ee.fromBase64(ref.key), "AES"),
                GCMParameterSpec(128, E2ee.fromBase64(ref.nonce)),
            )
            cipher.updateAAD(E2ee.attachmentAad(ref.fileId))
            val total = part.length()
            var consumed = 0L
            part.inputStream().use { encrypted ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DECRYPT_CHUNK)
                    while (true) {
                        val read = encrypted.read(buffer)
                        if (read <= 0) break
                        cipher.update(buffer, 0, read)?.let(output::write)
                        consumed += read
                        if (total > 0) {
                            onProgress((consumed.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                    cipher.doFinal()?.let(output::write)
                }
            }
            if (!temp.renameTo(opened)) {
                temp.copyTo(opened, overwrite = true)
                temp.delete()
            }
            opened
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }
}

private suspend fun decryptToCache(
    context: Context,
    attachment: Attachment,
    keystore: E2eeKeystore,
): File? {
    val ref = keystore.sealedAttachment(attachment.id) ?: return null
    return SealedFiles.open(context, attachment, ref)
}

internal data class AttachmentSource(
    val url: String?,
    val resolving: Boolean,
    val deferred: Boolean = false,
    val progress: Float = 0f,
    val phase: SealedPhase = SealedPhase.DOWNLOAD,
) {
    val unavailable: Boolean get() = url == null && !resolving && !deferred

    val phaseLabel: String
        get() = if (phase == SealedPhase.DECRYPT) "Decrypting" else "Downloading"
}

@Composable
internal fun rememberAttachmentSource(
    attachment: Attachment,
    wanted: Boolean = true,
): AttachmentSource {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keystore = remember(context) { E2eeKeystore.get(context) }
    val ref = remember(attachment.id) { keystore.sealedAttachment(attachment.id) }
    val plain = remember(attachment.url) { absoluteUrl(inlineUrl(attachment.url)) }
    val fetching by SealedFiles.progress.collectAsState()

    if (ref == null) return AttachmentSource(plain, resolving = false)
    if (ref.size > MAX_INLINE_SEALED) {
        return AttachmentSource(null, resolving = false, deferred = true)
    }

    var opened by remember(attachment.id) { mutableStateOf<String?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }

    LaunchedEffect(attachment.id, wanted) {
        if (!wanted || opened != null || failed) return@LaunchedEffect
        val file = decryptToCache(context, attachment, keystore)
        if (file == null) failed = true else opened = Uri.fromFile(file).toString()
    }

    return when {
        opened != null -> AttachmentSource(opened, resolving = false)
        failed -> AttachmentSource(null, resolving = false)
        wanted -> {
            val step = fetching[attachment.id] ?: SealedProgress()
            AttachmentSource(
                null,
                resolving = true,
                progress = step.fraction,
                phase = step.phase,
            )
        }
        else -> AttachmentSource(null, resolving = false, deferred = true)
    }
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

@Composable
fun rememberAttachmentDownloader(): (Attachment) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                val app = context.applicationContext
                Toast.makeText(app, "Saving ${attachment.filename}…", Toast.LENGTH_SHORT).show()
                SealedFiles.saves.launch {
                    val saved = saveOpenedAttachment(app, attachment)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            app,
                            if (saved) "Saved ${attachment.filename}" else AppStrings.get(context, R.string.catalog_couldn_t_decrypt_this_file_08287a15),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
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
