#!/usr/bin/env bash
#
# Build and ship OrangChat.
#
# Everything is compiled before anything is released, so a build that fails
# leaves production exactly as it was rather than half-updated. Within the
# release phase the order is fixed and deliberate: migrations, then the backend
# that expects them, then the SPA that talks to it, then nginx.
#
#   ./deploy/deploy.sh                 # the whole site: shared, web, server, migrate, nginx
#   ./deploy/deploy.sh web             # just the SPA
#   ./deploy/deploy.sh server migrate  # backend + schema
#   ./deploy/deploy.sh --all           # + android + desktop
#   ./deploy/deploy.sh --build-only    # compile everything, touch nothing
#   ./deploy/deploy.sh --list
#
set -euo pipefail

readonly REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly WEB_ROOT=/var/www/chat.oranges.lt
readonly SERVICE=orangchat
readonly HEALTH_URL=http://127.0.0.1:3001/health
readonly ENV_FILE=/etc/orangchat/orangchat.env
# Pinned: an unpinned `prisma` now resolves to v7, which rejects this schema.
readonly PRISMA=prisma@6.19.3
readonly LOCKFILE=/tmp/orangchat-deploy.lock

readonly DEFAULT_TARGETS=(shared web server migrate nginx)
readonly ALL_TARGETS=(shared web server migrate nginx android desktop)
# Compile order, then release order. A target may appear in either or both.
readonly BUILD_ORDER=(shared web server android desktop)
readonly RELEASE_ORDER=(migrate server web nginx android)

DRY_RUN=0
ASSUME_YES=0
BUILD_ONLY=0
RUN_TESTS=0
PRUNE_WEB=0
INSTALL_DEPS=0

declare -A WANT=()

# ── output ──────────────────────────────────────────────

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_BLUE=''; C_GREEN=''; C_YELLOW=''; C_RED=''
fi

