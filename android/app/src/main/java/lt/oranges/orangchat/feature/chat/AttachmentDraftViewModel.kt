package lt.oranges.orangchat.feature.chat
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.data.remote.AttachmentUploader
import lt.oranges.orangchat.data.repository.E2eeRepository
import lt.oranges.orangchat.crypto.SealedAttachmentRef
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AttachmentDraftViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val uploader: AttachmentUploader,
    private val e2ee: E2eeRepository,
) : ViewModel() {

    data class PendingUpload(
        val key: String,
        val name: String,
        val size: Long,
        val ephemeral: Boolean,
        val progress: Float = 0f,
        val attachment: Attachment? = null,
        val sealed: SealedAttachmentRef? = null,
        val error: String? = null,
        val previewUri: Uri? = null,
        val sourceUri: Uri? = null,
        val deleteSourceOnCleanup: Boolean = false,
        val detached: Boolean = false,
    ) {
        val settled: Boolean get() = attachment != null || error != null
    }

    private val _uploads = MutableStateFlow<List<PendingUpload>>(emptyList())
    val uploads: StateFlow<List<PendingUpload>> = _uploads.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun dismissError() { _error.value = null }

    /** One failed upload fails the whole detached message - thrown so the
     * caller can mark it as not sent, same as any other send failure. */
    class AttachmentUploadFailedException(message: String) : Exception(message)

    fun detach(keys: List<String>): suspend () -> Pair<List<String>, List<SealedAttachmentRef>> {
        val batch = keys.toSet()
        _uploads.update { list -> list.map { if (it.key in batch) it.copy(detached = true) else it } }
        return {
            batch.mapNotNull { jobs.remove(it) }.forEach { it.join() }
            val done = _uploads.value.filter { it.key in batch }
            _uploads.update { list -> list.filterNot { it.key in batch } }
            done.forEach(::cleanup)
            val failure = done.firstNotNullOfOrNull { it.error }
            if (failure != null) throw AttachmentUploadFailedException(failure)
            val ids = done.flatMap { listOfNotNull(it.attachment?.id, it.sealed?.thumb?.attachmentId) }
            ids to done.mapNotNull { it.sealed }
        }
    }

    fun add(
        uris: List<Uri>,
        channelId: String?,
        temporaryUris: Set<Uri> = emptySet(),
    ): List<String> {
        if (uris.isEmpty()) return emptyList()
        _error.value = null

        val room = AttachmentUploader.MAX_PER_MESSAGE - _uploads.value.count { !it.detached }
        if (room <= 0) {
            _error.value = "A message can carry at most ${AttachmentUploader.MAX_PER_MESSAGE} attachments"
            return emptyList()
        }
        val accepted = if (uris.size > room) {
            _error.value = "Only the first $room of those fit on one message"
            uris.take(room)
        } else {
            uris
        }

        val created = mutableListOf<String>()
        for (uri in accepted) {
            val key = UUID.randomUUID().toString()
            val info = runCatching { uploader.describe(uri) }.getOrNull()
            if (info == null) {
                _error.value = "Could not read that file"
                continue
            }

            _uploads.update {
                it + PendingUpload(
                    key = key,
                    name = info.name,
                    size = info.size,
                    ephemeral = info.isEphemeral,
                    previewUri = uri.takeIf { _ -> info.mimeType?.startsWith("image/") == true },
                    sourceUri = uri,
                    deleteSourceOnCleanup = uri in temporaryUris,
                )
            }
            created += key

            jobs[key] = viewModelScope.launch {
                runCatching {
                    if (channelId != null && e2ee.shouldEncrypt(channelId)) {
                        val sealed = uploader.uploadSealed(uri, info) { fraction ->
                            patch(key) { it.copy(progress = fraction) }
                        }
                        sealed.attachment to sealed.ref
                    } else {
                        uploader.upload(uri, info) { fraction ->
                            patch(key) { it.copy(progress = fraction) }
                        } to null
                    }
                }
                    .onSuccess { (attachment, sealed) ->
                        patch(key) {
                            it.copy(
                                attachment = attachment,
                                sealed = sealed,
                                progress = 1f,
                            )
                        }
                    }
                    .onFailure { cause ->
                        if (cause is CancellationException) return@onFailure
                        Log.e("OrangChatUpload", "upload failed for ${info.name}", cause)
                        val shown = if (cause is kotlinx.serialization.SerializationException) {
                            "Upload failed"
                        } else {
                            cause.message?.takeIf { it.isNotBlank() } ?: AppStrings.get(context, R.string.catalog_upload_failed_ad0d0603)
                        }
                        patch(key) { it.copy(error = shown) }
                    }
            }
        }
        return created
    }

    fun remove(key: String) {
        jobs.remove(key)?.cancel()
        val removed = _uploads.value.firstOrNull { it.key == key }
        cleanup(removed)
        _uploads.update { list -> list.filterNot { it.key == key } }
    }

    fun clear() {
        val dropped = _uploads.value.filterNot { it.detached }
        dropped.forEach { jobs.remove(it.key)?.cancel() }
        dropped.forEach(::cleanup)
        _uploads.update { list -> list.filter { it.detached } }
        _error.value = null
    }

    override fun onCleared() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _uploads.value.forEach(::cleanup)
        _uploads.value = emptyList()
        super.onCleared()
    }

    private fun cleanup(upload: PendingUpload?) {
        if (upload?.deleteSourceOnCleanup != true) return
        upload.sourceUri?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private fun patch(key: String, block: (PendingUpload) -> PendingUpload) {
        _uploads.update { list -> list.map { if (it.key == key) block(it) else it } }
    }
}
