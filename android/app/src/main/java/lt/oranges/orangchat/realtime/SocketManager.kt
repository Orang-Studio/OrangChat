package lt.oranges.orangchat.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.BuildConfig
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.model.AuthResult
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.DmCall
import lt.oranges.orangchat.data.model.DmCallEnded
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequest
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.UnreadActivity
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.VoiceCredentials
import lt.oranges.orangchat.data.model.VoiceState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the single Socket.IO connection. JWT is passed on the handshake via
 * `auth.token`, exactly as socket.rs reads it (TryData<AuthPayload{ token }>).
 * Incoming server->client events are decoded into [SocketEvent] and emitted on
 * [events]; client->server actions are exposed as emit/ack helpers.
 */
@Singleton
class SocketManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
    private val json: Json,
    @Named("baseUrl") private val baseUrl: String,
    @Named("refresh") private val refreshClient: Provider<OkHttpClient>,
) {
    private var socket: Socket? = null
    @Volatile private var activeChannelId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastAuthRefresh = 0L

    init {
        tokenStore.addTokenListener { token ->
            if (token == null) disconnect() else reauthenticate()
        }
        registerConnectivityNudges()
    }

    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<SocketEvent> = _events

    val isConnected: Boolean get() = socket?.connected() == true

    @Synchronized
    fun connect() {
        val token = tokenStore.accessToken ?: return
        socket?.let { existing ->
            if (!existing.connected()) existing.connect()
            return
        }

        val opts = IO.Options().apply {
            path = "/socket.io"
            transports = arrayOf("websocket")
            reconnection = true
            auth = mapOf("token" to token, "device" to "mobile")
        }
        val s = IO.socket(BuildConfig.SOCKET_URL, opts)
        registerListeners(s)
        socket = s
        s.connect()
    }

    @Synchronized
    fun disconnect() {
        socket?.let {
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
    }

    /** Call after a token refresh so the next connect uses the new JWT (auth is
     *  captured from options at handshake time, so we rebuild the socket). */
    @Synchronized
    fun reauthenticate() {
        disconnect()
        connect()
    }

    /** Nudge a reconnect after the network or the app comes back, when socket.io
     *  hasn't recovered on its own. Idempotent. */
    @Synchronized
    fun reconnectIfNeeded() {
        if (tokenStore.accessToken == null) return
        val s = socket
        if (s == null) connect() else if (!s.connected()) s.connect()
    }

    /** Mint a fresh access token off the refresh cooldown and hand it to the
     *  token store, whose listener rebuilds the socket with it. */
    private fun maybeRefreshAuth() {
        val now = System.currentTimeMillis()
        if (now - lastAuthRefresh < AUTH_REFRESH_COOLDOWN_MS) return
        lastAuthRefresh = now
        scope.launch {
            refreshAccessTokenBlocking()?.let { tokenStore.setAccessToken(it) }
        }
    }

    /** Blocking POST /auth/refresh, mirroring TokenAuthenticator. Null on any
     *  failure, including a network that is simply still down. */
    private fun refreshAccessTokenBlocking(): String? = try {
        val url = baseUrl.trimEnd('/') + "/auth/refresh"
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        refreshClient.get().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.string()?.let {
                json.decodeFromString(AuthResult.serializer(), it).tokens.accessToken
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun registerConnectivityNudges() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reconnectIfNeeded()
        })
        // Coming back to the foreground is the other moment a dead socket needs a
        // kick. Lifecycle observers must be added on the main thread.
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = reconnectIfNeeded()
            })
        }
    }

    private fun registerListeners(s: Socket) {
        s.on(Socket.EVENT_CONNECT) { _ ->
            activeChannelId?.let { s.emit("channel:join", it) }
            emit(SocketEvent.ConnectionState(true))
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            emit(SocketEvent.ConnectionState(false, args.firstOrNull()?.toString()))
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.toString().orEmpty()
            Log.w(TAG, "connect_error: $msg")
            emit(SocketEvent.ConnectionState(false, "connect_error"))
            // an expired access token rejects the handshake as "unauthorized".
            // mint a fresh one, which reauthenticates the socket via the token
            // listener; without this the socket stays down until relaunch.
            if (msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("auth", ignoreCase = true)
            ) {
                maybeRefreshAuth()
            }
        }

        s.on("message:new") { a -> obj(a)?.let { emit(SocketEvent.MessageNew(decode<Message>(it))) } }
        s.on("message:updated") { a -> obj(a)?.let { emit(SocketEvent.MessageUpdated(decode<Message>(it))) } }
        s.on("message:deleted") { a ->
            obj(a)?.let { emit(SocketEvent.MessageDeleted(it.optString("channelId"), it.optString("messageId"))) }
        }
        s.on("typing") { a ->
            obj(a)?.let { emit(SocketEvent.Typing(it.optString("channelId"), it.optString("userId"))) }
        }
        s.on("presence") { a ->
            obj(a)?.let {
                val devices = it.optJSONArray("devices")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                }.orEmpty()
                val activities = it.optJSONArray("activities")
                    ?.let { array -> json.decodeFromString<List<lt.oranges.orangchat.data.model.UserActivity>>(array.toString()) }
                    .orEmpty()
                emit(SocketEvent.Presence(it.optString("userId"), it.optString("status"), devices, activities))
            }
        }
        s.on("reaction") { a ->
            obj(a)?.let {
                emit(
                    SocketEvent.ReactionEvent(
                        it.optString("channelId"), it.optString("messageId"),
                        it.optString("emoji"), it.optString("userId"), it.optBoolean("added"),
                    ),
                )
            }
        }
        s.on("member:joined") { a ->
            obj(a)?.let { emit(SocketEvent.MemberJoined(it.optString("serverId"), decodeField<ServerMember>(it, "member"))) }
        }
        s.on("member:updated") { a ->
            obj(a)?.let { emit(SocketEvent.MemberUpdated(it.optString("serverId"), decodeField<ServerMember>(it, "member"))) }
        }
        s.on("member:left") { a ->
            obj(a)?.let { emit(SocketEvent.MemberLeft(it.optString("serverId"), it.optString("userId"))) }
        }
        s.on("role:created") { a -> obj(a)?.let { emit(SocketEvent.RoleCreated(decode<Role>(it))) } }
        s.on("role:updated") { a -> obj(a)?.let { emit(SocketEvent.RoleUpdated(decode<Role>(it))) } }
        s.on("role:deleted") { a ->
            obj(a)?.let { emit(SocketEvent.RoleDeleted(it.optString("serverId"), it.optString("roleId"))) }
        }
        s.on("channel:created") { a -> obj(a)?.let { emit(SocketEvent.ChannelCreated(decode<Channel>(it))) } }
        s.on("channel:updated") { a -> obj(a)?.let { emit(SocketEvent.ChannelUpdated(decode<Channel>(it))) } }
        s.on("channel:deleted") { a ->
            obj(a)?.let {
                val sid = if (it.isNull("serverId")) null else it.optString("serverId")
                emit(SocketEvent.ChannelDeleted(sid, it.optString("channelId")))
            }
        }
        s.on("server:updated") { a -> obj(a)?.let { emit(SocketEvent.ServerUpdated(decode<Server>(it))) } }
        s.on("server:deleted") { a -> obj(a)?.let { emit(SocketEvent.ServerDeleted(it.optString("serverId"))) } }
        s.on("user:updated") { a -> obj(a)?.let { emit(SocketEvent.UserUpdated(decode<User>(it))) } }
        s.on("friend:request") { a -> obj(a)?.let { emit(SocketEvent.FriendRequestReceived(decode<FriendRequest>(it))) } }
        s.on("friend:accepted") { a -> obj(a)?.let { emit(SocketEvent.FriendAccepted(decode<Friend>(it))) } }
        s.on("friend:request:removed") { a -> obj(a)?.let { emit(SocketEvent.FriendRequestRemoved(it.optString("id"))) } }
        s.on("friend:removed") { a -> obj(a)?.let { emit(SocketEvent.FriendRemoved(it.optString("userId"))) } }
        s.on("voice:state") { a -> obj(a)?.let { emit(SocketEvent.VoiceStateChanged(decode<VoiceState>(it))) } }
        s.on("soundboard:played") { a ->
            obj(a)?.let {
                emit(
                    SocketEvent.SoundboardPlayed(
                        channelId = it.optString("channelId"),
                        soundId = it.optString("soundId"),
                        userId = it.optString("userId"),
                        url = it.optString("url"),
                        volume = it.optDouble("volume", 1.0),
                    ),
                )
            }
        }
        s.on("dm:call:ringing") { a -> obj(a)?.let { emit(SocketEvent.DmCallRinging(decode<DmCall>(it))) } }
        s.on("dm:call:accepted") { a -> obj(a)?.let { emit(SocketEvent.DmCallAccepted(decode<DmCall>(it))) } }
        s.on("dm:call:ended") { a -> obj(a)?.let { emit(SocketEvent.DmCallFinished(decode<DmCallEnded>(it))) } }
        s.on("unread:activity") { a ->
            obj(a)?.let { emit(SocketEvent.UnreadActivityEvent(decode<UnreadActivity>(it))) }
        }
        s.on("read:state") { a ->
            obj(a)?.let { emit(SocketEvent.ChannelRead(it.optString("channelId"))) }
        }
    }

    // ── client -> server ────────────────────────────────
    fun joinChannel(channelId: String) {
        activeChannelId = channelId
        socket?.emit("channel:join", channelId)
    }
    fun leaveChannel(channelId: String) {
        if (activeChannelId == channelId) activeChannelId = null
        socket?.emit("channel:leave", channelId)
    }
    fun startTyping(channelId: String) { socket?.emit("typing:start", channelId) }
    fun updatePresence(status: String) { socket?.emit("presence:update", status) }

    fun addReaction(channelId: String, messageId: String, emoji: String) =
        socket?.emit("reaction:add", payload("channelId" to channelId, "messageId" to messageId, "emoji" to emoji))

    fun removeReaction(channelId: String, messageId: String, emoji: String) =
        socket?.emit("reaction:remove", payload("channelId" to channelId, "messageId" to messageId, "emoji" to emoji))

    /**
     * message:send with ack -> the created Message (or throws on error string).
     *
     * [attachmentIds] come from AttachmentUploader; the server resolves each to
     * the file it staged, so nothing about the file travels in this payload.
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        replyToId: String? = null,
        attachmentIds: List<String> = emptyList(),
        /**
         * Set for an end-to-end encrypted conversation. `content` then goes as
         * the empty string the server stores, and the real text only exists
         * inside this envelope (docs/E2EE.md §2).
         */
        ciphertext: String? = null,
        encEpoch: Int? = null,
        encVersion: Int? = null,
    ): Message =
        emitWithAck("message:send", buildJson {
            put("channelId", channelId)
            put("content", if (ciphertext == null) content else "")
            if (replyToId != null) put("replyToId", replyToId)
            if (attachmentIds.isNotEmpty()) put("attachmentIds", JSONArray(attachmentIds))
            if (ciphertext != null) {
                put("ciphertext", ciphertext)
                put("encEpoch", encEpoch)
                put("encVersion", encVersion)
            }
        }) { decode(it) }

    suspend fun editMessage(
        channelId: String,
        messageId: String,
        content: String,
        ciphertext: String? = null,
        encEpoch: Int? = null,
        encVersion: Int? = null,
    ): Message =
        emitWithAck("message:edit", buildJson {
            put("channelId", channelId)
            put("messageId", messageId)
            put("content", if (ciphertext == null) content else "")
            if (ciphertext != null) {
                put("ciphertext", ciphertext)
                put("encEpoch", encEpoch)
                put("encVersion", encVersion)
            }
        }) { decode(it) }

    suspend fun deleteMessage(channelId: String, messageId: String) {
        emitWithAckUnit("message:delete", buildJson {
            put("channelId", channelId); put("messageId", messageId)
        })
    }

    // ── voice + calls ───────────────────────────────────
    /** voice:join takes a bare channel id string and acks LiveKit credentials. */
    suspend fun joinVoice(channelId: String): VoiceCredentials =
        emitWithAck("voice:join", channelId) {
            VoiceCredentials(it.getString("token"), it.getString("url"))
        }

    fun leaveVoice(channelId: String) { socket?.emit("voice:leave", channelId) }

    /**
     * Fire a clip at everyone in the voice channel. The rate limit rejects via
     * the ack, so a refusal surfaces rather than silently doing nothing.
     */
    suspend fun playSound(channelId: String, soundId: String) {
        emitWithAckUnit("soundboard:play", buildJson {
            put("channelId", channelId); put("soundId", soundId)
        })
    }

    fun updateVoice(
        channelId: String,
        muted: Boolean? = null,
        deafened: Boolean? = null,
        video: Boolean? = null,
    ) {
        socket?.emit("voice:update", buildJson {
            put("channelId", channelId)
            muted?.let { put("muted", it) }
            deafened?.let { put("deafened", it) }
            video?.let { put("video", it) }
        })
    }

    /** Ring everyone else in a DM / group DM. */
    suspend fun startCall(channelId: String, video: Boolean): DmCall =
        emitWithAck("dm:call:start", buildJson {
            put("channelId", channelId); put("video", video)
        }) { decode(it) }

    /** Answer the call ringing at us; acks the call's updated roster. */
    suspend fun acceptCall(channelId: String): DmCall =
        emitWithAck("dm:call:respond", buildJson {
            put("channelId", channelId); put("accept", true)
        }) { decode(it) }

    /** Decline acks void, so it cannot share [acceptCall]'s decoding path. */
    suspend fun declineCall(channelId: String) {
        emitWithAckUnit("dm:call:respond", buildJson {
            put("channelId", channelId); put("accept", false)
        })
    }

    fun cancelCall(channelId: String) { socket?.emit("dm:call:cancel", channelId) }
    fun endCall(channelId: String) { socket?.emit("dm:call:end", channelId) }

    // ── helpers ─────────────────────────────────────────
    private fun emit(e: SocketEvent) { _events.tryEmit(e) }

    private fun obj(args: Array<out Any?>): JSONObject? = args.firstOrNull() as? JSONObject

    /**
     * A decode failure here used to surface as a bare kotlinx message - a model
     * class name and nothing about which field or which payload - and the actual
     * body was gone by the time anyone looked. Log the body that failed so the
     * mismatch is one logcat line away, then rethrow unchanged.
     */
    private inline fun <reified T> decode(o: JSONObject): T =
        try {
            json.decodeFromString(o.toString())
        } catch (e: Exception) {
            Log.e(TAG, "could not decode ${T::class.simpleName} from $o", e)
            throw e
        }

    private inline fun <reified T> decodeField(o: JSONObject, field: String): T =
        try {
            json.decodeFromString(o.getJSONObject(field).toString())
        } catch (e: Exception) {
            Log.e(TAG, "could not decode ${T::class.simpleName} from field '$field' of $o", e)
            throw e
        }

    private fun payload(vararg pairs: Pair<String, String>): JSONObject =
        JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    private inline fun buildJson(block: JSONObject.() -> Unit): JSONObject =
        JSONObject().apply(block)

    /**
     * Emit with a Socket.IO ack of shape { ok, data } / { ok:false, error }.
     * [payload] is Any because not every event takes an object - voice:join and
     * the call cancel/end events send a bare channel-id string.
     *
     * Failures resume with an exception rather than cancelling the continuation:
     * cancellation surfaces as CancellationException, which would tear down the
     * caller's coroutine instead of letting it report "already on a call".
     */
    private suspend inline fun <T> emitWithAck(
        event: String,
        payload: Any,
        crossinline map: (JSONObject) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val s = socket ?: run {
            cont.resumeWithException(IllegalStateException("Socket not connected"))
            return@suspendCancellableCoroutine
        }
        s.emit(event, arrayOf(payload), Ack { args ->
            if (!cont.isActive) return@Ack
            val res = args.firstOrNull() as? JSONObject
            if (res == null) {
                cont.resumeWithException(IllegalStateException("$event: no ack from server"))
                return@Ack
            }
            if (res.optBoolean("ok", false)) {
                val data = res.optJSONObject("data") ?: JSONObject()
                runCatching { map(data) }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            } else {
                cont.resumeWithException(RuntimeException(res.optString("error", "Request failed")))
            }
        })
    }

    private suspend fun emitWithAckUnit(event: String, payload: Any) =
        suspendCancellableCoroutine<Unit> { cont ->
            val s = socket ?: run {
                cont.resumeWithException(IllegalStateException("Socket not connected"))
                return@suspendCancellableCoroutine
            }
            s.emit(event, arrayOf(payload), Ack { args ->
                if (!cont.isActive) return@Ack
                val res = args.firstOrNull() as? JSONObject
                if (res == null || res.optBoolean("ok", false)) {
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(RuntimeException(res.optString("error", "Request failed")))
                }
            })
        }

    companion object {
        private const val TAG = "SocketManager"
        private const val AUTH_REFRESH_COOLDOWN_MS = 4_000L
    }
}
