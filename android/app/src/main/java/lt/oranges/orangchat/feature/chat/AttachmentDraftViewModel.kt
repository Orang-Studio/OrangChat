package lt.oranges.orangchat.feature.chat

import android.net.Uri
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

/**
 * The attachments on the message being composed, from pick through to upload.
 *
 * Uploading starts the moment a file is picked rather than at send: a large file
 * is usually already up by the time the user finishes typing, and a failure
 * shows up while they can still do something about it instead of after they hit
 * send.
 */
@HiltViewModel
class AttachmentDraftViewModel @Inject constructor(
    private val uploader: AttachmentUploader,
    private val e2ee: E2eeRepository,
) : ViewModel() {

    data class PendingUpload(
        val key: String,
        val name: String,
        val size: Long,
        /** Headed for OrangMove, so it expires an hour after it's sent. */
        val ephemeral: Boolean,
        /** 0–1 while the bytes go out. */
        val progress: Float = 0f,
        /** Set once uploaded; its id is what the message references. */
        val attachment: Attachment? = null,
        /** Present only for E2EE uploads; sealed into the message payload. */
        val sealed: SealedAttachmentRef? = null,
        val error: String? = null,
        /** The content uri, for thumbnailing images before they're up. */
        val previewUri: Uri? = null,
    ) {
        val settled: Boolean get() = attachment != null || error != null
    }

    private val _uploads = MutableStateFlow<List<PendingUpload>>(emptyList())
    val uploads: StateFlow<List<PendingUpload>> = _uploads.asStateFlow()

    /** Surfaced for things that fail before an upload starts (too many files). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    val uploading: Boolean get() = _uploads.value.any { !it.settled }
    val readyIds: List<String>
        get() = _uploads.value.flatMap { upload ->
            listOfNotNull(upload.attachment?.id, upload.sealed?.thumb?.attachmentId)
        }
    val readySealed: List<SealedAttachmentRef> get() = _uploads.value.mapNotNull { it.sealed }
    val hasFailures: Boolean get() = _uploads.value.any { it.error != null }

    fun dismissError() { _error.value = null }

    fun add(uris: List<Uri>, channelId: String?) {
        if (uris.isEmpty()) return
        _error.value = null

        val room = AttachmentUploader.MAX_PER_MESSAGE - _uploads.value.size
        if (room <= 0) {
            _error.value = "A message can carry at most ${AttachmentUploader.MAX_PER_MESSAGE} attachments"
            return
        }
        val accepted = if (uris.size > room) {
            _error.value = "Only the first $room of those fit on one message"
            uris.take(room)
        } else {
            uris
        }

        for (uri in accepted) {
            val key = UUID.randomUUID().toString()
            // describe() touches the content resolver but only reads a metadata
            // row, so it's cheap enough to do before showing the chip.
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
                )
            }

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
                        // Cancelling is the user's own doing; remove() already
                        // dropped the chip, so there's nothing to report.
                        if (cause is CancellationException) return@onFailure
                        patch(key) { it.copy(error = cause.message ?: "Upload failed") }
                    }
            }
        }
    }

    fun remove(key: String) {
        jobs.remove(key)?.cancel()
        _uploads.update { list -> list.filterNot { it.key == key } }
    }

    /** Drop the whole draft - sent, or the channel changed under it. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _uploads.value = emptyList()
        _error.value = null
    }

    private fun patch(key: String, block: (PendingUpload) -> PendingUpload) {
        _uploads.update { list -> list.map { if (it.key == key) block(it) else it } }
    }
}
