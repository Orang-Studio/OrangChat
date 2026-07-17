package lt.oranges.orangchat.data.remote

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Attachment
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
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns a picked file into an attachment id that [SocketManager.sendMessage] can
 * reference. Which route it takes depends only on size:
 *
 * * **<= 10MB** — posted to OrangChat, which keeps it as long as the message.
 * * **> 10MB** — posted straight to OrangMove, then registered with OrangChat by
 *   token. The bytes never pass through OrangChat, so a big file is only
 *   uploaded once.
 *
 * OrangMove is an ephemeral store — an hour is the longest it keeps anything —
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
            json.decodeFromString<Attachment>(text)
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
            json.decodeFromString<Attachment>(text)
        }
    }

    /** OrangChat answers with `{"error": ...}`; OrangMove answers in plain text. */
    private fun errorFrom(body: String, fallback: String): String {
        val text = body.trim()
        if (text.isEmpty()) return fallback
        return runCatching { JSONObject(text).getString("error") }
            .getOrElse { if (text.length < 200) text else fallback }
    }
}

/**
 * Streams the picked file straight from the content provider. Reading it into a
 * ByteArray first would mean holding up to a gigabyte in the heap — an OOM on
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
