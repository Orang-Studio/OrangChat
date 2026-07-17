package lt.oranges.orangchat.feature.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.LinkPreviewData
import javax.inject.Inject

@HiltViewModel
class LinkPreviewViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {
    private val cache = mutableMapOf<String, LinkPreviewData?>()

    suspend fun preview(url: String): LinkPreviewData? {
        if (cache.containsKey(url)) return cache[url]
        val result = runCatching { api.getLinkPreview(url) }.getOrNull()
        cache[url] = result
        return result
    }
}
