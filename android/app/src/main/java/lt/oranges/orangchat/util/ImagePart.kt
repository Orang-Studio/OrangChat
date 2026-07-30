package lt.oranges.orangchat.util

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Read a picked image into the multipart part `POST /uploads/image` expects.
 * Blocking IO - call from a background dispatcher.
 */
fun buildImagePart(context: Context, uri: Uri): MultipartBody.Part {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not read that image")
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
    // The server derives the real format from the bytes; the name is cosmetic.
    return MultipartBody.Part.createFormData("file", "upload", body)
}
