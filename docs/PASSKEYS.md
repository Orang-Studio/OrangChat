# Passkeys

Status: **implemented** on the server, the web client, and Android.

A passkey is a key pair the authenticator - a phone's secure element, a laptop's
TPM, a hardware key - creates for one site and will only ever use for that site.
The private half never leaves it. What travels is a signature over a challenge
the server just made up, so there is nothing to replay, nothing to phish, and
nothing on our side worth stealing: the `Passkey` table holds public keys.

Two things follow from that, and they are the whole design:

1. **A passkey can end a login on its own.** No password, no emailed code, not
   even TOTP. The authenticator already established that a human was present and
   verified them (biometric or device PIN), and the browser or platform bound the
   ceremony to our origin before it would run at all. Adding a second factor on
   top of that would be asking for a weaker proof after a stronger one.
2. **A passkey outranks the emailed code.** When an account has at least one
   passkey, `POST /auth/login` answers a correct password with a passkey
   challenge instead of mailing a code. Email is the weakest link in the account
   - it is what an attacker attacks first - so where a passkey exists it is used.

## Sign-in

Two entry points, one finish.

**Usernameless.** `POST /auth/passkey/start` names no account. The credential the
device offers is what names it: discoverable credentials store the user handle,
and the server resolves the account from the credential id in the response.
This is what the "Sign in with a passkey" button and browser autofill
(conditional mediation) use.

**As the second step of a password login.** `POST /auth/login` with the right
password returns `{ passkeyRequired: true, challenge, ceremonyToken }` rather
than mailing a code. The client answers it immediately - the sheet is already
the natural next thing on screen.

Either way the answer goes to `POST /auth/passkey/finish`, which verifies the
signature, re-applies the lockdown and email-verification gates that any other
login path applies, clears the failed-login counter, and issues the session.

**Falling back.** Someone on a borrowed machine has no passkey to offer. The
passkey step offers "Email me a code instead", which re-submits the password
with `skipPasskey: true`; the server then behaves as it did before passkeys
existed. This is a deliberate downgrade path and it is why passkeys are a
*preference*, not a lockout: an account whose only passkey lives on a lost phone
is still reachable through the address it was registered with.

## Enrolment

Under `/security/passkeys`, alongside the other credential settings.

- `POST /security/passkeys/register/start` - **password-gated**, plus a TOTP code
  when 2FA is on. A passkey is a way in; a hijacked session must not be able to
  quietly add one and keep the account after the password is changed back.
- `POST /security/passkeys/register/finish` - not gated. The ceremony token it
  finishes was only handed out after that password check, is bound to the
  account, and is single-use.
- `PATCH /security/passkeys/:id` - rename. Session-only: the worst a rename can
  do is confuse the owner about which key is which.
- `DELETE /security/passkeys/:id` - password-gated like `start`. Taking a factor
  away is exactly what someone who stole the session would want to do first.

Existing credentials are sent as `excludeCredentials`, so an authenticator that
already holds one for the account says so instead of silently making a second
that nobody can tell apart. Twenty per account (`passkey::MAX_PER_USER`).

## Ceremony state

The challenge the server will accept has to be the server's own, so it is never
handed to the client. `services::passkey` parks the in-progress ceremony in
Redis under an opaque UUIDv4 - `passkey:ceremony:{token}`, 300 s - and reads it
back with a read-then-delete. Single use is the point: a challenge that survived
its answer could be answered twice. An expired or already-used token comes back
as "That took too long - start again".

## RP ID and origins

The RP ID is the host of `CLIENT_ORIGIN` and nothing else. Authenticators scope
credentials to it permanently, so changing it orphans every passkey in
existence - which is why it is derived rather than configured. `CLIENT_ORIGIN`
must be https; browsers will not run a ceremony from an insecure origin, so an
http value is a misconfiguration that is better caught at the server than as an
unexplained failure in the browser.

**Android is the exception that needs configuring.** A native app has no web
origin. Credential Manager signs the same RP ID - that is what lets a passkey
made on the site work in the app - but its client data says

```
android:apk-key-hash:<base64url, unpadded, of the SHA-256 of the signing certificate>
```

which no relying party can guess. `ANDROID_CERT_FINGERPRINTS` lists the
fingerprints allowed to do that, in the colon-separated hex form
`deploy/assetlinks.json` already publishes; the server does the base64url
conversion (`config::android_origin`, pinned by a test, because standard base64
or kept padding fails with nothing but "invalid origin" to go on). Unset, it
means the published release key alone. **A debug build is signed with a
different key and will be rejected until its fingerprint is added here** - and
listed in assetlinks.json too, or the device half never even starts.

That file is the other half of the link: `/.well-known/assetlinks.json` on the
RP-ID host must name the package and fingerprint under
`delegate_permission/common.get_login_creds`. Credential Manager checks it
before it will run a ceremony at all, so a missing entry fails on the phone and
a missing allowed origin fails at the server - two different symptoms, one
fingerprint.

## User handle

WebAuthn wants a UUID and our ids are cuids, so the handle is
`uuid_v5(NAMESPACE_URL, user_id)`. Stable for the life of the account, which is
what matters: an authenticator keys its own keychain entry on the handle, and a
handle that changed would leave a second orphaned entry every time someone
enrolled. Nothing reads it back - sign-in resolves the account from the
credential id, which is unique on its own.

## Storage

`Passkey` holds `credentialId` (base64url, unpadded - what a discoverable
sign-in is looked up by), the serialized `credential` from webauthn-rs, a
user-chosen `name`, `backedUp`, and `lastUsedAt`. `backedUp` is the
authenticator's own backup-eligibility flag, surfaced in the UI because it is
the difference between "lose the phone, lose the key" and not.

## Where the code is

| | |
|---|---|
| `packages/server-rs/src/services/passkey.rs` | ceremonies, storage, RP construction |
| `packages/server-rs/src/http/auth.rs` | `/auth/passkey/*`, and the login short-circuit |
| `packages/server-rs/src/http/security.rs` | `/security/passkeys/*` |
| `packages/server-rs/src/config.rs` | `android_origins` |
| `packages/client/src/features/auth/webauthn.ts` | browser ceremonies, base64url, conditional UI |
| `packages/client/src/features/settings/PasskeysSection.tsx` | management UI |
| `android/.../feature/auth/Passkeys.kt` | Credential Manager wrapper |
| `deploy/assetlinks.json` | the app ↔ origin link |
