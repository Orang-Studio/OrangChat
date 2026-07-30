package lt.oranges.orangchat.data.remote

import android.content.ContentResolver
import android.util.Log
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.SealedAttachmentRef
import lt.oranges.orangchat.util.BACKEND_ORIGIN
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns a picked file into an attachment id that [SocketManager.sendMessage] can
 * reference. Which route it takes depends only on size:
 *
 * * **<= 10MB** - posted to OrangChat, which keeps it as long as the message.
 * * **> 10MB** - posted straight to OrangMove, then registered with OrangChat by
 *   token. The bytes never pass through OrangChat, so a big file is only
 *   uploaded once.
 *
 * OrangMove is an ephemeral store - an hour is the longest it keeps anything -
 * so large attachments come back with an `expiresAt` and stop resolving after
 * it. Nothing here can extend that; the UI shows the deadline instead.
 */
@Singleton
class AttachmentUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("upload") private val chatClient: OkHttpClient,
    @Named("orangmove") private val orangMoveClient: OkHttpClient,
    @Named("baseUrl") private val baseUrl: String,
    private val json: Json,
) {

    companion object {
        private const val TAG = "OrangChatUpload"
        /** Must match MAX_LOCAL_ATTACHMENT in server-rs/src/http/attachments.rs. */
        // The encrypted envelope consumes 36 bytes of Cloudinary's 10 MiB cap.
        const val MAX_LOCAL_ATTACHMENT = 10L * 1024 * 1024 - 36
        /** OrangMove's hard ceiling (MAX_SIZE); nothing larger can be sent. */
        const val MAX_ATTACHMENT = 1024L * 1024 * 1024
        /** Mirrors attachmentIds.max(10) in shared/schemas.ts. */
        const val MAX_PER_MESSAGE = 10
        /** OrangMove's MAX_TTL, and so the longest a large file can live. */
        private const val ORANGMOVE_TTL_SECONDS = 3600
    }

    /** What the content resolver can tell us about a picked file. */
    data class FileInfo(val name: String, val size: Long, val mimeType: String?) {
        /** Bound for OrangMove, so it will expire an hour after it's sent. */
        val isEphemeral: Boolean get() = size > MAX_LOCAL_ATTACHMENT
    }

    /**
     * Name and size for a picked uri. Both are needed before uploading: size
     * picks the route, and a file with no size can't be streamed with a
     * Content-Length.
     */
    fun describe(uri: Uri): FileInfo {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }

        return FileInfo(
            name = name ?: uri.lastPathSegment ?: "file",
            // A provider that won't report a size can't be uploaded; 0 makes the
            // caller's own empty-file check report it.
            size = size ?: 0L,
            mimeType = resolver.getType(uri),
        )
    }

    /**
     * Upload one file. [onProgress] reports 0–1 as the bytes go out; cancelling
     * the calling coroutine cancels the request in flight.
     */
    suspend fun upload(
        uri: Uri,
        info: FileInfo = describe(uri),
        onProgress: (Float) -> Unit = {},
    ): Attachment = withContext(Dispatchers.IO) {
        if (info.size <= 0L) throw IOException("\"${info.name}\" is empty")
        if (info.size > MAX_ATTACHMENT) throw IOException("\"${info.name}\" is over the 1GB limit")

        if (info.isEphemeral) uploadViaOrangMove(uri, info, onProgress)
        else uploadToChat(uri, info, onProgress)
    }

    data class SealedUpload(
        val attachment: Attachment,
        val ref: SealedAttachmentRef,
    )

    /**
     * Encrypts before upload. The temporary file contains ciphertext only; the
     * filename, MIME type, key and nonce travel inside the E2EE message payload.
     * Streaming through CipherOutputStream avoids holding a large file in heap.
     */
    suspend fun uploadSealed(
        uri: Uri,
        info: FileInfo = describe(uri),
        onProgress: (Float) -> Unit = {},
    ): SealedUpload = withContext(Dispatchers.IO) {
        if (info.size <= 0L) throw IOException("\"${info.name}\" is empty")
        if (info.size > MAX_ATTACHMENT) throw IOException("\"${info.name}\" is over the 1GB limit")

        val fileId = E2ee.toHex(E2ee.randomBytes(16))
        val key = E2ee.randomBytes(32)
        val nonce = E2ee.randomBytes(12)
        val temp = File.createTempFile("sealed-", ".ocf", context.cacheDir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(E2ee.attachmentAad(fileId))
            context.contentResolver.openInputStream(uri)?.use { input ->
                CipherOutputStream(FileOutputStream(temp), cipher).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            } ?: throw IOException("Could not open \"${info.name}\"")

            val sealedInfo = FileInfo("sealed.ocf", temp.length(), "application/octet-stream")
            val attachment = if (sealedInfo.isEphemeral) {
                uploadFileViaOrangMove(temp, sealedInfo, onProgress)
            } else {
                uploadSealedFileToChat(temp, sealedInfo, onProgress)
            }
            val dimensions = imageDimensions(uri, info.mimeType)
            val thumbnail = createThumbnail(uri, info.mimeType)?.let { plain ->
                try {
                    sealThumbnail(plain)
                } catch (_: Exception) {
                    null
                } finally {
                    plain.delete()
                }
            }
            SealedUpload(
                attachment,
                SealedAttachmentRef(
                    fileId = fileId,
                    attachmentId = attachment.id,
                    key = E2ee.toBase64(key),
                    nonce = E2ee.toBase64(nonce),
                    filename = info.name,
                    contentType = info.mimeType ?: "application/octet-stream",
                    size = info.size,
                    width = dimensions?.first,
                    height = dimensions?.second,
                    thumb = thumbnail,
                ),
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * Makes the preview on-device. Cloudinary never receives readable pixels in
     * an E2EE conversation, so it cannot make this transformation for us.
     */
    private fun createThumbnail(uri: Uri, mimeType: String?): File? {
        if (mimeType?.startsWith("image/") != true) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 800) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return null
        val scale = minOf(1f, 400f / maxOf(decoded.width, decoded.height))
        val width = maxOf(1, (decoded.width * scale).toInt())
        val height = maxOf(1, (decoded.height * scale).toInt())
        val resized = if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true)
        }
        val output = File.createTempFile("thumb-", ".jpg", context.cacheDir)
        return try {
            FileOutputStream(output).use {
                if (!resized.compress(Bitmap.CompressFormat.JPEG, 80, it)) {
                    throw IOException("Could not create image preview")
                }
            }
            output
        } catch (_: Exception) {
            output.delete()
            null
        } finally {
            if (resized !== decoded) resized.recycle()
            decoded.recycle()
        }
    }

    /** Seals and uploads a generated preview as a second opaque blob. */
    private suspend fun sealThumbnail(plain: File): SealedAttachmentRef.Thumb {
        val fileId = E2ee.toHex(E2ee.randomBytes(16))
        val key = E2ee.randomBytes(32)
        val nonce = E2ee.randomBytes(12)
        val sealed = File.createTempFile("sealed-thumb-", ".ocf", context.cacheDir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(E2ee.attachmentAad(fileId))
            FileInputStream(plain).use { input ->
                CipherOutputStream(FileOutputStream(sealed), cipher).use { output ->
                    input.copyTo(output, 32 * 1024)
                }
            }
            val info = FileInfo("sealed.ocf", sealed.length(), "application/octet-stream")
            val attachment = uploadSealedFileToChat(sealed, info) {}
            return SealedAttachmentRef.Thumb(
                fileId = fileId,
                attachmentId = attachment.id,
                key = E2ee.toBase64(key),
                nonce = E2ee.toBase64(nonce),
                contentType = "image/jpeg",
                size = plain.length(),
            )
        } finally {
            sealed.delete()
        }
    }

    private fun imageDimensions(uri: Uri, mimeType: String?): Pair<Int, Int>? {
        if (mimeType?.startsWith("image/") != true) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun body(uri: Uri, info: FileInfo, onProgress: (Float) -> Unit): RequestBody =
        UriRequestBody(
            resolver = context.contentResolver,
            uri = uri,
            size = info.size,
            mediaType = info.mimeType?.toMediaTypeOrNull(),
            onProgress = { sent -> onProgress(sent.toFloat() / info.size) },
        )

    private suspend fun uploadToChat(uri: Uri, info: FileInfo, onProgress: (Float) -> Unit): Attachment {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", info.name, body(uri, info, onProgress))
            .build()

        val request = Request.Builder()
            .url("${baseUrl}uploads/attachment")
            .post(form)
            .build()

        return chatClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorFrom(text, "Upload failed"))
            decodeAttachment(text)
        }
    }

    private suspend fun uploadSealedFileToChat(
        file: File,
        info: FileInfo,
        onProgress: (Float) -> Unit,
    ): Attachment {
        val requestBody = ProgressRequestBody(
            file.asRequestBody(info.mimeType?.toMediaTypeOrNull()),
            file.length(),
            onProgress,
        )
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "sealed.ocf", requestBody)
            .build()
        val request = Request.Builder()
            .url("${baseUrl}uploads/attachment?sealed=1")
            .post(form)
            .build()
        return chatClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorFrom(text, "Encrypted upload failed"))
            decodeAttachment(text)
        }
    }

    private suspend fun uploadViaOrangMove(
        uri: Uri,
        info: FileInfo,
        onProgress: (Float) -> Unit,
    ): Attachment {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // Order matters: OrangMove reads fields as they stream in and wants
            // the ttl before it validates, so it has to precede the file.
            .addFormDataPart("ttl", ORANGMOVE_TTL_SECONDS.toString())
            .addFormDataPart("file", info.name, body(uri, info, onProgress))
            .build()

        val request = Request.Builder()
            .url("$BACKEND_ORIGIN/orangmove/upload")
            .post(form)
            .build()

        val token = orangMoveClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // OrangMove rejects executables by magic bytes and extension, so
                // that's the likeliest way a large upload gets turned away.
                throw IOException(errorFrom(text, "The file service rejected this file"))
            }
            JSONObject(text).getString("token")
        }

        return registerExternal(token)
    }

    private suspend fun uploadFileViaOrangMove(
        file: File,
        info: FileInfo,
        onProgress: (Float) -> Unit,
    ): Attachment {
        val requestBody = ProgressRequestBody(
            file.asRequestBody(info.mimeType?.toMediaTypeOrNull()),
            file.length(),
            onProgress,
        )
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ttl", ORANGMOVE_TTL_SECONDS.toString())
            .addFormDataPart("file", "sealed.ocf", requestBody)
            .build()
        val request = Request.Builder()
            .url("$BACKEND_ORIGIN/orangmove/upload")
            .post(form)
            .build()
        val token = orangMoveClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(errorFrom(text, "The file service rejected the encrypted file"))
            }
            JSONObject(text).getString("token")
        }
        return registerExternal(token)
    }

    /**
     * Hand the token to OrangChat, which reads the file's real name and size back
     * from OrangMove rather than trusting anything sent from here.
     */
    private suspend fun registerExternal(token: String): Attachment {
        val request = Request.Builder()
            .url("${baseUrl}uploads/attachment/external")
            .post(
                JSONObject().put("token", token).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull()),
            )
            .build()

        return chatClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorFrom(text, "Could not attach the file"))
            decodeAttachment(text)
        }
    }

    /**
     * Decode an upload response, saying which body failed when it does.
     *
     * A kotlinx failure here names only a model class, and the response that
     * caused it is gone by the time anyone reads the message - which left an
     * unexplainable upload error with nothing to debug it from. Log the body,
     * then rethrow so callers still see a failure.
     */
    private fun decodeAttachment(text: String): Attachment =
        try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            Log.e(TAG, "could not decode an upload response: $text", e)
            throw e
        }

    /** OrangChat answers with `{"error": ...}`; OrangMove answers in plain text. */
    private fun errorFrom(body: String, fallback: String): String {
        val text = body.trim()
        if (text.isEmpty()) return fallback
        return runCatching { JSONObject(text).getString("error") }
            .getOrElse { if (text.length < 200) text else fallback }
    }
}

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val size: Long,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val forwarding = object : okio.ForwardingSink(sink) {
            var sent = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress((sent.toFloat() / size.coerceAtLeast(1L)).coerceIn(0f, 1f))
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/**
 * Streams the picked file straight from the content provider. Reading it into a
 * ByteArray first would mean holding up to a gigabyte in the heap - an OOM on
 * any real device.
 */
private class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val size: Long,
    private val mediaType: MediaType?,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = size

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file")
        input.use {
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val read = it.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent)
            }
        }
    }
}

/** Suspending [Call.enqueue] that cancels the request when the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // Nobody is left to close it if the caller already walked away.
            if (cont.isCancelled) response.close() else cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            // Cancelling a call surfaces here too; that continuation is gone.
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
