package lt.oranges.orangchat.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

const val MUTE_FOREVER: Long = Long.MAX_VALUE

enum class MuteDuration(val millis: Long) {
    FIFTEEN_MINUTES(15 * 60_000L),
    ONE_HOUR(60 * 60_000L),
    EIGHT_HOURS(8 * 60 * 60_000L),
    ONE_DAY(24 * 60 * 60_000L),
    FOREVER(MUTE_FOREVER),
}

fun Long?.isActiveMute(): Boolean =
    this != null && (this == MUTE_FOREVER || this > System.currentTimeMillis())

@Singleton
class NotificationMuteStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("oc-mutes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val mutableMutes = MutableStateFlow(read(KEY_MUTES))
    val mutes: StateFlow<Map<String, Long>> = mutableMutes.asStateFlow()

    private var channelServers: Map<String, String> = readChannels()

    fun isMuted(id: String): Boolean {
        val until = mutableMutes.value[id] ?: return false
        if (until == MUTE_FOREVER) return true
        if (until > System.currentTimeMillis()) return true
        unmute(id)
        return false
    }

    fun isChannelMuted(channelId: String): Boolean {
        if (isMuted(channelId)) return true
        val serverId = channelServers[channelId] ?: return false
        return isMuted(serverId)
    }

    fun mute(id: String, duration: MuteDuration) {
        val until = if (duration == MuteDuration.FOREVER) {
            MUTE_FOREVER
        } else {
            System.currentTimeMillis() + duration.millis
        }
        write(mutableMutes.value + (id to until))
    }

    fun unmute(id: String) {
        write(mutableMutes.value - id)
    }

    fun indexChannels(serverId: String, channelIds: List<String>) {
        if (channelIds.isEmpty()) return
        val next = channelServers.toMutableMap()
        channelIds.forEach { next[it] = serverId }
        if (next == channelServers) return
        channelServers = next
        prefs.edit().putString(KEY_CHANNELS, json.encodeToString(next)).apply()
    }

    private fun write(next: Map<String, Long>) {
        mutableMutes.value = next
        prefs.edit().putString(KEY_MUTES, json.encodeToString(next)).apply()
    }

    private fun read(key: String): Map<String, Long> = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString<Map<String, Long>>(it) }
    }.getOrNull().orEmpty()

    private fun readChannels(): Map<String, String> = runCatching {
        prefs.getString(KEY_CHANNELS, null)?.let { json.decodeFromString<Map<String, String>>(it) }
    }.getOrNull().orEmpty()

    private companion object {
        const val KEY_MUTES = "muted_until"
        const val KEY_CHANNELS = "channel_servers"
    }
}
