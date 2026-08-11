package lt.oranges.orangchat.feature.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.DraftBody
import lt.oranges.orangchat.data.repository.E2eeRepository
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageDraftStore @Inject constructor(
    @ApplicationContext context: Context,
    private val api: ApiService,
    private val e2ee: E2eeRepository,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "oc_message_drafts",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirty = Collections.synchronizedSet(mutableSetOf<String>())
    private val timers = mutableMapOf<String, Job>()

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = flush()
        })
    }

    private fun readLocal(channelId: String): String = prefs.getString(channelId, "") ?: ""

    private fun writeLocal(channelId: String, content: String) {
        prefs.edit().apply {
            if (content.isEmpty()) remove(channelId) else putString(channelId, content)
        }.apply()
    }

    private suspend fun push(channelId: String) {
        if (e2ee.shouldEncrypt(channelId)) {
            dirty.remove(channelId)
            runCatching { api.deleteDraft(channelId) }
            return
        }
        val content = readLocal(channelId)
        runCatching { api.putDraft(channelId, DraftBody(content)) }
            .onSuccess { if (readLocal(channelId) == content) dirty.remove(channelId) }
    }

    fun save(channelId: String, content: String) {
        writeLocal(channelId, content)
        dirty.add(channelId)
        synchronized(timers) {
            timers.remove(channelId)?.cancel()
            timers[channelId] = scope.launch {
                delay(SYNC_DEBOUNCE_MS)
                push(channelId)
            }
        }
    }

    fun saveNow(channelId: String, content: String) {
        writeLocal(channelId, content)
        dirty.add(channelId)
        synchronized(timers) { timers.remove(channelId)?.cancel() }
        scope.launch { push(channelId) }
    }

    fun clear(channelId: String) {
        writeLocal(channelId, "")
        dirty.remove(channelId)
        synchronized(timers) { timers.remove(channelId)?.cancel() }
        scope.launch { runCatching { api.deleteDraft(channelId) } }
    }

    suspend fun load(channelId: String): String {
        val local = readLocal(channelId)
        if (local.isNotEmpty()) return local
        if (e2ee.shouldEncrypt(channelId)) return ""
        return runCatching { api.getDraft(channelId).content }.getOrNull().orEmpty()
    }

    fun flush() {
        for (channelId in dirty.toList()) scope.launch { push(channelId) }
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 800L
    }
}
