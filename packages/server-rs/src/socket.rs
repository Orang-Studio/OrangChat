
use serde::{Deserialize, Serialize};
use serde_json::json;
use socketioxide::extract::{AckSender, Data, SocketRef, TryData};
use socketioxide::handler::ConnectHandler;
use socketioxide::SocketIo;

use std::collections::HashSet;

use crate::auth::verify_access_token;
use crate::permissions::{self, CONNECT, MANAGE_MESSAGES, SPEAK};
use crate::services::{
    bot, call, channel, friends, game, membership, message, presence, rate_limit, read_state,
    server, sound, voice,
};
use crate::state::AppState;

#[derive(Deserialize)]
struct AuthPayload {
    token: Option<String>,
    device: Option<String>,
}

#[derive(Clone)]
struct SocketUser {
    user_id: String,
    username: String,
    device: String,
}

#[derive(Debug)]
struct AuthError;
impl std::fmt::Display for AuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "unauthorized")
    }
}
impl std::error::Error for AuthError {}


#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct GameActivityPayload {
    #[serde(default)]
    game_id: Option<String>,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SendPayload {
    channel_id: String,
    content: String,
    #[serde(default)]
    reply_to_id: Option<String>,
    #[serde(default)]
    attachment_ids: Vec<String>,
    #[serde(default)]
    spoiler_attachment_ids: Vec<String>,
    #[serde(default)]
    ciphertext: Option<String>,
    #[serde(default)]
    enc_epoch: Option<i32>,
    #[serde(default)]
    enc_version: Option<i32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EditPayload {
    channel_id: String,
    message_id: String,
    content: String,
    #[serde(default)]
    ciphertext: Option<String>,
    #[serde(default)]
    enc_epoch: Option<i32>,
    #[serde(default)]
    enc_version: Option<i32>,
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

const REACTION_MAX_CHARS: usize = 64;

fn reaction_ok(emoji: &str) -> bool {
    !emoji.is_empty() && emoji.chars().count() <= REACTION_MAX_CHARS
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
    devices: Vec<String>,
    activities: Vec<crate::dto::ActivityDto>,
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
    let rooms = match server_id {
        Some(sid) => vec![format!("server:{sid}"), format!("channel:{channel_id}")],
        None => vec![format!("channel:{channel_id}")],
    };
    let _ = io.to(rooms).emit("voice:state", payload);
}

async fn emit_voice_devices(io: &SocketIo, state: &AppState, user_id: &str) {
    let devices = voice::list_devices(state, user_id)
        .await
        .unwrap_or_default();
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

pub(crate) async fn broadcast_presence(
    io: &SocketIo,
    state: &AppState,
    user_id: &str,
    status: &str,
) {
    let devices = presence::get_devices(state, user_id)
        .await
        .unwrap_or_default();
    let activities = presence::get_activities(state, user_id)
        .await
        .unwrap_or_default();
    let mut rooms = vec![format!("user:{user_id}")];
    if let Ok(servers) = server::get_user_servers(state, user_id).await {
        rooms.extend(
            servers
                .into_iter()
                .map(|server| format!("server:{}", server.id)),
        );
    }
    if let Ok(friend_rows) = friends::list_friends(state, user_id).await {
        rooms.extend(
            friend_rows
                .into_iter()
                .map(|friend| format!("user:{}", friend.user.id)),
        );
    }
    let _ = io.to(rooms).emit(
        "presence",
        &PresencePayload {
            user_id,
            status,
            devices,
            activities,
        },
    );
}

pub async fn deliver_message(
    io: &SocketIo,
    state: &AppState,
    channel: &crate::models::ChannelRow,
    msg: &crate::dto::MessageDto,
    author_id: &str,
    content: &str,
    from: Option<&SocketRef>,
) {
    let channel_id = channel.id.clone();

    let _ = read_state::mark_read(state, author_id, &channel_id).await;
    let _ = io
        .to(format!("user:{author_id}"))
        .emit("read:state", &json!({ "channelId": channel_id }));
    spawn_read_dismiss(state, author_id, &channel_id);

    let room = format!("channel:{channel_id}");
    match from {
        Some(sender) => {
            let _ = sender.broadcast().within(room).emit("message:new", msg);
        }
        None => {
            let _ = io.to(room).emit("message:new", msg);
        }
    }

    let parsed = read_state::parse_mentions(content);
    let recipients = read_state::resolve_mention_recipients(state, channel, author_id, &parsed)
        .await
        .unwrap_or_default();
    if !recipients.is_empty() {
        let _ = read_state::add_mentions(state, &channel_id, &recipients).await;
    }
    let payload = json!({
        "channelId": channel.id,
        "serverId": channel.server_id,
        "authorId": author_id,
        "mentions": recipients,
        "preview": msg.content,
        "author": msg.author,
    });
    if let Some(server_id) = &channel.server_id {
        let _ = io
            .to(format!("server:{server_id}"))
            .emit("unread:activity", &payload);
    } else if let Ok(members) = read_state::channel_member_ids(state, channel).await {
        for m in &members {
            let _ = io.to(format!("user:{m}")).emit("unread:activity", &payload);
        }
    }

    let push_targets = if channel.server_id.is_some() {
        recipients.clone()
    } else {
        read_state::channel_member_ids(state, channel)
            .await
            .unwrap_or_default()
    };

    let viewing = active_channel_viewers(io, &channel_id);
    for viewer in &viewing {
        if viewer == author_id {
            continue;
        }
        let _ = read_state::mark_read(state, viewer, &channel_id).await;
        let _ = io
            .to(format!("user:{viewer}"))
            .emit("read:state", &json!({ "channelId": channel_id }));
        spawn_read_dismiss(state, viewer, &channel_id);
    }
    let push_targets: Vec<String> = push_targets
        .into_iter()
        .filter(|t| !viewing.contains(t))
        .collect();

    let push_state = state.clone();
    let message_id = msg.id.clone();
    let push_channel = channel.id.clone();
    let server_id = channel.server_id.clone();
    let author = author_id.to_string();
    let author_name = msg.author.display_name.clone();
    let author_avatar = msg.author.avatar_url.clone();
    let preview = msg.content.clone();
    let sealed = msg.ciphertext.clone();
    let enc_epoch = msg.enc_epoch;
    tokio::spawn(async move {
        crate::services::push::notify_message(
            &push_state,
            &message_id,
            &push_channel,
            server_id.as_deref(),
            &author,
            &author_name,
            author_avatar.as_deref(),
            &preview,
            &push_targets,
            sealed.as_deref(),
            enc_epoch,
        )
        .await;
    });
}

fn spawn_read_dismiss(state: &AppState, user_id: &str, channel_id: &str) {
    let state = state.clone();
    let user_id = user_id.to_string();
    let channel_id = channel_id.to_string();
    tokio::spawn(async move {
        crate::services::push::notify_read(&state, &user_id, &channel_id).await;
    });
}

fn active_channel_viewers(io: &SocketIo, channel_id: &str) -> HashSet<String> {
    io.within(format!("channel:{channel_id}"))
        .sockets()
        .map(|sockets| {
            sockets
                .iter()
                .filter_map(|s| s.extensions.get::<SocketUser>().map(|u| u.user_id.clone()))
                .collect()
        })
        .unwrap_or_default()
}

fn normalize_device(device: Option<&str>) -> String {
    match device {
        Some("mobile") => "mobile",
        Some("desktop") => "desktop",
        _ => "browser",
    }
    .to_string()
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
            let auth = auth.ok();
            let token = auth.as_ref().and_then(|a| a.token.clone());
            let Some(token) = token else {
                return Err(AuthError);
            };
            let device = normalize_device(auth.as_ref().and_then(|a| a.device.as_deref()));

            if let Some(bot_token) = token.strip_prefix("Bot ") {
                let Ok(Some(bot_id)) = bot::authenticate(&state, bot_token).await else {
                    return Err(AuthError);
                };
                let username: Option<(String,)> =
                    sqlx::query_as(r#"SELECT username FROM "User" WHERE id = $1"#)
                        .bind(&bot_id)
                        .fetch_optional(&state.pool)
                        .await
                        .ok()
                        .flatten();
                let Some((username,)) = username else {
                    return Err(AuthError);
                };
                s.extensions.insert(SocketUser {
                    user_id: bot_id,
                    username,
                    device: "bot".to_string(),
                });
                return Ok(());
            }

            match verify_access_token(&state.config, &token) {
                Ok(claims) => {
                    s.extensions.insert(SocketUser {
                        user_id: claims.sub,
                        username: claims.username,
                        device,
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
    let device = user.device.clone();
    let socket_id = s.id.to_string();

    register_handlers(
        &s,
        io.clone(),
        state.clone(),
        user_id.clone(),
        username.clone(),
        device.clone(),
    );

    {
        let io = io.clone();
        let state = state.clone();
        let uid = user_id.clone();
        let device = device.clone();
        let socket_id = socket_id.clone();
        s.on_disconnect(move |s: SocketRef| {
            let io = io.clone();
            let state = state.clone();
            let uid = uid.clone();
            let device = device.clone();
            let socket_id = socket_id.clone();
            async move {
                let sid = s.id.to_string();
                let _ = voice::remove_device(&state, &uid, &sid).await;
                for room in s.rooms().expect("room lookup is infallible") {
                    let room = room.to_string();
                    if let Some(cid) = room.strip_prefix("voice:") {
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
                if let Ok(last_socket) = presence::remove_socket(&state, &uid, &socket_id).await {
                    if last_socket {
                        if let Ok(Some(channel_id)) = call::active_call_of(&state, &uid).await {
                            end_call_for(&io, &state, &channel_id, &uid, "ended").await;
                        }
                        broadcast_presence(&io, &state, &uid, "offline").await;
                    } else {
                        if device == "desktop" {
                            let devices =
                                presence::get_devices(&state, &uid).await.unwrap_or_default();
                            if !devices.iter().any(|kind| kind == "desktop") {
                                let _ = game::clear(&state, &uid).await;
                            }
                        }
                        let status = presence::get_status(&state, &uid)
                            .await
                            .unwrap_or_else(|_| "online".into());
                        broadcast_presence(&io, &state, &uid, &status).await;
                    }
                }
            }
        });
    }

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
    if let Ok(first_socket) =
        presence::add_socket(&state, &user_id, &socket_id, &device).await
    {
        if first_socket {
            let _ = presence::set_status(&state, &user_id, "online").await;
        }
        let status = presence::get_status(&state, &user_id)
            .await
            .unwrap_or_else(|_| "online".into());
        broadcast_presence(&io, &state, &user_id, &status).await;
    }
    emit_voice_devices(&io, &state, &user_id).await;

    replay_ringing_call(&s, &state, &user_id).await;
}

async fn replay_ringing_call(s: &SocketRef, state: &AppState, user_id: &str) {
    let Ok(Some(channel_id)) = call::active_call_of(state, user_id).await else {
        return;
    };
    let Ok(Some(current)) = call::load(state, &channel_id).await else {
        return;
    };
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
    device: String,
) {
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

    s.on(
        "channel:leave",
        move |s: SocketRef, Data(channel_id): Data<String>, ack: AckSender| async move {
            let _ = s.leave(format!("channel:{channel_id}"));
            ack_void(ack);
        },
    );

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "message:send",
            move |s: SocketRef, Data(p): Data<SendPayload>, ack: AckSender| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let is_bot = s
                        .extensions
                        .get::<SocketUser>()
                        .is_some_and(|u| u.device == "bot");
                    let res = async {
                        rate_limit::check(
                            &state,
                            "msg:send",
                            &uid,
                            if is_bot {
                                rate_limit::MESSAGE_SEND_PER_BOT
                            } else {
                                rate_limit::MESSAGE_SEND_PER_USER
                            },
                        )
                        .await?;
                        let ch =
                            channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        channel::enforce_slowmode(&state, &ch, &uid).await?;
                        membership::assert_not_timed_out(&state, &ch, &uid).await?;
                        let sealed = message::parse_sealed(
                            &ch,
                            p.ciphertext.as_deref(),
                            p.enc_epoch,
                            p.enc_version,
                        )?;
                        message::send_message(
                            &state,
                            &p.channel_id,
                            &uid,
                            &p.content,
                            p.reply_to_id.as_deref(),
                            &p.attachment_ids,
                            &p.spoiler_attachment_ids,
                            sealed.as_ref(),
                        )
                        .await
                    }
                    .await;
                    match res {
                        Ok(msg) => {
                            if let Ok(Some(ch)) = channel::get_channel(&state, &p.channel_id).await
                            {
                                deliver_message(&io, &state, &ch, &msg, &uid, &p.content, Some(&s))
                                    .await;
                            }
                            ack_ok(ack, &msg);
                        }
                        Err(e) => ack_err(ack, e.message()),
                    }
                }
            },
        );
    }

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
                        let ch =
                            channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        let sealed = message::parse_sealed(
                            &ch,
                            p.ciphertext.as_deref(),
                            p.enc_epoch,
                            p.enc_version,
                        )?;
                        message::edit_message(
                            &state,
                            &p.message_id,
                            &uid,
                            &p.content,
                            sealed.as_ref(),
                        )
                        .await
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
                        message::delete_message(
                            &state,
                            &p.channel_id,
                            &p.message_id,
                            &uid,
                            can_manage,
                        )
                        .await
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

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("reaction:add", move |Data(p): Data<ReactionPayload>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                if !reaction_ok(&p.emoji) {
                    return;
                }
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
                if membership::assert_not_timed_out(&state, &ch, &uid)
                    .await
                    .is_err()
                {
                    return;
                }
                if let Ok((_, true)) =
                    message::add_reaction(&state, &p.channel_id, &p.message_id, &uid, &p.emoji)
                        .await
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

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("reaction:remove", move |Data(p): Data<ReactionPayload>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                if !reaction_ok(&p.emoji) {
                    return;
                }
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
                    message::remove_reaction(&state, &p.channel_id, &p.message_id, &uid, &p.emoji)
                        .await
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

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on("presence:update", move |Data(status): Data<String>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            async move {
                if !presence::valid_status(&status) {
                    return;
                }
                let _ = presence::set_status(&state, &uid, &status).await;
                let effective = presence::get_status(&state, &uid)
                    .await
                    .unwrap_or_else(|_| status.clone());
                broadcast_presence(&io, &state, &uid, &effective).await;
            }
        });
    }

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        let device = device.clone();
        let socket_id = s.id.to_string();
        s.on("presence:heartbeat", move |Data(lifecycle): Data<String>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            let device = device.clone();
            let socket_id = socket_id.clone();
            async move {
                if !matches!(lifecycle.as_str(), "online" | "idle") {
                    return;
                }
                if let Ok(first_socket) =
                    presence::refresh_socket(&state, &uid, &socket_id, &device).await
                {
                    let _ = presence::set_socket_lifecycle(
                        &state,
                        &uid,
                        &socket_id,
                        &lifecycle,
                    )
                    .await;
                    if first_socket {
                        let _ = presence::set_status(&state, &uid, "online").await;
                        let effective = presence::get_status(&state, &uid)
                            .await
                            .unwrap_or_else(|_| lifecycle.clone());
                        broadcast_presence(&io, &state, &uid, &effective).await;
                    }
                }
            }
        });
    }

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        let socket_id = s.id.to_string();
        s.on("presence:lifecycle", move |Data(status): Data<String>| {
            let state = state.clone();
            let io = io.clone();
            let uid = uid.clone();
            let socket_id = socket_id.clone();
            async move {
                if presence::set_socket_lifecycle(&state, &uid, &socket_id, &status)
                    .await
                    .unwrap_or(false)
                {
                    let effective = presence::get_status(&state, &uid)
                        .await
                        .unwrap_or_else(|_| "online".into());
                    broadcast_presence(&io, &state, &uid, &effective).await;
                }
            }
        });
    }

    {
        let state = state.clone();
        let io = io.clone();
        let uid = user_id.clone();
        s.on(
            "activity:game",
            move |TryData(payload): TryData<Option<GameActivityPayload>>| {
                let state = state.clone();
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    let payload = payload.ok().flatten().unwrap_or_default();
                    if rate_limit::check(
                        &state,
                        "activity:game",
                        &uid,
                        rate_limit::GAME_ACTIVITY_PER_USER,
                    )
                    .await
                    .is_err()
                    {
                        return;
                    }
                    let custom = payload.name.as_deref().filter(|_| payload.game_id.is_none());
                    let changed = game::report(&state, &uid, payload.game_id.as_deref(), custom)
                        .await
                        .unwrap_or(false);
                    if changed {
                        let status = presence::get_status(&state, &uid)
                            .await
                            .unwrap_or_else(|_| "online".into());
                        broadcast_presence(&io, &state, &uid, &status).await;
                    }
                }
            },
        );
    }

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
                            let _ = voice::add_device(&state, &uid, &s.id.to_string(), &channel_id)
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
                    let mine = voice::list_devices(&state, &uid)
                        .await
                        .unwrap_or_default()
                        .into_iter()
                        .any(|d| d.session_id == session_id);
                    if !mine {
                        ack_err(ack, "That device is not connected".into());
                        return;
                    }
                    if session_id == s.id.to_string() {
                        ack_err(ack, "That is this device".into());
                        return;
                    }

                    match session_id.parse() {
                        Ok(sid) => match io.get_socket(sid) {
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
                        let owns =
                            membership::is_server_member(&state, &snd.server_id, &uid).await?;
                        if !owns {
                            return Err(crate::error::AppError::NotFound("Sound not found".into()));
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
                    let res = async {
                        channel::require_channel_access(&state, &p.channel_id, &uid).await?;
                        let patch = voice::VoicePatch {
                            muted: p.muted,
                            deafened: p.deafened,
                            video: p.video,
                            screen_sharing: p.screen_sharing,
                        };
                        voice::patch_voice_state(&state, &p.channel_id, &uid, patch).await
                    }
                    .await;
                    match res {
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

    {
        let io = io.clone();
        let uid = user_id.clone();
        let sid = s.id;
        s.on(
            "e2ee:transfer:signal",
            move |Data(mut payload): Data<serde_json::Value>| {
                let io = io.clone();
                let uid = uid.clone();
                async move {
                    if let Some(object) = payload.as_object_mut() {
                        object.insert("from".into(), json!(sid.to_string()));
                    }
                    let _ = io
                        .to(format!("user:{uid}"))
                        .except(sid)
                        .emit("e2ee:transfer:signal", &payload);
                }
            },
        );
    }

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
