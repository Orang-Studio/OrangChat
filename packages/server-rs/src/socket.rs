//! Socket.IO layer: handshake auth, connection lifecycle, and event handlers.
//! Mirrors sockets/index.ts + sockets/handlers.ts.

use serde::{Deserialize, Serialize};
use serde_json::json;
use socketioxide::extract::{AckSender, Data, SocketRef, TryData};
use socketioxide::handler::ConnectHandler;
use socketioxide::SocketIo;

use crate::auth::verify_access_token;
use crate::permissions::{self, CONNECT, MANAGE_MESSAGES, SPEAK};
use crate::services::{
    call, channel, membership, message, presence, rate_limit, read_state, server, sound, voice,
};
use crate::state::AppState;

#[derive(Deserialize)]
struct AuthPayload {
    token: Option<String>,
}

#[derive(Clone)]
struct SocketUser {
    user_id: String,
    username: String,
}

#[derive(Debug)]
struct AuthError;
impl std::fmt::Display for AuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "unauthorized")
    }
}
impl std::error::Error for AuthError {}

// ── Event payloads (client → server) ────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SendPayload {
    channel_id: String,
    content: String,
    #[serde(default)]
    reply_to_id: Option<String>,
    /// Ids handed out by /uploads/attachment; the server resolves them to the
    /// files it staged, so the payload never carries a url or size.
    #[serde(default)]
    attachment_ids: Vec<String>,
    /// Which of the above to hide behind a spoiler cover. Presentation only.
    #[serde(default)]
    spoiler_attachment_ids: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EditPayload {
    channel_id: String,
    message_id: String,
    content: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeletePayload {
    channel_id: String,
    message_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReactionPayload {
    channel_id: String,
    message_id: String,
    emoji: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VoiceUpdatePayload {
    channel_id: String,
    #[serde(default)]
    muted: Option<bool>,
    #[serde(default)]
    deafened: Option<bool>,
    #[serde(default)]
    video: Option<bool>,
    #[serde(default)]
    screen_sharing: Option<bool>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SoundboardPlayPayload {
    channel_id: String,
    sound_id: String,
}

/// What listeners need to play the clip without a fetch of their own.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SoundboardPlayedPayload {
    channel_id: String,
    sound_id: String,
    user_id: String,
    url: String,
    volume: f64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CallStartPayload {
    channel_id: String,
    #[serde(default)]
    video: Option<bool>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CallRespondPayload {
    channel_id: String,
    accept: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PresencePayload<'a> {
    user_id: &'a str,
    status: &'a str,
}

fn ack_ok<T: Serialize>(ack: AckSender, data: &T) {
    let _ = ack.send(&json!({ "ok": true, "data": data }));
}
fn ack_void(ack: AckSender) {
    let _ = ack.send(&json!({ "ok": true }));
}
fn ack_err(ack: AckSender, msg: String) {
    let _ = ack.send(&json!({ "ok": false, "error": msg }));
}

/// Fan a call event out to every member of a conversation via their `user:<id>`
/// rooms. Call events must reach people who do not have the DM open, so the
/// `channel:<id>` room (which clients join only while viewing) is not enough.
/// Fan a `voice:state` out to everyone whose UI draws that roster.
///
/// `channel:{id}` is joined by opening a channel's *chat*, which is exactly what
/// nobody does to a voice channel — selecting one joins the call instead. So the
/// roster in the channel list was updating for whoever happened to be reading
/// that channel and for nobody else: joins survived only because the list is
/// also seeded over REST, while leaves went unseen until the next reseed. The
/// server room is the set that actually renders this. A DM call has no server,
/// and its conversation room is already the right audience.
async fn emit_voice_state(
    io: &SocketIo,
    state: &AppState,
    channel_id: &str,
    payload: &voice::VoiceStatePayload,
) {
    let server_id = channel::get_channel(state, channel_id)
        .await
        .ok()
        .flatten()
        .and_then(|c| c.server_id);
    // One emit over both rooms, not two: a member reading the channel is in
    // both, and socket.io delivers to each socket once per broadcast.
    let rooms = match server_id {
        Some(sid) => vec![format!("server:{sid}"), format!("channel:{channel_id}")],
        None => vec![format!("channel:{channel_id}")],
    };
    let _ = io.to(rooms).emit("voice:state", payload);
}

/// Tell every one of this user's own sockets which of them are in voice, so each
/// can work out whether the *other* ones are.
async fn emit_voice_devices(io: &SocketIo, state: &AppState, user_id: &str) {
    let devices = voice::list_devices(state, user_id).await.unwrap_or_default();
    let _ = io
        .to(format!("user:{user_id}"))
        .emit("voice:devices", &devices);
}

async fn emit_to_conversation<T: Serialize + ?Sized>(
    io: &SocketIo,
    state: &AppState,
    channel_id: &str,
    event: &'static str,
    payload: &T,
) {
    let Ok(members) = crate::services::dm::get_conversation_participants(state, channel_id).await
    else {
        return;
    };
    for member in members {
        let _ = io.to(format!("user:{member}")).emit(event, payload);
    }
}

/// Drop a user from a call and tell the conversation why.
async fn end_call_for(
    io: &SocketIo,
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    reason: &'static str,
) {
    let Ok(Some(call_over)) = call::leave(state, channel_id, user_id).await else {
        return;
    };
    let payload = call::CallEndedPayload {
        channel_id: channel_id.to_string(),
        user_id: user_id.to_string(),
        reason,
        call_over,
    };
    emit_to_conversation(io, state, channel_id, "dm:call:ended", &payload).await;
}

/// After RING_TIMEOUT, stop ringing anyone who never picked up.
fn arm_ring_timeout(state: AppState, io: SocketIo, channel_id: String, started_at: String) {
    tokio::spawn(async move {
        tokio::time::sleep(call::RING_TIMEOUT).await;
        let Ok((timed_out, over)) = call::expire_ringing(&state, &channel_id, &started_at).await
        else {
            return;
        };
        for uid in timed_out {
            let payload = call::CallEndedPayload {
                channel_id: channel_id.clone(),
                user_id: uid,
                reason: "timeout",
                call_over: over,
            };
            emit_to_conversation(&io, &state, &channel_id, "dm:call:ended", &payload).await;
        }
    });
}

/// Emit a user's presence to every server they belong to.
async fn broadcast_presence(io: &SocketIo, state: &AppState, user_id: &str, status: &str) {
    if let Ok(servers) = server::get_user_servers(state, user_id).await {
        for s in servers {
            let _ = io
                .to(format!("server:{}", s.id))
                .emit("presence", &PresencePayload { user_id, status });
        }
    }
}

pub fn setup(io: SocketIo, state: AppState) {
    let connect_state = state.clone();
    let connect_io = io.clone();
    let mw_state = state.clone();

    let on_connect = move |s: SocketRef| {
        let state = connect_state.clone();
        let io = connect_io.clone();
        async move {
            handle_connect(s, io, state).await;
        }
    };
    let middleware = move |s: SocketRef, TryData(auth): TryData<AuthPayload>| {
        let state = mw_state.clone();
        async move {
            let token = auth.ok().and_then(|a| a.token);
            let Some(token) = token else {
                return Err(AuthError);
            };
            match verify_access_token(&state.config, &token) {
                Ok(claims) => {
                    s.extensions.insert(SocketUser {
                        user_id: claims.sub,
                        username: claims.username,
                    });
                    Ok(())
                }
                Err(_) => Err(AuthError),
            }
        }
    };

    io.ns("/", on_connect.with(middleware));
}

async fn handle_connect(s: SocketRef, io: SocketIo, state: AppState) {
    let Some(user) = s.extensions.get::<SocketUser>() else {
        let _ = s.disconnect();
        return;
    };
    let user_id = user.user_id.clone();
    let username = user.username.clone();

    register_handlers(
        &s,
        io.clone(),
        state.clone(),
        user_id.clone(),
        username.clone(),
    );

    // Disconnect: clear voice state for any voice rooms, then presence.
    {
        let io = io.clone();
        let state = state.clone();
        let uid = user_id.clone();
        s.on_disconnect(move |s: SocketRef| {
            let io = io.clone();
            let state = state.clone();
            let uid = uid.clone();
            async move {
                let sid = s.id.to_string();
                let _ = voice::remove_device(&state, &uid, &sid).await;
                for room in s.rooms().expect("room lookup is infallible") {
                    let room = room.to_string();
                    if let Some(cid) = room.strip_prefix("voice:") {
                        // As in voice:leave — one device dropping must not evict
                        // the user from a channel their other device is still in.
                        if voice::has_other_device_in(&state, &uid, cid, &sid)
                            .await
                            .unwrap_or(false)
                        {
                            continue;
                        }
                        let _ = voice::clear_voice_state(&state, cid, &uid).await;
                        emit_voice_state(&io, &state, cid, &voice::left_payload(cid, &uid)).await;
                    }
                }
                emit_voice_devices(&io, &state, &uid).await;
                // Only the user's *last* socket going away ends their call —
                // closing one of several tabs must not hang up on the others.
                if let Ok(true) = presence::remove_socket(&state, &uid).await {
                    if let Ok(Some(channel_id)) = call::active_call_of(&state, &uid).await {
                        end_call_for(&io, &state, &channel_id, &uid, "ended").await;
                    }
                    broadcast_presence(&io, &state, &uid, "offline").await;
                }
            }
        });
    }

    // Join user + server + conversation rooms, then mark online.
    let _ = s.join(format!("user:{user_id}"));
    if let Ok(servers) = server::get_user_servers(&state, &user_id).await {
        for srv in servers {
            let _ = s.join(format!("server:{}", srv.id));
        }
    }
    if let Ok(convos) = crate::services::dm::list_conversations(&state, &user_id).await {
        for (c, _) in convos {
            let _ = s.join(format!("channel:{}", c.id));
        }
    }
    if let Ok(true) = presence::add_socket(&state, &user_id).await {
        let _ = presence::set_status(&state, &user_id, "online").await;
        broadcast_presence(&io, &state, &user_id, "online").await;
    }
    // A device that opens while another is already in a call has no voice event
    // coming to tell it so; without this the glow would wait for one.
    emit_voice_devices(&io, &state, &user_id).await;

    replay_ringing_call(&s, &state, &user_id).await;
}

/// Re-deliver a call that is ringing at this user right now.
///
/// `dm:call:ringing` is a live event, so a client that was not connected when it
/// fired never learns about the call: a phone woken by the call push cold-starts
/// with no ringing state at all, which is why tapping the notification landed in
/// a silent app with no call UI. The ring is still in Redis - the caller is still
/// waiting - so the state is there to be replayed, and replaying it is what makes
/// the callee's ringtone and popup appear. A reconnect mid-ring recovers the same
/// way.
async fn replay_ringing_call(s: &SocketRef, state: &AppState, user_id: &str) {
    let Ok(Some(channel_id)) = call::active_call_of(state, user_id).await else {
        return;
    };
    let Ok(Some(current)) = call::load(state, &channel_id).await else {
        return;
    };
    // Only if they are still being rung: already answering or already on the
    // call means their client has the state, and re-ringing would be a bug.
    if !current.ringing.iter().any(|uid| uid == user_id) {
        return;
    }
    if let Ok(payload) = call::payload(state, &current).await {
        let _ = s.emit("dm:call:ringing", &payload);
    }
}

fn register_handlers(
    s: &SocketRef,
    io: SocketIo,
    state: AppState,
    user_id: String,
    username: String,
) {
    // channel:join
    {
        let state = state.clone();
        let uid = user_id.clone();
        s.on(
            "channel:join",
            move |s: SocketRef, Data(channel_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let uid = uid.clone();
                async move {
                    match channel::require_channel_access(&state, &channel_id, &uid).await {
                        Ok(_) => {
                            let _ = s.join(format!("channel:{channel_id}"));
                            ack_void(ack);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // channel:leave
    s.on(
        "channel:leave",
        move |s: SocketRef, Data(channel_id): Data<String>, ack: AckSender| async move {
            let _ = s.leave(format!("channel:{channel_id}"));
            ack_void(ack);
        },
    );

    // message:send
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "message:send",
            move |Data(p): Data<SendPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let res = async {
                        rate_limit::check(
                            &state,
                            "msg:send",
                            &uid,
                            rate_limit::MESSAGE_SEND_PER_USER,
                        )
                        .await?;
                        let ch =
                            channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        channel::enforce_slowmode(&state, &ch, &uid).await?;
                        membership::assert_not_timed_out(&state, &ch, &uid).await?;
                        message::send_message(
                            &state,
                            &p.channel_id,
                            &uid,
                            &p.content,
                            p.reply_to_id.as_deref(),
                            &p.attachment_ids,
                            &p.spoiler_attachment_ids,
                        )
                        .await
                    }
                    .await;
                    match res {
                        Ok(msg) => {
                            let _ = io
                                .to(format!("channel:{}", p.channel_id))
                                .emit("message:new", &msg);

                            // Unread + @mention bookkeeping: bump mention counters
                            // and notify members via rooms they never leave
                            // (server:<id> / user:<id>), so background channels
                            // still light up.
                            if let Ok(Some(ch)) = channel::get_channel(&state, &p.channel_id).await
                            {
                                let parsed = read_state::parse_mentions(&p.content);
                                let recipients = read_state::resolve_mention_recipients(
                                    &state, &ch, &uid, &parsed,
                                )
                                .await
                                .unwrap_or_default();
                                if !recipients.is_empty() {
                                    let _ = read_state::add_mentions(
                                        &state,
                                        &p.channel_id,
                                        &recipients,
                                    )
                                    .await;
                                }
                                let payload = json!({
                                    "channelId": ch.id,
                                    "serverId": ch.server_id,
                                    "authorId": uid,
                                    "mentions": recipients,
                                    "preview": msg.content,
                                    "author": msg.author,
                                });
                                if let Some(server_id) = &ch.server_id {
                                    let _ = io
                                        .to(format!("server:{server_id}"))
                                        .emit("unread:activity", &payload);
                                } else if let Ok(members) =
                                    read_state::channel_member_ids(&state, &ch).await
                                {
                                    for m in &members {
                                        let _ = io
                                            .to(format!("user:{m}"))
                                            .emit("unread:activity", &payload);
                                    }
                                }

                                let push_targets = if ch.server_id.is_some() {
                                    recipients.clone()
                                } else {
                                    read_state::channel_member_ids(&state, &ch)
                                        .await
                                        .unwrap_or_default()
                                };
                                let push_state = state.clone();
                                let message_id = msg.id.clone();
                                let channel_id = ch.id.clone();
                                let server_id = ch.server_id.clone();
                                let author_id = uid.clone();
                                let author_name = msg.author.display_name.clone();
                                let author_avatar = msg.author.avatar_url.clone();
                                let preview = msg.content.clone();
                                tokio::spawn(async move {
                                    crate::services::push::notify_message(
                                        &push_state,
                                        &message_id,
                                        &channel_id,
                                        server_id.as_deref(),
                                        &author_id,
                                        &author_name,
                                        author_avatar.as_deref(),
                                        &preview,
                                        &push_targets,
                                    )
                                    .await;
                                });
                            }

                            ack_ok(ack, &msg);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // message:edit
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "message:edit",
            move |Data(p): Data<EditPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let res = async {
                        rate_limit::check(
                            &state,
                            "msg:edit",
                            &uid,
                            rate_limit::MESSAGE_EDIT_PER_USER,
                        )
                        .await?;
                        channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        message::edit_message(&state, &p.message_id, &uid, &p.content).await
                    }
                    .await;
                    match res {
                        Ok(msg) => {
                            let _ = io
                                .to(format!("channel:{}", p.channel_id))
                                .emit("message:updated", &msg);
                            ack_ok(ack, &msg);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // message:delete
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "message:delete",
            move |Data(p): Data<DeletePayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let res = async {
                        let ch =
                            channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        let mut can_manage = false;
                        if let Some(server_id) = ch.server_id {
                            if let Some(perms) =
                                membership::effective_permissions(&state, &server_id, &uid).await?
                            {
                                can_manage = permissions::has_permission(perms, MANAGE_MESSAGES);
                            }
                        }
                        message::delete_message(&state, &p.message_id, &uid, can_manage).await
                    }
                    .await;
                    match res {
                        Ok(_) => {
                            let _ = io.to(format!("channel:{}", p.channel_id)).emit(
                                "message:deleted",
                                &json!({ "channelId": p.channel_id, "messageId": p.message_id }),
                            );
                            ack_void(ack);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // typing:start (best-effort, no ack, excludes sender)
    {
        let state = state.clone();
        let uid = user_id.clone();
        s.on(
            "typing:start",
            move |s: SocketRef, Data(channel_id): Data<String>| {
                let state = state.clone();
                let uid = uid.clone();
                async move {
                    if rate_limit::check(&state, "typing", &uid, rate_limit::TYPING_PER_USER)
                        .await
                        .is_err()
                    {
                        return;
                    }
                    if channel::require_channel_access(&state, &channel_id, &uid)
                        .await
                        .is_ok()
                    {
                        let _ = s
                            .to(format!("channel:{channel_id}"))
                            .emit("typing", &json!({ "channelId": channel_id, "userId": uid }));
                    }
                }
            },
        );
    }

    // reaction:add
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("reaction:add", move |Data(p): Data<ReactionPayload>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                if rate_limit::check(&state, "reaction", &uid, rate_limit::REACTION_PER_USER)
                    .await
                    .is_err()
                {
                    return;
                }
                let Ok(ch) = channel::require_channel_access(&state, &p.channel_id, &uid).await
                else {
                    return;
                };
                // A timeout that still let you react would not be a timeout.
                if membership::assert_not_timed_out(&state, &ch, &uid).await.is_err() {
                    return;
                }
                if let Ok((_, true)) =
                    message::add_reaction(&state, &p.message_id, &uid, &p.emoji).await
                {
                    let _ = io.to(format!("channel:{}", p.channel_id)).emit(
                        "reaction",
                        &json!({
                            "channelId": p.channel_id, "messageId": p.message_id,
                            "emoji": p.emoji, "userId": uid, "added": true
                        }),
                    );
                }
            }
        });
    }

    // reaction:remove
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("reaction:remove", move |Data(p): Data<ReactionPayload>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                if rate_limit::check(&state, "reaction", &uid, rate_limit::REACTION_PER_USER)
                    .await
                    .is_err()
                {
                    return;
                }
                if channel::require_channel_access(&state, &p.channel_id, &uid)
                    .await
                    .is_err()
                {
                    return;
                }
                if let Ok((_, true)) =
                    message::remove_reaction(&state, &p.message_id, &uid, &p.emoji).await
                {
                    let _ = io.to(format!("channel:{}", p.channel_id)).emit(
                        "reaction",
                        &json!({
                            "channelId": p.channel_id, "messageId": p.message_id,
                            "emoji": p.emoji, "userId": uid, "added": false
                        }),
                    );
                }
            }
        });
    }

    // presence:update
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("presence:update", move |Data(status): Data<String>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                let _ = presence::set_status(&state, &uid, &status).await;
                broadcast_presence(&io, &state, &uid, &status).await;
            }
        });
    }

    // voice:join
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        let uname = username.clone();
        s.on(
            "voice:join",
            move |s: SocketRef, Data(channel_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                let uname = uname.clone();
                async move {
                    let res = async {
                        let ch = channel::require_channel_access(&state, &channel_id, &uid).await?;
                        membership::assert_not_timed_out(&state, &ch, &uid).await?;
                        // A DM call has no roles behind it, so nothing to restrict.
                        let mut sources = None;
                        if let Some(ref server_id) = ch.server_id {
                            let perms =
                                membership::effective_permissions(&state, server_id, &uid).await?;
                            let ok = perms
                                .map(|p| permissions::has_permission(p, CONNECT))
                                .unwrap_or(false);
                            if !ok {
                                return Err(crate::error::AppError::Permission(
                                    "Missing CONNECT permission".into(),
                                ));
                            }
                            voice::assert_capacity(&state, &ch, &uid, perms.unwrap_or(0)).await?;
                            sources = Some(voice::publish_sources(perms.unwrap_or(0)));
                        }
                        let creds = voice::mint_voice_token(
                            &state.config,
                            &channel_id,
                            &uid,
                            &uname,
                            sources,
                        )?;
                        let voice_state = voice::set_voice_state(
                            &state,
                            &channel_id,
                            &uid,
                            voice::VoicePatch::default(),
                        )
                        .await?;
                        Ok::<_, crate::error::AppError>((creds, voice_state))
                    }
                    .await;
                    match res {
                        Ok(((token, url), voice_state)) => {
                            let _ = s.join(format!("voice:{channel_id}"));
                            let _ =
                                voice::add_device(&state, &uid, &s.id.to_string(), &channel_id)
                                    .await;
                            emit_voice_state(&io, &state, &channel_id, &voice_state).await;
                            emit_voice_devices(&io, &state, &uid).await;
                            ack_ok(ack, &json!({ "token": token, "url": url }));
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // voice:leave
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "voice:leave",
            move |s: SocketRef, Data(channel_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let sid = s.id.to_string();
                    let _ = voice::remove_device(&state, &uid, &sid).await;
                    let _ = s.leave(format!("voice:{channel_id}"));
                    // Another of this user's devices may still be sitting in the
                    // channel; wiping their state would make them vanish from the
                    // roster while still audible.
                    let elsewhere = voice::has_other_device_in(&state, &uid, &channel_id, &sid)
                        .await
                        .unwrap_or(false);
                    if !elsewhere {
                        let _ = voice::clear_voice_state(&state, &channel_id, &uid).await;
                        emit_voice_state(
                            &io,
                            &state,
                            &channel_id,
                            &voice::left_payload(&channel_id, &uid),
                        )
                        .await;
                    }
                    emit_voice_devices(&io, &state, &uid).await;
                    ack_void(ack);
                }
            },
        );
    }

    // voice:device:disconnect — hang up one of my *other* devices.
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "voice:device:disconnect",
            move |s: SocketRef, Data(session_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    // Only ever your own devices: the session id is a socket id,
                    // and without this check knowing one would hang up a stranger.
                    let mine = voice::list_devices(&state, &uid)
                        .await
                        .unwrap_or_default()
                        .into_iter()
                        .any(|d| d.session_id == session_id);
                    if !mine {
                        ack_err(ack, "That device is not connected".into());
                        return;
                    }
                    // Disconnecting the device you are asking from is just
                    // leaving, and the client has voice:leave for that.
                    if session_id == s.id.to_string() {
                        ack_err(ack, "That is this device".into());
                        return;
                    }

                    match session_id.parse() {
                        Ok(sid) => match io.get_socket(sid) {
                            // The target tears down its own media and emits
                            // voice:leave, which is what actually clears state —
                            // so a device that has already gone away needs no
                            // cleanup here beyond the stale entry below.
                            Some(target) => {
                                let _ = target.emit("voice:force:leave", &());
                                ack_void(ack);
                            }
                            None => {
                                let _ = voice::remove_device(&state, &uid, &session_id).await;
                                emit_voice_devices(&io, &state, &uid).await;
                                ack_void(ack);
                            }
                        },
                        Err(_) => ack_err(ack, "That device is not connected".into()),
                    }
                }
            },
        );
    }

    // soundboard:play
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "soundboard:play",
            move |Data(p): Data<SoundboardPlayPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let res = async {
                        rate_limit::check(
                            &state,
                            "soundboard",
                            &uid,
                            rate_limit::SOUNDBOARD_PER_USER,
                        )
                        .await?;

                        let ch =
                            channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        let server_id = ch.server_id.ok_or_else(|| {
                            crate::error::AppError::BadRequest(
                                "Soundboard is only available in server voice channels".into(),
                            )
                        })?;

                        // Being able to be heard is the same permission that
                        // governs talking; a muted-by-role member must not get a
                        // second mouth via the soundboard.
                        let perms =
                            membership::effective_permissions(&state, &server_id, &uid).await?;
                        let ok = perms
                            .map(|p| permissions::has_permission(p, SPEAK))
                            .unwrap_or(false);
                        if !ok {
                            return Err(crate::error::AppError::Permission(
                                "Missing SPEAK permission".into(),
                            ));
                        }

                        // Actually being in the channel is what makes this a
                        // sound in a room rather than a sound fired at one.
                        let here = voice::list_voice_participants(&state, &p.channel_id)
                            .await?
                            .into_iter()
                            .any(|s| s.user_id == uid);
                        if !here {
                            return Err(crate::error::AppError::Permission(
                                "You are not in that voice channel".into(),
                            ));
                        }

                        let snd = sound::get_sound(&state, &p.sound_id).await?;
                        // Otherwise any server's sounds could be played into any
                        // other server's channels.
                        if snd.server_id != server_id {
                            return Err(crate::error::AppError::NotFound(
                                "Sound not found".into(),
                            ));
                        }
                        Ok::<_, crate::error::AppError>(snd)
                    }
                    .await;

                    match res {
                        Ok(snd) => {
                            let payload = SoundboardPlayedPayload {
                                channel_id: p.channel_id.clone(),
                                sound_id: snd.id,
                                user_id: uid.clone(),
                                url: snd.url,
                                volume: snd.volume,
                            };
                            // Everyone in the voice room, the player included —
                            // hearing your own sound is the confirmation it fired.
                            let _ = io
                                .to(format!("voice:{}", p.channel_id))
                                .emit("soundboard:played", &payload);
                            ack_void(ack);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // voice:update
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "voice:update",
            move |Data(p): Data<VoiceUpdatePayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let patch = voice::VoicePatch {
                        muted: p.muted,
                        deafened: p.deafened,
                        video: p.video,
                        screen_sharing: p.screen_sharing,
                    };
                    match voice::set_voice_state(&state, &p.channel_id, &uid, patch).await {
                        Ok(voice_state) => {
                            emit_voice_state(&io, &state, &p.channel_id, &voice_state).await;
                            ack_void(ack);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // ── DM / group-DM calls ─────────────────────────────
    // Signalling only: after `dm:call:start` / an accepted `dm:call:respond`,
    // the client still emits `voice:join` to get its LiveKit credentials.

    // dm:call:start
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "dm:call:start",
            move |Data(p): Data<CallStartPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    if let Err(e) = rate_limit::check(
                        &state,
                        "call:start",
                        &uid,
                        rate_limit::CALL_START_PER_USER,
                    )
                    .await
                    {
                        return ack_err(ack, e.message());
                    }
                    let outcome =
                        match call::start(&state, &p.channel_id, &uid, p.video.unwrap_or(false))
                            .await
                        {
                            Ok(o) => o,
                            Err(e) => return ack_err(ack, e.message()),
                        };
                    let payload = match call::payload(&state, &outcome.call).await {
                        Ok(p) => p,
                        Err(e) => return ack_err(ack, e.message()),
                    };

                    for target in &outcome.call.ringing {
                        let _ = io
                            .to(format!("user:{target}"))
                            .emit("dm:call:ringing", &payload);
                    }
                    let push_state = state.clone();
                    let push_channel = payload.channel_id.clone();
                    let push_caller = payload.caller.display_name.clone();
                    let push_avatar = payload.caller.avatar_url.clone();
                    let push_targets = outcome.call.ringing.clone();
                    let push_video = payload.video;
                    tokio::spawn(async move {
                        crate::services::push::notify_call(
                            &push_state,
                            &push_channel,
                            &push_caller,
                            push_avatar.as_deref(),
                            push_video,
                            &push_targets,
                        )
                        .await;
                    });
                    // Let the caller see who we skipped rather than silently
                    // dropping them from the call.
                    for busy_id in &outcome.busy {
                        let _ = io.to(format!("user:{uid}")).emit(
                            "dm:call:ended",
                            &call::CallEndedPayload {
                                channel_id: p.channel_id.clone(),
                                user_id: busy_id.clone(),
                                reason: "busy",
                                call_over: false,
                            },
                        );
                    }
                    if outcome.created {
                        arm_ring_timeout(
                            state.clone(),
                            io.clone(),
                            p.channel_id.clone(),
                            outcome.call.started_at.clone(),
                        );
                    } else {
                        // Joining a call in progress: the roster changed.
                        emit_to_conversation(
                            &io,
                            &state,
                            &p.channel_id,
                            "dm:call:accepted",
                            &payload,
                        )
                        .await;
                    }
                    ack_ok(ack, &payload);
                }
            },
        );
    }

    // dm:call:respond
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "dm:call:respond",
            move |Data(p): Data<CallRespondPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    if !p.accept {
                        end_call_for(&io, &state, &p.channel_id, &uid, "declined").await;
                        return ack_void(ack);
                    }
                    match call::accept(&state, &p.channel_id, &uid).await {
                        Ok(call_state) => match call::payload(&state, &call_state).await {
                            Ok(payload) => {
                                emit_to_conversation(
                                    &io,
                                    &state,
                                    &p.channel_id,
                                    "dm:call:accepted",
                                    &payload,
                                )
                                .await;
                                ack_ok(ack, &payload);
                            }
                            Err(e) => ack_err(ack, e.message()),
                        },
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

    // dm:call:cancel — the caller gives up before anyone answers.
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "dm:call:cancel",
            move |Data(channel_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    end_call_for(&io, &state, &channel_id, &uid, "cancelled").await;
                    ack_void(ack);
                }
            },
        );
    }

    // dm:call:end — hang up a call in progress.
    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "dm:call:end",
            move |Data(channel_id): Data<String>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    end_call_for(&io, &state, &channel_id, &uid, "ended").await;
                    ack_void(ack);
                }
            },
        );
    }
}
