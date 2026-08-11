package lt.oranges.orangchat.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyOutbox @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("notification_outbox", Context.MODE_PRIVATE)
    private val lock = Any()

    data class Entry(val channelId: String, val text: String, val queuedAt: Long)

    fun add(channelId: String, text: String): Entry = synchronized(lock) {
        val entry = Entry(channelId, text, System.currentTimeMillis())
        write((read() + entry).takeLast(MAX_ENTRIES))
        entry
    }

    fun all(): List<Entry> = synchronized(lock) { read() }

    fun isEmpty(): Boolean = synchronized(lock) { read().isEmpty() }

    fun remove(entry: Entry) = synchronized(lock) {
        write(read().filterNot { it == entry })
    }

    private fun read(): List<Entry> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val channelId = item.optString("channelId")
                val text = item.optString("text")
                if (channelId.isBlank() || text.isBlank()) continue
                add(Entry(channelId, text, item.optLong("queuedAt")))
            }
        }
    }

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject()
                .put("channelId", it.channelId)
                .put("text", it.text)
                .put("queuedAt", it.queuedAt))
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private companion object {
        const val KEY = "pending_replies"
        const val MAX_ENTRIES = 50
    }
}
