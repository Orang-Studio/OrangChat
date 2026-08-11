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

/** Give up rather than retry forever on a connection that keeps dying. */
private const val MAX_FETCH_ATTEMPTS = 5

/**
 * How much ciphertext is handed to the cipher at a time.
 *
 * Not a tuning knob so much as the whole fix. `CipherInputStream` reads its
 * source 512 bytes at a time no matter what buffer the caller copies with, and
 * Android's GCM cipher is one-shot underneath (BoringSSL's EVP_AEAD), so every
 * `update` appends to an internal buffer that is regrown to the exact new size.
 * 512-byte increments over a 30 MB file is sixty thousand reallocations copying
 * fifteen megabytes on average - hundreds of gigabytes of memcpy to decrypt one
 * clip, which is where the two minutes went. Feeding it a megabyte at a time
 * leaves thirty of those copies.
 */
private const val DECRYPT_CHUNK = 1 shl 20

/** Which half of "opening a sealed file" is currently running. */
internal enum class SealedPhase { DOWNLOAD, DECRYPT }

/** How far along one sealed attachment is, and at what. */
internal data class SealedProgress(
    val phase: SealedPhase = SealedPhase.DOWNLOAD,
    val fraction: Float = 0f,
)

/**
 * Opening sealed attachments, once each, off the composition's lifetime.
 *
 * Three things went wrong here and they compounded. Every composable that
 * rendered the file - the image, the video, the lightbox, the download button -
 * started its own decrypt; each one ran inside a `LaunchedEffect`, so leaving
 * the composition (a scroll, a recomposition, opening the lightbox) cancelled
 * the transfer; and each restart began at byte zero. A 39 MB attachment was
 * requested sixteen times and never once arrived: the server logs show a stack
 * of concurrent reads for the same file, each cut off after a few megabytes.
 *
 * So: one job per attachment, shared by every caller; run in a scope that
 * outlives the composables watching it, so a cancelled *observer* no longer
 * cancels the *work*; and the ciphertext lands in a `.part` file that a dropped
 * connection resumes from with a `Range` request instead of starting over.
 */
internal object SealedFiles {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** For saving to Downloads, which must also outlive the screen it started on. */
    val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<File?>>()

    /** Per attachment id, how far it has got and whether it is still fetching. */
    private val _progress = MutableStateFlow<Map<String, SealedProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SealedProgress>> = _progress.asStateFlow()

    /**
     * The decrypted file, fetching and decrypting it if this is the first ask.
     * Cancelling the caller abandons the wait, never the download.
     */
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

    /**
     * Downloads to [part], resuming it if some of the file is already there.
     *
     * How long the file is comes from the response, never from the attachment
     * row: by the time a sealed attachment is rendered its `size` has been
     * replaced with the *plaintext* length from inside the message, which is
     * short of the ciphertext by the GCM tag. Stopping there would leave every
     * file a few bytes shy and fail the tag on decrypt.
     */
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
                    // "bytes 1024-2047/4096" - the whole length is after the slash.
                    connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: -1L
                } else {
                    // A server that ignored the range sends the whole file again.
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
                // Whatever arrived stays on disk; the next attempt resumes it.
            } finally {
                connection.disconnect()
            }

            if (total > 0) {
                if (part.isFile && part.length() >= total) return true
            } else if (drained) {
                // No length to check against, but the stream ended cleanly.
                return part.isFile && part.length() > 0
            }
        }
        return false
    }

    /**
     * Decrypts [part] into [opened], reporting how much ciphertext has been fed
     * through so a large clip shows movement rather than a frozen bar.
     *
     * Deliberately not `CipherInputStream` - see [DECRYPT_CHUNK] for why that
     * combination is quadratic. Feeding the cipher directly also means the
     * fraction here is the real unit of work, not a guess.
     */
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
            // Plaintext only becomes visible outside the private temp file after
            // doFinal verifies the GCM tag successfully.
            part.inputStream().use { encrypted ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DECRYPT_CHUNK)
                    while (true) {
                        val read = encrypted.read(buffer)
                        if (read <= 0) break
                        // Providers that buffer internally return nothing here
                        // and hand everything back from doFinal; both are fine.
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

/**
 * Where an attachment's bytes can be read from right now.
 *
 * A sealed file has no url until it has been fetched and opened, which is work
 * and therefore takes time. "Not yet" and "never" have to be told apart by
 * anything that renders it: treating the gap before decryption as a failure is
 * what turned every encrypted image into a download card.
 */
internal data class AttachmentSource(
    val url: String?,
    val resolving: Boolean,
    /** Sealed, and nobody has asked for it yet - a tap is all it needs. */
    val deferred: Boolean = false,
    /** 0-1 through whatever [phase] is currently doing. */
    val progress: Float = 0f,
    /**
     * Downloading or decrypting. Worth telling apart: decrypting a large clip
     * takes long enough that a bar labelled "downloading" sitting at 100% is
     * what made it look stuck.
     */
    val phase: SealedPhase = SealedPhase.DOWNLOAD,
) {
    val unavailable: Boolean get() = url == null && !resolving && !deferred

    /** A word for what is happening, for anything with room to say it. */
    val phaseLabel: String
        get() = if (phase == SealedPhase.DECRYPT) "Decrypting" else "Downloading"
}

/**
 * Where to read [attachment] from, decrypting it first when it is sealed.
 *
 * [wanted] is what keeps a channel of clips from fetching itself: a sealed
 * video or sound is not touched until its play button is pressed. It has no
 * effect on a file that is not sealed - there is nothing to fetch ahead of
 * time, the player streams those itself when it starts.
 */
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
        // Uri.fromFile, not File.toURI: the latter writes file:/path with one
        // slash, which not every loader parses back.
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

/**
 * Returns a callback that saves an attachment, asking for the legacy storage
 * permission first where that's still a thing and resuming the download once
 * it's answered.
 */
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
                // Deliberately not the composition's scope: saving a large file
                // takes long enough to outlive the screen it was started from,
                // and cancelling it there is what made the download button on a
                // big attachment look like it did nothing at all.
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
