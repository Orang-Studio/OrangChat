package lt.oranges.orangchat.util

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

fun buildImagePart(context: Context, uri: Uri): MultipartBody.Part {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not read that image")
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
    return MultipartBody.Part.createFormData("file", "upload", body)
}