step() { printf '\n%s==>%s %s%s%s\n' "$C_BLUE" "$C_RESET" "$C_BOLD" "$*" "$C_RESET"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '%s !! %s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
ok()   { printf '%s  ✓ %s%s\n' "$C_GREEN" "$*" "$C_RESET"; }
die()  { printf '%s !! %s%s\n' "$C_RED" "$*" "$C_RESET" >&2; exit 1; }

# Echo every command before running it, and run nothing under --dry-run. Use
# this for anything with an effect; plain reads can just run.
run() {
  printf '%s    $ %s%s\n' "$C_DIM" "$*" "$C_RESET"
  ((DRY_RUN)) || "$@"
}

usage() {
  cat <<EOF
${C_BOLD}OrangChat deploy${C_RESET}

  ./deploy/deploy.sh [options] [target ...]

${C_BOLD}Targets${C_RESET}
  shared     Build @orangchat/shared (web and the SPA both compile against it)
  web        Build the SPA and sync it to $WEB_ROOT
  server     cargo build --release, restart $SERVICE, wait for /health
  migrate    prisma migrate deploy against the production database
  nginx      Install deploy/nginx/*, nginx -t, reload
  android    Signed release APK, published to the in-app updater
  desktop    Windows Electron installer (needs docker + wine)

  No target given runs: ${DEFAULT_TARGETS[*]}

${C_BOLD}Options${C_RESET}
  -a, --all         Every target, including android and desktop
  -b, --build-only  Compile only; skip everything that touches production
  -n, --dry-run     Print what would run, change nothing
  -y, --yes         Do not prompt before the release phase
  -t, --test        Run the test suites before building
  -i, --install     pnpm install first
      --prune       Delete web-root files the build no longer produces
  -l, --list        List targets and exit
  -h, --help        This

${C_BOLD}Notes${C_RESET}
  android needs a bumped versionCode and android/app/changelog/<versionName>.txt,
  and publishes to every existing install the moment it lands. It is not part of
  the default set for that reason.

  --prune keeps download/ and .well-known/ regardless. It also removes the old
  hashed assets a still-open browser tab may be about to request, so the default
  is to leave them in place.
EOF
}

# ── argument parsing ────────────────────────────────────

parse_args() {
  local -a targets=()
  while (($#)); do
    case "$1" in
      -a|--all)        targets=("${ALL_TARGETS[@]}") ;;
      -b|--build-only) BUILD_ONLY=1 ;;
      -n|--dry-run)    DRY_RUN=1 ;;
      -y|--yes)        ASSUME_YES=1 ;;
      -t|--test)       RUN_TESTS=1 ;;
      -i|--install)    INSTALL_DEPS=1 ;;
      --prune)         PRUNE_WEB=1 ;;
      -l|--list)
        printf '%s\n' "${ALL_TARGETS[@]}"
        exit 0
        ;;
      -h|--help)       usage; exit 0 ;;
      all)             targets=("${ALL_TARGETS[@]}") ;;
      -*)              die "Unknown option: $1 (try --help)" ;;
      *)
        local known=0 t
        for t in "${ALL_TARGETS[@]}"; do [[ "$1" == "$t" ]] && known=1; done
        ((known)) || die "Unknown target: $1 (try --list)"
        targets+=("$1")
        ;;
    esac
    shift
  done

  ((${#targets[@]})) || targets=("${DEFAULT_TARGETS[@]}")
  local t
  for t in "${targets[@]}"; do WANT[$t]=1; done
  # The SPA imports @orangchat/shared, so asking for one asks for the other.
  [[ -n "${WANT[web]:-}" ]] && WANT[shared]=1
  return 0
}

wanted() { [[ -n "${WANT[$1]:-}" ]]; }

# ── preflight ───────────────────────────────────────────

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 is not installed"; }

preflight() {
  need_cmd pnpm
  wanted server && need_cmd cargo
  { wanted web || wanted migrate || wanted nginx || wanted server; } && need_cmd sudo
  wanted web && need_cmd rsync
  wanted desktop && need_cmd docker

  if wanted android; then
    [[ -x "$REPO/android/gradlew" ]] || die "No android/gradlew"
    grep -q '^signing.propertiesFile=' "$REPO/android/local.properties" 2>/dev/null ||
      warn "android/local.properties has no signing.propertiesFile - the release APK will be unsigned and publishUpdate will refuse it"
  fi

  if wanted migrate && [[ ! -r /dev/null || ! -e "$ENV_FILE" ]]; then
    sudo test -e "$ENV_FILE" || die "No $ENV_FILE to read DATABASE_URL from"
  fi
}

confirm() {
  ((ASSUME_YES)) && return 0
  ((DRY_RUN)) && return 0
  [[ -t 0 ]] || return 0
  local reply
  printf '\n%sRelease to production?%s [y/N] ' "$C_BOLD" "$C_RESET"
  read -r reply
  [[ "$reply" == [yY]* ]] || die "Cancelled - nothing was released"
}

# ── build phase ─────────────────────────────────────────

build_shared() {
  step "Build @orangchat/shared"
  run pnpm --filter @orangchat/shared build
}

build_web() {
  step "Build the SPA"
  # ~68MB of game-presence tiles, derived from third-party store art and so kept
  # out of git. They live in the SPA's public/ and vite copies them into dist,
  # which means they have to exist *before* the build or every game on every
  # profile renders a broken image. Re-running is cheap: it only fetches ids
  # whose tile is missing.
  run python3 "$REPO/packages/shared/scripts/fetch-game-art.py"
  # `build` is tsc --noEmit followed by vite build, so a type error stops here
  # rather than reaching the web root.
  run pnpm --filter @orangchat/client build
  [[ -f "$REPO/packages/client/dist/index.html" ]] || ((DRY_RUN)) ||
    die "No dist/index.html after the build"
}

build_server() {
  step "Build the backend"
  run cargo build --release --manifest-path "$REPO/packages/server-rs/Cargo.toml"
}

build_android() {
  step "Build the Android release APK"
  run "$REPO/android/gradlew" -p "$REPO/android" assembleRelease
}

build_desktop() {
  step "Build the Windows desktop app"
  run pnpm --filter @orangchat/desktop dist:win:docker
}

run_tests() {
  step "Tests"
  run pnpm --filter @orangchat/shared test
  run pnpm --filter @orangchat/client test
  if wanted server; then
    run cargo test --manifest-path "$REPO/packages/server-rs/Cargo.toml"
  fi
  if wanted android; then
    run "$REPO/android/gradlew" -p "$REPO/android" testDebugUnitTest
  fi
}

# ── release phase ───────────────────────────────────────

release_migrate() {
  step "Apply database migrations"
  local url
  url="$(sudo grep -m1 '^DATABASE_URL=' "$ENV_FILE" | cut -d= -f2-)"
  url="${url%\"}"; url="${url#\"}"; url="${url%\'}"; url="${url#\'}"
  [[ -n "$url" ]] || die "DATABASE_URL is not set in $ENV_FILE"
  info "using DATABASE_URL from $ENV_FILE"
  # migrate deploy is a no-op when nothing is pending, so this is safe to run
  # on every release.
  printf '%s    $ DATABASE_URL=… pnpm dlx %s migrate deploy%s\n' "$C_DIM" "$PRISMA" "$C_RESET"
  ((DRY_RUN)) || DATABASE_URL="$url" pnpm dlx "$PRISMA" migrate deploy \
    --schema "$REPO/prisma/schema.prisma"
}

release_server() {
  step "Restart $SERVICE"
  run sudo systemctl restart "$SERVICE"
  ((DRY_RUN)) && return 0

  local i
  for i in $(seq 1 30); do
    if curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
      ok "$SERVICE is healthy"
      return 0
    fi
    sleep 1
  done
  # Say what happened rather than exiting on a bare non-zero: the logs are the
  # only thing that explains a backend that came up and then died.
  sudo systemctl --no-pager --lines=30 status "$SERVICE" || true
  die "$SERVICE did not answer $HEALTH_URL within 30s"
}

release_web() {
  step "Sync the SPA to $WEB_ROOT"
  local -a flags=(-a --human-readable)
  if ((PRUNE_WEB)); then
    # download/ holds the published APKs and .well-known/ the app links; both
    # are put there by other steps and are not the SPA's to delete.
    flags+=(--delete --exclude 'download/***' --exclude '.well-known/***')
  fi
  run sudo mkdir -p "$WEB_ROOT"
  run sudo rsync "${flags[@]}" "$REPO/packages/client/dist/" "$WEB_ROOT/"
  run sudo mkdir -p "$WEB_ROOT/.well-known"
  run sudo cp "$REPO/deploy/assetlinks.json" "$WEB_ROOT/.well-known/"
}

release_nginx() {
  step "Install nginx configuration"
  # chat.oranges.lt.conf -> sites-available/chat.oranges.lt (+ enabled symlink);
  # env-easter-egg.conf is an include, so it belongs in snippets/.
  local conf name
  for conf in "$REPO"/deploy/nginx/*.conf; do
    name="$(basename "$conf" .conf)"
    if [[ "$name" == env-easter-egg ]]; then
      run sudo cp "$conf" /etc/nginx/snippets/env-easter-egg.conf
    else
      run sudo cp "$conf" "/etc/nginx/sites-available/$name"
      run sudo ln -sfn "/etc/nginx/sites-available/$name" "/etc/nginx/sites-enabled/$name"
    fi
  done
  run sudo nginx -t
  run sudo systemctl reload nginx
}

release_android() {
  step "Publish the Android update"
  # publishUpdate re-runs assembleRelease (already up to date), hashes the APK
  # and writes it plus update.json into the web root. Two of these at once
  # corrupt each other's manifest, which is what the lock at the top prevents.
  run "$REPO/android/gradlew" -p "$REPO/android" publishUpdate
}

# ── main ────────────────────────────────────────────────

main() {
  parse_args "$@"
  cd "$REPO"

  exec 9>"$LOCKFILE"
  flock -n 9 || die "Another deploy is already running ($LOCKFILE)"

  preflight

  local -a build_steps=() release_steps=()
  local t
  for t in "${BUILD_ORDER[@]}"; do
    wanted "$t" && declare -F "build_$t" >/dev/null && build_steps+=("$t")
  done
  if ((! BUILD_ONLY)); then
    for t in "${RELEASE_ORDER[@]}"; do
      wanted "$t" && declare -F "release_$t" >/dev/null && release_steps+=("$t")
    done
  fi

  printf '%sOrangChat deploy%s%s\n' "$C_BOLD" "$C_RESET" "$( ((DRY_RUN)) && printf ' (dry run)')"
  info "build:   ${build_steps[*]:-nothing}"
  info "release: ${release_steps[*]:-nothing}"

  local started=$SECONDS

  ((RUN_TESTS)) && run_tests
  ((INSTALL_DEPS)) && { step "Install dependencies"; run pnpm install; }

  for t in "${build_steps[@]:-}"; do [[ -n "$t" ]] && "build_$t"; done

  if ((${#release_steps[@]})); then
    confirm
    for t in "${release_steps[@]}"; do "release_$t"; done
  fi

  printf '\n'
  ok "Done in $((SECONDS - started))s: ${release_steps[*]:-${build_steps[*]:-nothing}}"
  if ((! BUILD_ONLY)) && wanted web; then
    info "https://chat.oranges.lt"
  fi
}

main "$@"
