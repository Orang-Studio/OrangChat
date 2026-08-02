package lt.oranges.orangchat.data.local

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequest
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.UnreadState

/**
 * Encrypted, account-scoped snapshots used to paint the authenticated shell
 * before the network answers and to keep recent conversations readable when it
 * cannot answer at all.
 *
 * Files live under noBackupFilesDir: they are device-local copies, not an
 * account backup, and every byte is sealed with a key held by Android Keystore.
 */
@Singleton
class OfflineCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    @Serializable
    data class Navigation(
        val servers: List<Server> = emptyList(),
        val dms: List<Conversation> = emptyList(),
        val friends: List<Friend> = emptyList(),
        val incomingRequests: List<FriendRequest> = emptyList(),
        val outgoingRequests: List<FriendRequest> = emptyList(),
        val unreads: List<UnreadState> = emptyList(),
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "offline-cache")
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private val navigationHot = mutableMapOf<String, Navigation>()

    suspend fun navigation(userId: String): Navigation? = withCacheLock {
        navigationHot[userId] ?: read<Navigation>(accountDir(userId).resolve(NAVIGATION_FILE))
            ?.also { navigationHot[userId] = it }
    }

    suspend fun storeServers(userId: String, servers: List<Server>) =
        updateNavigation(userId) { it.copy(servers = servers) }

    suspend fun storeDms(userId: String, dms: List<Conversation>) =
        updateNavigation(userId) { it.copy(dms = dms) }

    suspend fun storeFriends(
        userId: String,
        friends: List<Friend>,
        incoming: List<FriendRequest>,
        outgoing: List<FriendRequest>,
    ) = updateNavigation(userId) {
        it.copy(friends = friends, incomingRequests = incoming, outgoingRequests = outgoing)
    }

    suspend fun storeNavigation(userId: String, navigation: Navigation) = withCacheLock {
        navigationHot[userId] = navigation
        write(accountDir(userId).resolve(NAVIGATION_FILE), navigation)
    }

    suspend fun serverDetail(userId: String, serverId: String): ServerDetail? = withCacheLock {
        read(accountDir(userId).resolve("server-${key(serverId)}.json"))
    }

    suspend fun storeServerDetail(userId: String, detail: ServerDetail) = withCacheLock {
        write(accountDir(userId).resolve("server-${key(detail.server.id)}.json"), detail)
    }

    suspend fun messages(userId: String, channelId: String): List<Message> = withCacheLock {
        read<MessageSnapshot>(accountDir(userId).resolve("messages-${key(channelId)}.json"))
            ?.messages
            .orEmpty()
    }

    suspend fun storeMessages(userId: String, channelId: String, messages: List<Message>) =
        withCacheLock {
            write(
                accountDir(userId).resolve("messages-${key(channelId)}.json"),
                MessageSnapshot(cacheableMessages(messages)),
            )
        }

    /** Search readable rows retained for offline history, including plaintext DMs. */
    suspend fun searchMessages(
        userId: String,
        query: String,
        channelIds: Set<String>,
        limit: Int = 100,
    ): List<Message> = withCacheLock {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withCacheLock emptyList()
        channelIds.asSequence()
            .flatMap { channelId ->
                read<MessageSnapshot>(accountDir(userId).resolve("messages-${key(channelId)}.json"))
                    ?.messages
                    .orEmpty()
                    .asSequence()
            }
            .filter { it.content.lowercase().contains(needle) }
            .distinctBy(Message::id)
            .sortedByDescending(Message::createdAt)
            .take(limit)
            .toList()
    }

    /** Explicit sign-out must leave nothing another account can recover. */
    suspend fun clear(userId: String) = withCacheLock {
        navigationHot.remove(userId)
        accountDir(userId).deleteRecursively()
    }

    private suspend fun updateNavigation(userId: String, transform: (Navigation) -> Navigation) =
        withCacheLock {
            val current = navigationHot[userId]
                ?: read<Navigation>(accountDir(userId).resolve(NAVIGATION_FILE))
                ?: Navigation()
            val next = transform(current)
            navigationHot[userId] = next
            write(accountDir(userId).resolve(NAVIGATION_FILE), next)
        }

    private suspend fun <T> withCacheLock(block: () -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { block() } }

    private inline fun <reified T> read(file: File): T? {
        if (!file.isFile) return null
        return runCatching {
            encrypted(file).openFileInput().bufferedReader().use { input ->
                json.decodeFromString<T>(input.readText())
            }
        }.getOrElse {
            // An interrupted/obsolete snapshot is disposable. A network refresh
            // will recreate it, while repeatedly retrying corrupt ciphertext is
            // both slow and incapable of succeeding.
            file.delete()
            null
        }
    }

    private inline fun <reified T> write(file: File, value: T) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.pending")
        pending.delete()
        encrypted(pending).openFileOutput().bufferedWriter().use { output ->
            output.write(json.encodeToString(value))
        }
        if (!pending.renameTo(file)) {
            file.delete()
            check(pending.renameTo(file)) { "Could not replace offline cache snapshot" }
        }
    }

    private fun encrypted(file: File) = EncryptedFile.Builder(
        appContext,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private fun accountDir(userId: String) = root.resolve("account-${key(userId)}")

    private fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class MessageSnapshot(val messages: List<Message>)

    private companion object {
        const val NAVIGATION_FILE = "navigation.json"
    }
}

internal const val MAX_OFFLINE_MESSAGES_PER_CHANNEL = 250

/** Pending sends already have their own encrypted outbox and must not be duplicated. */
internal fun cacheableMessages(messages: List<Message>): List<Message> = messages
    .filterNot { it.id.startsWith("pending:") }
    .takeLast(MAX_OFFLINE_MESSAGES_PER_CHANNEL)
