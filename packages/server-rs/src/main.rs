mod auth;
mod auth_security;
mod config;
mod connections;
mod dto;
mod error;
mod http;
mod ids;
mod models;
mod oauth;
mod permissions;
mod services;
mod socket;
mod state;
mod timefmt;

use std::sync::OnceLock;
use std::time::{Duration, Instant};

use redis::aio::ConnectionManager;
use socketioxide::SocketIo;
use sqlx::postgres::PgPoolOptions;
use tokio::net::TcpListener;

use crate::config::Config;
use crate::services::{badge, key_deletion, presence, push, spotify};
use crate::state::AppState;

static START: OnceLock<Instant> = OnceLock::new();

/// Process uptime in seconds (for /health), mirroring process.uptime().
pub fn uptime_seconds() -> f64 {
    START
        .get()
        .map(|s| s.elapsed().as_secs_f64())
        .unwrap_or(0.0)
}

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();
    let _ = START.set(Instant::now());

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,sqlx=warn".into()),
        )
        .init();

    let config = match Config::from_env() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("❌ Invalid environment: {e}");
            std::process::exit(1);
        }
    };
    let port = config.port;

    let pool = match PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url)
        .await
    {
        Ok(p) => p,
        Err(e) => {
            eprintln!("❌ Failed to connect to Postgres: {e}");
            std::process::exit(1);
        }
    };

    let redis_client = redis::Client::open(config.redis_url.clone()).expect("valid REDIS_URL");
    let redis = match ConnectionManager::new(redis_client).await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("❌ Failed to connect to Redis: {e}");
            std::process::exit(1);
        }
    };

    let state = AppState::new(pool, redis, config);

    // Redis outlives this process, while its Socket.IO connections do not.
    // Reconcile before opening the listener so a counter from an earlier
    // process can never make a disconnected user appear online.
    match presence::clear_stale_startup_presence(&state).await {
        Ok(n) if n > 0 => tracing::info!("cleared {n} stale presence key(s) at startup"),
        Ok(_) => {}
        Err(e) => {
            eprintln!("Failed to reconcile presence at startup: {e}");
            std::process::exit(1);
        }
    }

    // Hand-awarded badges are declared in the badges file, so reconcile them
    // here rather than leaving the database as the only record of who has one.
    match badge::sync_from_file(&state).await {
        Ok((0, 0)) => {}
        Ok((granted, revoked)) => {
            tracing::info!("badges reconciled: {granted} granted, {revoked} revoked")
        }
        Err(e) => tracing::warn!("badge reconciliation failed: {e}"),
    }

    // Files get picked and never sent. Nothing else deletes those, so sweep them
    // hourly rather than letting them pile up on disk.
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(3600));
            loop {
                ticker.tick().await;
                match http::attachments::sweep_pending(&state).await {
                    Ok(n) if n > 0 => tracing::info!("swept {n} unsent attachment(s)"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!("attachment sweep failed: {e}"),
                }
            }
        });
    }

    // Key erasures come due on a wall clock nobody is watching. Five minutes
    // rather than the hourly cadence above because the delay the user was quoted
    // should mean roughly what it said, and because every tick that passes after
    // a request comes due is another tick in which a device could check in and
    // abort a wipe the owner already stopped caring about.
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(300));
            loop {
                ticker.tick().await;
                match key_deletion::sweep(&state).await {
                    Ok(n) if n > 0 => tracing::info!("erased encryption keys for {n} account(s)"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!("key erasure sweep failed: {e}"),
                }
            }
        });
    }

    let (layer, io) = SocketIo::builder().build_layer();
    state.set_io(io.clone());
    socket::setup(io.clone(), state.clone());

    // Client heartbeats renew five-minute per-socket leases. Sweep often enough
    // that a missed disconnect becomes visible promptly after its lease expires.
    {
        let state = state.clone();
        let io = io.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(10));
            loop {
                ticker.tick().await;
                match presence::sweep_expired_leases(&state).await {
                    Ok(users) => {
                        for user_id in users {
                            socket::broadcast_presence(&io, &state, &user_id, "offline").await;
                        }
                    }
                    Err(e) => tracing::warn!("presence lease sweep failed: {}", e.message()),
                }
            }
        });
    }

    // Notifications held back from someone's phone while they were at their
    // computer. Thirty seconds is fine grain against a five-minute hold, and the
    // sweep costs nothing when nothing is due.
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(30));
            loop {
                ticker.tick().await;
                match push::flush_deferred(&state).await {
                    Ok(n) if n > 0 => tracing::debug!("released {n} held mobile notification(s)"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!("deferred push sweep failed: {}", e.message()),
                }
            }
        });
    }

    // Spotify has no push event for track changes. Poll only currently-online
    // linked users, then publish changes through the normal presence event.
    {
        let state = state.clone();
        tokio::spawn(async move { spotify::run_poll_loop(state).await });
    }

    // CORS must be outermost so it also covers Socket.IO polling requests.
    let cors = http::cors_layer(&state);
    let app = http::router(state.clone()).layer(layer).layer(cors);

    let addr = format!("127.0.0.1:{port}");
    let listener = TcpListener::bind(&addr).await.expect("bind failed");
    tracing::info!("🍊 OrangChat (rust) listening on http://{addr}");

    // with_connect_info so rate limiting still sees a real peer address when
    // running without nginx in front (dev, or a direct bind).
    let service = app.into_make_service_with_connect_info::<std::net::SocketAddr>();
    if let Err(e) = axum::serve(listener, service).await {
        eprintln!("server error: {e}");
        std::process::exit(1);
    }
}
