package lt.oranges.orangchat.feature.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingShare(
    val text: String = "",
    val uris: List<Uri> = emptyList(),
    val channelId: String? = null,
)

@Singleton
class PendingShareStore @Inject constructor() {
    private val _share = MutableStateFlow<PendingShare?>(null)
    val share: StateFlow<PendingShare?> = _share.asStateFlow()

    fun offer(share: PendingShare) { _share.value = share }
    fun consume() { _share.value = null }
}
