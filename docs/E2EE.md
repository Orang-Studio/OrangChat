# End-to-end encryption for DMs and group DMs

Status: **implemented**, all twelve steps of §11. The device identity graph and
transparency log, the crypto core with pinned test vectors and its Kotlin
mirror, epochs and key envelopes, encrypted send/receive on web and Android,
in-band head gossip, strict mode with its verification UI, the local encrypted
cache and client-side search, device transfer, client-side attachments, and
push decryption are all built. §11 records what each step turned into and the
limits that remain.

### Adding a PC and phone

Private identity keys are never copied. Each device creates its own
non-extractable identity key; the transfer copies only the encrypted-conversation
history keys and authorizes the new device.

To add an Android phone from a PC that is already authorized:

1. Sign in to the same OrangChat account on both devices. Enable TOTP two-factor
   authentication if you have an authenticator app; accounts without one use a
   one-time email code instead (§4.4).
2. On the PC, open **Settings → Encryption → Add another device**. The PC shows
   a one-time device-transfer QR.
3. On the phone, open **Settings → Encryption → Add this phone → Scan code from
   PC** and point OrangChat's built-in camera scanner at the QR. The system
   Camera app plus **Open OrangChat** remains an alternative.
4. Read the confirmation screen before continuing. It explains that the phone
   will create its own Android Keystore identity, both devices will compare a
   safety code, the authorized PC will require a fresh security code, and only
   then will encrypted history keys move. Nothing is transferred until
   **Continue transfer** is tapped.
5. Compare the six digits on both screens. Cancel if they differ; otherwise tap
   **The digits match** on both.
6. Enter a fresh security code on the already-authorized PC - a current TOTP
   code when the account has an authenticator, otherwise the six-digit code the
   PC emails to the account address. It encrypts the history bundle to the phone
   and signs the phone into the append-only device log.
7. Wait for the phone to say it is authorized. It then appears in the device
   list and can decrypt existing history.

The reverse direction works too: a new PC can show its device code and an
already-authorized phone can scan it. Pasting a complete
`orangchat://device-transfer?...` value remains a fallback, not the primary
desktop workflow.

Android 12 (API 31) or newer is required. API 31 is the first Android release
whose Keystore supports a non-extractable ECDH agreement key; supporting older
versions would contradict §3's private-key guarantee.

Things the implementation settled that this document left open:

- **The epoch id is minted by the client, not the server.** Every wrapping is
  bound to it as the HKDF salt before the request is sent, so a server-assigned
  id would have to be predicted before it existed. It is 128 bits of CSPRNG.
- **Inclusion and consistency proofs are the log itself.** For a hash chain,
  proving entry *N* is under head *H* means replaying entries *N…H*, and a device
  log is a handful of entries. `GET /e2ee/users/:id/devices?since=N` returns
  exactly that range and the client replays it against its pinned head. There is
  no Merkle tree and no need for one at this size; a public mirror and external
  auditors (§6.1) can be added without changing the entry format.
- **Clients pin every entry hash, not just the head.** Gossip is only checkable
  that way: a server that equivocated at seq 2 and then moved on would be
  invisible to a device whose own head had passed it. The list is short by
  construction and lives beside the pinned commitment.
- **What gets encrypted is a payload, not a string.** The server assigns the
  message cuid after the client seals, so it cannot be bound in the AAD (§2).
  The payload therefore carries `sentAt`, `replyTo`, a client-side id, the
  attachment keys and the gossiped heads, and clients render those. Messages
  sealed before this format existed are bare UTF-8 and are still read as text
  rather than failed on.
- **Signatures are 64 raw bytes, never DER.** WebCrypto emits `r‖s` and the Rust
  server parses the same; the JCA emits DER, so the Kotlin mirror converts in
  both directions. Getting this wrong fails silently and only across platforms,
  which is exactly the drift the pinned vectors exist to catch.

Goal: for `dm` and `group_dm` channels, the server stores and forwards message
content it cannot read - **including a server that actively tries.** Not "does
not read", not "is not supposed to read": cannot, against an operator who
controls the database, the application code it serves, and every byte on the
wire. Keys are generated on the device, never sent to the server, and never
leave the device they were born on. A second device is enrolled by a
face-to-face pairing (physical proximity) plus a TOTP check, not by copying a
key out of a file.

Every DM is end-to-end encrypted. There is no unencrypted mode and no opt-out.
What varies is **how the recipient's key is authenticated**, and that is a real
distinction with two settings (§6):

| | **Encrypted** (default) | **Encrypted + Verified** (strict) |
|---|---|---|
| Key authentication | key transparency log, inclusion proofs, self-monitoring | human QR scan / safety-number comparison |
| Guarantee | server substitution is **detected**, publicly logged, attributable | server substitution is **prevented**; no CK is wrapped to an unconfirmed key |
| User action | none | one in-person scan, or a call to compare digits |
| Blocks sending | never | until verification completes |

Both modes use the same ciphertext, the same keys, the same server-blind storage.
The default is not "encryption off" and must never be described that way in code,
copy or UI - the difference is prevention versus detection of a first-contact key
substitution, not encryption versus plaintext.

An earlier draft made verification mandatory everywhere. That is the strongest
possible answer to first contact and it makes the product unusable with anyone you
cannot stand next to, so it is now the opt-in strict mode rather than the floor.
§6.1 explains why key transparency makes the default defensible, and §6.4 is
precise about what the default does not give you.

Server channels (`type = "text"`) stay in plaintext. They have public history,
role-based access, moderation, audit logs and search that all depend on the
server reading content, and a server admin can already read every message by
definition. Encrypting them buys close to nothing and costs everything below.

---

## 1. Threat model

**Protected against**

- A database dump, a stolen backup, a compromised Postgres/Redis host.
- The application server itself reading DM content, live or later.
- An operator (including us) served a subpoena for DM content.
- A malicious or compromised Cloudinary: attachment bytes are already sealed
  today, but with a key we hold; after this they are sealed with a key we do not.
- A malicious server inventing a device for an existing, pinned E2EE identity;
  client-validated authorization signatures prevent that device receiving a CK.
- A malicious server substituting a *first* identity at first contact -
  **prevented outright in strict mode** (§6), where no CK is wrapped to a key a
  human has not confirmed out of band.
- The same attack in default mode is **detected rather than prevented**: the
  substituted identity must be published in an append-only log, where the
  victim's own devices see an identity they never created and independent
  auditors see the equivocation (§6.1). Bounded window, permanent evidence,
  no deniability. Read §6.4 before quoting the guarantee.
- A malicious server equivocating - showing different device logs to different
  people - via inclusion/consistency proofs, self-monitoring and gossip (§6.1).
- A malicious *member* of a group DM forging a message from another member.
  Per-sender signatures (§2) make authorship asymmetric, so holding the shared
  conversation key does not confer the ability to speak as someone else.

**Not protected against**

- A compromised endpoint. If your phone or browser is owned, the plaintext is
  right there. This is inherent to E2EE, not a shortcut we took.
- Metadata. The server keeps knowing who talks to whom, when, how often, message
  sizes, attachment sizes, membership changes, read receipts, online status.
  Hiding that is a different (much harder) system.
- The other participant. They can screenshot, copy, or report anything you send.

**A note on client-side code injection.** OrangChat runs community-contributed
JavaScript (`packages/marketplace/src/plugins`) and CSS (themes, profile themes,
`User.customCss`, `User.profileCss`) inside the page that will hold decrypted
messages. Plugins are bundled at build time from reviewed pull requests, so this
is a supply-chain risk of the same kind as any npm dependency, not a runtime
upload hole. But E2EE raises what a bad merge is *worth*: today it leaks data the
server already has, after E2EE it breaks the headline promise. §3 picks key
storage to cap the worst case and §10.6 covers the rest.

---

## 2. Shape of the scheme

Not the Double Ratchet, at least not in v1. The model matches what a group DM
actually is: **one symmetric conversation key per epoch, wrapped separately to
every member device.**

```
Device Identity Key (per device, never leaves it)
  ├── IK-sig   ECDSA P-256   - signs this device's key bundle
  └── IK-dh    ECDH  P-256   - receives wrapped conversation keys

Conversation Key  CK          AES-256-GCM, random, per (channel, epoch)
  └── wrapped once per member device:
        shared  = ECDH(ephemeral_priv, device.IK-dh_pub)
        wrapKey = HKDF-SHA256(shared, salt=epochId, info="orangchat/ck-wrap/v1")
        wrapNonce = 12 random bytes, stored beside the envelope
        envelope  = AES-256-GCM(wrapKey, wrapNonce, CK, aad=epochId ‖ deviceId)

Message Key  MK = HKDF-SHA256(CK, info = "orangchat/msg/v1" ‖ senderDeviceId ‖ seq)
  nonce      = 96 zero bits            - see "Nonces" below, do not randomise
  ciphertext = AES-256-GCM(MK, nonce, plaintext, aad)
  aad        = channelId ‖ epoch ‖ senderDeviceId ‖ senderUserId ‖ seq

Sender signature (mandatory, every message)
  sig = ECDSA-P256-SHA256(sender IK-sig_priv,
          "orangchat/msg-sig/v1" ‖ channelId ‖ epoch ‖ senderDeviceId
          ‖ senderUserId ‖ seq ‖ SHA-256(ciphertext ‖ nonce ‖ aad))
```

**Why per-message keys instead of using CK directly.** Several devices send under
one CK. Random nonces under a shared key start colliding around 2³² messages, and
a nonce reuse in GCM is catastrophic, not degraded. Deriving MK from
`senderDeviceId || seq` gives every sender a private key space, so no two devices
can ever collide no matter how badly a counter is managed.

**Why AAD binds sender and epoch.** Without it the server could replay Alice's
ciphertext attributed to Bob, or into another epoch. It cannot bind `messageId`,
because the server assigns the cuid after the client encrypts - so the plaintext
envelope carries its own `sentAt`, `replyTo` and client-side id, and the client
displays *those*, not the server's copy. The server can still lie about ordering
and timestamps; it cannot lie about content or authorship.

**Why a per-sender signature, and why AAD is not enough.** CK is symmetric and
shared by every device in the conversation. `senderDeviceId` and `seq` are public
- they are in the AAD, not secret - so *any member holding CK can derive any
other member's MK* and produce a ciphertext that decrypts and authenticates
perfectly as somebody else. A shared secret gives confidentiality; it cannot give
authorship the moment more than one party holds it. Against the server the AAD
binding is sufficient (the server never has CK); against a malicious group member
it is worth nothing. This is exactly why Signal's sender-keys and MLS carry a
per-sender signing key on top of the group secret.

So: **every message is signed by the sending device's `IK-sig`, and a receiving
client MUST verify that signature against the sender device's authorized bundle
(§6) before rendering.** An unsigned or badly-signed message is a hard failure,
not a downgrade. Consequences worth stating plainly:

- Message franking (§10.4) works, and only works, because of this. Verifying a
  GCM tag proves *someone in the conversation* wrote it; verifying the signature
  proves *which device* did. A report backed only by a GCM tag is forgeable by
  anyone in the group, including the reporter.
- It costs deniability. Signed messages are transferable proof of authorship to
  third parties, which the shared-CK scheme alone was not. For a chat product
  that ships reporting and franking, that trade is already implied - we cannot
  simultaneously promise "reports prove authorship" and "authorship is deniable".
  Say which one we sell. If deniable 1:1 DMs matter later, they belong in the
  v2 Double Ratchet mode alongside forward secrecy, not here.

**Nonces.** Specified, not left to the implementer:

- *Message nonce is all zeroes, deliberately.* MK is already unique per
  `(CK, senderDeviceId, seq)`, so each MK encrypts exactly one message and a
  fixed nonce is safe - this is the standard key-per-message construction.
  Randomising it is the dangerous option: 96-bit random nonces start colliding
  around 2³² messages, and a per-device counter that is not truly monotonic
  across reinstall, restore or a duplicated cache silently repeats. The safety
  argument rests entirely on **`seq` never repeating for a given
  `(CK, senderDeviceId)`**, so `seq` is persisted with the epoch, is monotonic,
  and a client that cannot prove monotonicity (restored cache, unclear state)
  MUST mint a new epoch rather than guess.
- *Wrap nonce is 12 random bytes*, stored in `KeyEnvelope.wrapNonce`. Each wrap
  uses a fresh ephemeral ECDH key, so the wrapKey is one-shot and random is fine.
- Test vectors in `packages/shared` pin both, so web and Kotlin cannot drift into
  incompatible-or-worse-unsafe nonce handling.

### Epochs and rotation

A new epoch (new CK, re-wrapped to everyone) is minted on:

- conversation creation,
- any participant added or removed,
- any participant adding or removing a device,
- 30 days or 10 000 messages, whichever comes first,
- manual "reset encryption" in channel settings.

A removed member keeps the epochs they already had - they were there, they read
those messages, that's not a leak. They cannot read anything from the new epoch.

### What this gives and doesn't

- **Forward secrecy: partial.** Compromising a device today exposes the epochs
  whose CKs it still holds - which, for readable history, is all of them. This is
  the same tradeoff WhatsApp/Signal make for backups and multi-device history:
  you cannot have "new phone can read old messages" *and* strong forward secrecy.
  We chose readable history. Say so in the UI, don't claim Signal parity.
- **Post-compromise security:** an epoch rotation after the attacker's device is
  removed locks them out of everything after.
- Roadmap: a Double Ratchet (or MLS) for 1:1 DMs in v2 for people who want the
  stronger property, as a per-conversation "disappearing/forward-secret mode"
  that explicitly does not sync history to new devices.

---

## 3. Where private keys live

Private keys are **non-extractable**. Not "stored encrypted" - the raw bytes
never exist in application memory at all, on either platform.

| | Web | Android |
|---|---|---|
| Generation | `crypto.subtle.generateKey({name:"ECDH",namedCurve:"P-256"}, false, …)` | `KeyPairGenerator` with `AndroidKeyStore`, `setIsStrongBoxBacked(true)` when available |
| Storage | the `CryptoKey` handle in IndexedDB (structured-clone; the browser keeps the material outside JS) | Keystore / TEE / StrongBox |
| Unlock | none by default; optional "require unlock" via WebAuthn PRF | `setUserAuthenticationRequired(true)` for the transfer key |
| Export | impossible | impossible |

P-256 rather than X25519/Ed25519 specifically because **it can be
non-extractable in both WebCrypto and the Android Keystore.** libsodium.js is a
better curve on paper and a worse system in practice here: it hands your identity
key to JavaScript as bytes, in a page that renders user-authored markdown, CSS
and profile HTML. Any script-execution bug in that surface then yields the
identity key itself. Code that can *use* the key while the tab is open is a bad
day; code that can *exfiltrate* it owns the account's future traffic on every
device that trusts it. Curve choice loses to that.

CKs are unavoidably in JS memory to do bulk decryption. They are stored at rest
wrapped under an AES-GCM key that is itself non-extractable, so a stolen
IndexedDB dump is inert without the origin's key handles.

Desktop (`packages/desktop`) is Electron loading the live site, so it inherits
the web path with its own origin storage - it is a distinct device with its own
identity key, which is correct.

---

## 4. Device transfer: nearby + a second factor

The requirement was "moving keys to another device needs a private connection
between those 2 devices, they have to be nearby, and copying requires a fresh
security code."

**The identity key is not what moves.** It can't - it's non-extractable, by
design. The new device generates its *own* identity key. What crosses the room is
the **history bundle**: the CKs for past epochs, which is what makes old messages
readable. Future messages work because the new device gets published into your
device list and everyone rotates to an epoch wrapped for it too.

This is strictly better than copying a key: two devices are never
cryptographically the same principal, so one can be revoked without touching the
other.

### Flow

```
NEW phone                     OLD PC (signed in, holds history)         SERVER
  │                                    │                                  │
  │                                    │ 1. create transferId + pairSecret │
  │◀──────── camera scans PC QR ───────│   transferId ‖ pairSecret(32B)    │
  │                                    │                                  │
  2. generate IK-sig / IK-dh (non-extractable)                            │
  │── public IKs + self signature (no pairSecret) ────────────────────────▶│
  │                                    │◀────── opaque public hello ───────│
  │                                    │                                  │
  3.        both derive: SAS = HKDF(ECDH ‖ pairSecret)[0..6]              │
  │         show 6 digits on both screens; human confirms they match      │
  │                                    │                                  │
  4.                                   │ prompt for TOTP / email code ───▶│
  │                                    │◀── transferGrant (60s, 1 use) ───│
  │                                    │                                  │
  5. history bundle = AES-GCM(HKDF(ECDH ‖ pairSecret), {epoch CKs})       │
  │◀═══ WebRTC datachannel, LAN-only ══│    (or opaque relay, §4.3)       │
  │                                    │                                  │
  6. OLD signs ("add-device/v1" ‖ userId ‖ new IK pubs ‖ transferId)
  │  POST /devices {IK pubs, self sig, authorization sig, transferGrant} ──▶│
  │                                    │            publishes device, bumps epochs
```

### 4.1 Proximity

The QR scan **is** the proximity proof: a camera needs line of sight, and the
`pairSecret` inside it never touches the network. Everything downstream is
encrypted to a key that only exists if someone physically pointed a camera at a
screen.

We additionally restrict the transport to the local network: WebRTC with no TURN
servers configured for this data channel, and the selected candidate pair
required to be `host`/`prflx` (or mDNS `.local`) - refuse `relay`, refuse a
`srflx` pair that traversed a different network. Same Wi-Fi, or USB tether, or
nothing.

Honest caveat: this is a strong heuristic, not a proof. Two devices on the same
corporate VPN can look local. The QR + SAS pair is the real guarantee; the LAN
restriction is defence in depth and a UX signal ("bring the phones together").

### 4.2 SAS

Six digits derived from the ECDH output and `pairSecret`, shown on both screens,
confirmed by a human tap. This kills a relay attacker who somehow got the QR
contents (photographed over a shoulder, screen-shared) but is not the device in
the room. Do not skip it; the QR alone is confidentiality, not authentication.

### 4.3 Fallback transport

If the datachannel can't establish (captive portal, AP client isolation), fall
back to `POST /transfers/{id}/blob` - the server stores an opaque ciphertext for
90 seconds, single fetch, then deletes. The server holds the blob and none of the
key material; `pairSecret` came off the QR. Same security, worse latency. It must
still require a completed SAS confirmation to unlock the upload.

This is the path used precisely on hostile networks, so the endpoint's own
hardening is part of the design, not an implementation detail:

- `transferId` is **128 bits of CSPRNG output**, not a cuid. Cuids embed a
  counter and a timestamp and are guessable-adjacent; this value is the only
  thing standing between an on-path attacker and a fetch race.
- Fetch is **single-use and rate-limited** (a handful of attempts per
  `transferId` and per source, then the transfer is burned and must restart).
  A burned transfer surfaces in the UI as a failure, not a silent retry.
- Losing the race gains the attacker nothing anyway: the blob is sealed under
  `HKDF(ECDH ‖ pairSecret)` and `pairSecret` only ever existed on the QR. This
  is defence in depth against traffic analysis and denial, not the security
  boundary - but a fetched-and-deleted blob the real device never receives is a
  failure mode users will hit, so make it loud.

### 4.4 2FA

The **old** device submits a fresh second-factor code to `POST /devices/transfer-grant`.
The server returns a 60-second single-use grant bound to
`(userId, transferId, newDeviceIkFingerprint)`. The grant enforces the code and
server policy, but it is **not** cryptographic authority to add a device.
`POST /devices` also carries an authorization signature made by the old device’s
`IK-sig`. Clients validate that signature and its chain before wrapping any CK.
An operator cannot bypass this by changing the server-side validation: peers do
the same validation locally and reject the invented device.

Two kinds of code buy the grant, decided by what the account has enrolled:

- **TOTP** (`services/totp.rs`) when the account has an authenticator app. The
  code is verified, or a single-use backup code is consumed.
- **A one-time email code** when it does not. `POST /e2ee/transfer-grant/email-code`
  mints a six-digit code into the same `EmailLoginCode` table as sign-in
  (10-minute lifetime, five attempts, one live code per account) and emails it;
  `POST /e2ee/transfer-grant` then takes the code plus the returned `loginToken`.

The email path exists because demanding TOTP from an account that has no
authenticator - and cannot mint one without one - is a softlock. Its proof is
mailbox possession, exactly the second factor sign-in's email code uses.

Policy consequences, and they're not optional:

- **A second factor is always required.** TOTP where one is enrolled, an emailed
  code otherwise - no code of either kind, no second device, and a "you will
  lose your history if you lose this device" warning on the single-device path.
- Account lockdown (`User.lockdownAt`) blocks transfer grants outright - it
  already means "nothing new attaches to this account", and a device is very much
  something new.
- A transfer grant is a security event: audit log entry, push to every other
  device, email.

---

## 5. Data model

```prisma
model Device {
  id            String   @id @default(cuid())
  userId        String
  name          String            // "Pixel 8", "Firefox on Linux"
  platform      String            // "web" | "android" | "desktop"
  ikSigPub      Bytes             // SPKI, ECDSA P-256
  ikDhPub       Bytes             // SPKI, ECDH  P-256
  bundleSig     Bytes             // self-signature over the two keys + userId
  authorizedBy  String?           // null only for the first (genesis) device
  authorizationSig Bytes?         // IK-sig signature by authorizedBy
  createdAt     DateTime @default(now())
  lastSeenAt    DateTime @default(now())
  revokedAt     DateTime?
  user          User     @relation(fields: [userId], references: [id], onDelete: Cascade)
  keyEnvelopes  KeyEnvelope[]
  @@index([userId])
}

model ChannelEpoch {
  id         String   @id @default(cuid())
  channelId  String
  epoch      Int
  createdAt  DateTime @default(now())
  createdBy  String            // device id that minted the CK
  channel    Channel  @relation(fields: [channelId], references: [id], onDelete: Cascade)
  envelopes  KeyEnvelope[]
  @@unique([channelId, epoch])
}

model KeyEnvelope {
  id           String @id @default(cuid())
  epochId      String
  deviceId     String
  ephemeralPub Bytes            // sender's one-off ECDH pub for this wrap
  wrapNonce    Bytes            // 12 random bytes, per envelope
  wrapped      Bytes            // AES-GCM(CK) - server-opaque
  epoch        ChannelEpoch @relation(fields: [epochId], references: [id], onDelete: Cascade)
  device       Device       @relation(fields: [deviceId], references: [id], onDelete: Cascade)
  @@unique([epochId, deviceId])
  @@index([deviceId])
}

model DeviceLogEntry {
  id           String   @id @default(cuid())
  userId       String
  seq          Int               // per-user, gapless, append-only
  kind         String            // "genesis" | "add-device" | "revoke"
  payload      Bytes             // the signed statement itself
  entryHash    Bytes             // SHA-256(prevHash ‖ payload)
  prevHash     Bytes?            // null only at seq 0
  signature    Bytes             // by genesis (seq 0) or an authorized device
  createdAt    DateTime @default(now())
  @@unique([userId, seq])
  @@index([userId])
}
```

`DeviceLogEntry` is the transparency log of §6.1. It is append-only at the
application layer *and* hash-chained, so the server cannot rewrite history
without every client that cached a later `entryHash` noticing. Rows are never
updated or deleted, including on revocation.

Changes to existing tables:

```prisma
model Channel {
  // …
  e2ee        Boolean @default(false)   // one-way latch: never goes back to false
  epochNumber Int     @default(0)
}

model Message {
  // …
  ciphertext Bytes?        // envelope; null on plaintext messages
  encEpoch   Int?
  encVersion Int?          // 1
}
```

`content` stays `String NOT NULL` and is written as `""` for encrypted messages.
Deliberate: every existing query, DTO builder and `content ILIKE` search keeps
compiling and simply finds nothing, instead of silently matching a base64 blob or
NPE-ing on a null. Grepping for `\.content` at implementation time will find
every place that assumed plaintext.

`Channel.e2ee` is a latch. Once true it never returns to false, or an attacker
who controls the server can downgrade a conversation to plaintext and the clients
would happily comply. Clients must refuse to *send* plaintext into a channel they
have ever seen as encrypted, independently of what the server currently claims.

---

## 6. Trust, verification, and device authorization

The server does not decide which devices belong to a user. Device membership is
an append-only, client-verifiable signature graph:

1. The first device is the **genesis device**. It self-signs a genesis statement
   containing `userId`, both identity public keys, and a random identity
   generation id. That statement’s hash is the user’s E2EE identity commitment.
2. A later device is valid only when its complete bundle is signed by the
   `IK-sig` of an active device already reachable from that genesis statement.
   The signed bytes include the new keys, `userId`, `transferId`, and protocol
   domain separator; signatures cannot be moved between accounts or requests.
3. Every client validates the complete chain itself. It must not wrap a CK, accept
   an epoch, or send an encrypted message while the chain is missing, invalid,
   forked, or contains an unauthorized device.
4. Revocations are signed operations too. Rows are never deleted. A lost last
   device cannot be bypassed through account recovery: the user must create a new
   E2EE identity, loses the old history, and every peer sees an identity change
   that requires verification again.

This prevents a malicious server from generating a key pair, inserting it into
the device list, and receiving a wrapped CK. The fake device has no authorization
signature from an existing device. TOTP, sessions, database access, or server
administrator privileges cannot manufacture that signature. A compromised
authorized endpoint still can; endpoint compromise is outside the threat model.

There is one unavoidable bootstrap boundary: before a peer has pinned the genesis
commitment, a malicious server can substitute a different first identity. No
protocol can authenticate a never-before-seen key using only data supplied by
that same untrusted server.

**There is no TOFU mode**, in the sense of "accept whatever key arrives and never
check it again". Both modes authenticate the key; they differ in whether the
check is a human comparison up front or a continuous automated audit.

**Default mode - Encrypted.** The client accepts the peer's genesis commitment
from the server *only* against a verified inclusion proof in the transparency log
(§6.1), pins it, and monitors it from then on. A substituting server must publish
its forgery into an append-only log where the victim's own devices and third-party
auditors are looking. Sending is never blocked.

**Strict mode - Encrypted + Verified.** The client does not create, wrap, or
accept a CK for a peer whose genesis commitment has not been confirmed out of
band. No ciphertext exists before verification, so a substituted key has nothing
to decrypt. Messages typed beforehand are **queued locally, encrypted at rest, and
never handed to the server** - not "sent insecurely with a warning". The compose
box stays usable; delivery is what waits.

In both modes, once a commitment is pinned, any genesis change, rollback, fork,
invalid device authorization, or unexplained disappearance is a hard failure, not
a dismissible notice.

Safety numbers are derived from both pinned genesis commitments, not from a
server-returned device list. Adding a correctly authorized child device does not
change the safety number; replacing the E2EE identity does.

### 6.1 Key transparency, in v1

Key transparency is what makes the default mode defensible, so it is v1 scope and
not a later addition. This is well-trodden ground - CONIKS academically, Google
Key Transparency, WhatsApp's Auditable Key Directory, Apple's iMessage Contact Key
Verification. **Do not invent anything here.**

- Every identity event is an entry in the hash-chained, append-only
  `DeviceLogEntry` (§5). Each entry commits to the previous one.
- A client fetching a peer's device list also fetches an **inclusion proof**, and
  a **consistency proof** against the log head it last saw. It refuses to proceed
  on a head that moved backwards, forked, or omits an entry it has already seen.
- **Self-monitoring is the mechanism that closes first contact without a human.**
  Every account continuously audits its *own* log. If the server mints a fake
  genesis for Vakaris and serves it to a stranger, Vakaris's real devices see an
  identity in their own log that they never created and raise a hard failure. The
  victim catches the attack, not the sender - which is why the default mode does
  not need the sender to do anything.
- Clients **gossip log heads** in-band: every device of an account cross-checks
  heads it has seen for shared contacts, and heads ride inside already-encrypted
  messages. A server that equivocates must keep every lie consistent across every
  device of every participant forever; one mismatch is a hard failure.
- **Third-party auditors and a publicly mirrored log are the goal state.** Gossip
  alone is weaker than an independent auditor. Ship gossip in v1, design the log
  so external auditors can be added without a format change, and treat auditor
  onboarding as the thing that upgrades the default mode's guarantee from "our
  clients would notice" to "anyone can check".

### 6.2 Verifying at a distance

Strict mode's scan assumes two people in one room. Most conversations are not
that, so this is the case that decides whether strict mode is usable at all.

**Most people should not verify manually, and that is the design working.**
Default mode already authenticates the key against the transparency log and
monitors it forever. Remote users are not "unprotected until they verify" - they
are covered by detection instead of prevention. The UI must not nag them toward a
ritual they cannot perform; an unverified contact in default mode is the normal,
expected state, not a warning.

For users who do want prevention remotely, the safety number has to travel over a
channel the OrangChat server does not control. Ranked by how much they actually
give you:

1. **A phone call or video call on another network.** You authenticate the key by
   recognising a voice or face. Cheap, and strong against an operator who controls
   OrangChat but not the telephone network. Live conversational impersonation of
   someone you know is a much higher bar than swapping a key in a database.
2. **A different messenger you already trust** (Signal, iMessage). Sound, with the
   trust honestly relocated: you are asserting that whoever compromised OrangChat
   did not also compromise that channel. Usually true; say so plainly rather than
   implying it is free.
3. **Meeting later.** Start in default mode, scan when you next see each other.
   Verification is not time-limited, and the comparison at that point *retro-
   actively reveals* a past substitution even though it cannot undo it (§6.4).
4. **An OrangChat voice call - weakest, and do not present it as verification.**
   LiveKit media traverses our SFU in plaintext to the SFU (§10.5), so the same
   operator in the threat model sits in the media path. Comparing digits over the
   channel the attacker controls is not out-of-band. Until call E2EE ships, the
   client should refuse to treat an in-app call as a verification channel.

The practical consequence: **strict mode is for people who can meet, call, or
already share another trusted channel.** That is a real limit and the setting's
description should say so, rather than implying everyone can reach the strongest
guarantee if they try hard enough.

### 6.3 Groups

Pairwise safety numbers work for a DM and collapse in a `group_dm`: N members
means N(N−1)/2 comparisons and nobody does them.

- **Default mode is the only supported group mode in v1.** Transparency-log
  authentication and self-monitoring apply per member exactly as in a DM, with no
  human step and no O(N²) anything.
- **Strict mode is DM-only in v1.** Inheriting the DM rule into groups would let
  one strict member block a twenty-person conversation until they had personally
  verified nineteen people - a single user imposing an enormous cost on a group
  that never opted in. If a strict user joins a group, their strict policy simply
  does not apply there, and the UI says so on the group rather than silently
  downgrading in the background.
- **Transitive display, not transitive enforcement.** Members you have already
  verified 1:1 show as verified inside the group. That is informational; it does
  not gate sending.
- **Group safety number**: a deterministic hash over every member's pinned genesis
  commitment, sorted. It changes when membership changes or when anyone resets
  their identity, giving members a one-glance way to confirm they are all in the
  same group with the same people. Show it; do not require it.

Strict groups are a legitimate v2 feature (they need the transitive-trust model to
be enforcement-grade, plus a sane story for invites). Deferring is a scope call,
not an oversight.

### 6.4 What each mode does and does not promise

Say this precisely, internally and in the UI, because the difference is the whole
security argument:

- **Default mode does not prevent first-contact substitution.** It makes it
  detectable, permanently logged, and attributable. A server that tries must
  publish evidence into a structure the victim's own devices audit. The honest
  phrasing is *"the server cannot read your messages without being caught"*, not
  *"the server cannot read your messages"*.
- **Strict mode prevents it.** No CK is ever wrapped to an unconfirmed key, so
  there is no window and nothing to detect. This is the mode that supports the
  unqualified claim.
- **Neither is retroactive.** Enabling strict on an existing conversation does not
  protect messages already sent under earlier epochs, whose CKs are already
  distributed. What it does is *reveal* a prior substitution - the safety number
  will not match - which is the highest-signal security event the app can produce
  and must be surfaced as a full-screen failure, not a toast.
- Marketing copy quotes the default mode's guarantee. Do not let the strict-mode
  sentence be used for the product as a whole.

### 6.5 Mode semantics: scope, asymmetry, conversion

**Strict is a per-user policy, scoped in effect to a conversation.** It is set
globally ("require verification for anyone new") or per conversation, and it never
mutates anybody else's account.

- **A strict user talking to a non-strict user does not turn the other person
  strict.** The strict client refuses to wrap a CK to an unverified peer, so *that
  one conversation* will not send until verification happens. The other user's
  global setting is untouched and their other conversations are unaffected. Any
  design where one account can flip another account's security policy is a remote
  setting-mutation primitive and a griefing vector - keep the blast radius at the
  conversation.
- **The outcome is mutual even though the policy is not.** Verification pins both
  directions, so once it completes both parties are verified regardless of who
  required it.
- **Both ends need copy for the blocked state**, or it reads as a bug to the
  person who chose nothing: *"Vakaris requires verification before messaging. Scan
  their code or compare safety numbers to continue,"* with the flow one tap away.

**Converting a conversation to strict at any time: yes.** The mechanism already
exists - verify, then mint a new epoch (§2). The new CK is wrapped only to
verified devices. No new concepts, and history stays readable under its old
epochs, subject to the non-retroactivity in §6.4.

**Downgrading out of strict is deliberate, not a latch.** Unlike `Channel.e2ee`
(§5), strict is a user policy rather than a channel property, and people
legitimately change their minds. But turning it off requires local re-authentication
and posts a visible event in the conversation, so a hijacked session cannot quietly
relax it and neither party can be downgraded without the other seeing.

### 6.6 Where the choice surfaces

Attach verification to moments, not to a settings page nobody opens. In priority
order:

1. **The friend-add QR - by far the highest value.** Adding a contact in person by
   scanning their code verifies them for free, inside a gesture the user is
   already making. No security chore, no explanation needed. Every contact added
   this way is permanently verified at zero UX cost.
2. **A quiet persistent affordance in the DM header**: a small indicator with three
   states - verified, encrypted (unverified), identity changed - that opens the
   safety number and "Scan QR" screen. Always available, never in the way.
3. **Escalation moments are the only loud ones.** Identity reset, unauthorized
   device, log fork: blocking interstitial, per §6. Users who never thought about
   verification will meet the concept here first, so the copy must stand alone.
4. **Global toggle in Privacy & Safety**, next to `dmPrivacy` and lockdown:
   "Require verification before messaging anyone new." Off by default. Per
   conversation override in DM settings.
5. **Onboarding gets an explainer, not a choice.** One line at genesis: messages
   are end-to-end encrypted, contacts can be verified in person for extra
   protection. Asking users to pick a mode at signup guarantees a random answer.

Do not badge unverified default-mode conversations with warning colours. Default
mode is secure; decorating it as deficient trains users to ignore the indicator in
case 3, which is the one that matters.

**Every one of these surfaces has to work for somebody who does not know what a
key is.** That is a hard requirement, not polish: a guarantee the user cannot
describe is a guarantee they cannot act on, and the loud states in case 3 are
useless to someone with no model of what changed. So:

- One plain-language explainer, written in locks, keys and a logbook, reachable
  from every surface that mentions encryption - the conversation indicator, the
  conversation intro, the Encryption settings page and the Privacy toggle.
  `HowEncryptionWorks.tsx` on web, `EncryptionExplainer.kt` on Android, kept in
  step with each other so the two clients do not teach two mental models.
- The two modes are shown as a **choice between two encrypted options**, never as
  one switch. A lone toggle labelled "verify before sending" reads as
  "encryption: off/on", which is the exact misreading §6 forbids; both cards say
  they encrypt, and the difference named is prevented-versus-caught.
- Jargon stays out of the primary path. "Safety number" is introduced as the
  thing you read aloud on a call, and it sits *after* the sentence explaining
  what it is for rather than above it.

### 6.7 The verification QR

The contact-verification QR carries the **genesis identity commitment** - the
thing safety numbers derive from - not a loose "key":

```
type tag ‖ userId ‖ IK-sig_pub ‖ IK-dh_pub ‖ genesisCommitment
```

Public material only. Scanning pins the commitment locally; it never contacts the
server for the value it is checking.

Two implementation hazards:

- **There are now three QR codes in the product** - QR sign-in (shipped), device
  transfer (§4), and contact verification. They are visually identical and one of
  them authorises a new device. Every payload carries a **domain-separated type
  tag**, and every scanner hard-rejects the wrong type with a specific message.
  Without this, "scan this to add me" eventually gets someone to scan a transfer
  code.
- **One scan verifies one direction only.** If A scans B's code, A has pinned B;
  B has pinned nothing. There is no way to bootstrap trust in A's key from the
  fact that A trusted B's. So the flow is: A scans B, the app immediately flips to
  A's own code and prompts *"now have them scan yours"*. Two scans, about ten
  seconds, they are standing together anyway. **The UI must not mark the contact
  verified after one scan** - that is the bug this note exists to prevent.

A "Show my QR" button lives on the profile and in the DM header, symmetrical with
the scanner, so either party can start.

### 6.8 Verifying at a distance

Scanning needs two people and one room. Everything else needs the safety number,
and printing it is not enough on its own: a screen full of digits with nowhere to
type the other person's copy asks the user to compare sixty of them by eye, and
then never learns the answer. That left the whole remote path advisory - nothing
was ever pinned, so a pair who are not standing together could not reach verified
at all, and by §6.5 that put verify-first mode out of their reach too.

So the "not in the same room" card takes input:

- The typed code is normalised to digits only (`normalizeSafetyNumber`, mirrored
  in `E2ee.kt`) before comparison. Spacing, dashes and stray whitespace are how
  people transcribe numbers off a phone call; treating them as a mismatch teaches
  users the check is broken rather than that they are.
- Anything short of all 60 digits normalises to null and is reported as
  incomplete, never as a mismatch. A partial code must never produce the alarm
  copy.
- A match in a DM pins the peer exactly as a scan does, and says the other side
  still has nothing written down until they do the same. A match in a group
  confirms the membership and pins nothing, per §6.3.
- A mismatch is inline, not an interstitial, and names the mundane cause first. A
  mistyped digit is far more likely than an attacker, and burning the §6
  escalation UI on typos is how that UI stops being believed.

This is not a weaker check than scanning. The digits still travelled over a
channel the server does not control, and it is the user's ear that authenticates
the voice reading them; what the field adds is that a machine does the comparing,
so one swapped digit in the middle cannot be waved through by someone who has
already decided it probably matches.

---

### 6.9 Starting over after losing every device

Every other recovery path in this document is cryptographic: a device that still
holds the key signs a revocation, or signs a new device in (§4). There is one
state where no such signature can ever be produced again - the last keyed device
is gone - and the protocol on its own has no answer for it. `enroll_genesis`
refuses while any device is listed, `revoke_device` refuses without an authorized
signer, so the account is stuck permanently. Two things follow.

**The account needs a way out.** `POST /e2ee/keys/deletion` schedules the erasure
of the device rows and the device log; `KeyEnvelope` follows by cascade. Both
tables have to go, because a surviving log head means the next genesis entry is
no longer at seq 0 with no previous hash, and `check_chain` rejects it.

The wipe is not authorized by a password or a TOTP code, because whoever stole
the account has both. It is authorized by *time and noise*:

- A delay - 3 hours behind a TOTP code, 24 without one. 2FA is offered rather
  than required: an account that never enabled it is exactly the one that cannot
  produce a code, and demanding one would leave the people this exists for with
  no way out at all. They wait longer instead.
- An email and a push to every device **the moment it is requested**, carrying a
  cancel link. Notifying only on execution would mean the first the owner hears
  of it is after the messages are already unrecoverable.
- An automatic abort if any device holding the key checks in while the request
  waits. `lastSeenAt` is moved only by `/e2ee/seen`, which a client calls only
  once it has loaded a local identity - so this measures "a working key still
  exists", not "somebody logged in". Session presence would be useless here: the
  attacker's own session would cancel the request they filed.
- Refused outright while the account is in lockdown, which exists precisely to
  freeze an account somebody else got into.

**A device holding a key does not wait for any of that.** `POST
/e2ee/keys/deletion/now` performs the same wipe immediately, authorized by a
signature over

```
encodeFields("orangchat/erase-keys/v1", userId, deviceId, issuedAt)
```

made with the identity signing key of a device that is still unrevoked in the
log. Nothing above applies to it, and that is the point: every one of those
controls is compensating for a request that proves nothing beyond a password,
while this one is made by the very key the waiting period was protecting. A TOTP
code on top would be gating the strong proof behind the weak one. `issuedAt` is
RFC 3339 and must be within `PROOF_MAX_AGE_SECONDS` (300) of the server's clock,
so a signature captured off a device cannot be replayed as a wipe later; lockdown
still refuses. The scheduled path is not merely slower for such a device, it is
unusable - the abort-on-check-in rule means the phone would cancel its own
request the next time it started.

**Their contacts need a way back too.** An account that starts over trips
`identity-changed` on every device that had pinned it (§6.6), and that check
throws *before* the new commitment can be written. Without a way to accept the
change, every contact of somebody who lost their devices is left unable to read
or send in that conversation, forever, on every device - a permanent break caused
by a completely legitimate event.

`acceptIdentityChange` is the way out, and only for `identity-changed`. It drops
the pin - which is what is rejecting the new identity - then re-runs the full
chain replay and re-pins with `verifiedAt: null`. So it forgives a *changed*
identity, never a malformed one, and the contact returns as unverified rather
than quietly inheriting the trust the old identity had earned. A failed replay
restores the old pin rather than leaving the account unpinned.

It is deliberately not offered for `log-fork`, `log-rollback`, `log-omission` or
`unauthorized-device`. Those are a server contradicting something it already
committed to, and there is no honest story that ends with the user clicking past
them; only an identity change has one.

---

## 7. Attachments

Today `services/attachment_crypto.rs` seals uploads with `ORANGAE1` under a
server-held key, which protects against Cloudinary but not against us. For E2EE
channels the client takes over:

1. Client generates a random per-file key `FK`, encrypts the bytes **and the
   filename and content-type** with AES-256-GCM.
2. Uploads the sealed blob through the existing upload path. The server treats it
   as opaque bytes and does not apply `attachment_crypto` a second time.
3. `FK`, the real filename, size, dimensions and blurhash go **inside the message
   plaintext**, not in `Message.attachments`. The server's copy of
   `attachments` keeps only the storage id and byte length.
4. Download goes through `/api/media/proxy` exactly as it does now, so the
   existing IP-leak protection is unchanged; the proxy returns ciphertext.

Consequences:

- Thumbnails and Cloudinary transformations are dead in E2EE channels. The client
  generates its own thumbnail, encrypts it as a second blob, and puts that key in
  the envelope too.
- **A thumbnail is two things, because one of them is not reliable.** The sealed
  second blob is sharp, but it is an upload: the frame grab can fail, the upload
  can fail, the row can go unclaimed and be swept, or it can simply not come down
  beside the message. Each of those ends in a permanently black video, and a
  measurable share of sent clips hit one — so the payload also carries `blur`, a
  16px JPEG in base64, which renderers upscale and blur behind the play button.
  It needs no fetch and no row, so it is there offline, in search, and before the
  first paint; the sealed blob replaces it when and if it resolves. Sizing is set
  by `MAX_PUSH_CIPHERTEXT_CHARS` (§8): the stamp costs roughly 1.8 characters of
  ciphertext per byte, and a payload that outgrows that cap is pushed without its
  envelope, which would trade a black rectangle for a silent notification.
- **Android additionally recovers a poster from the clip itself.** Once a sealed
  video has been decrypted to play, `MediaMetadataRetriever` pulls a frame out of
  the cached plaintext. This is the only thing that ever gives a poster to a clip
  sent before `blur` existed and whose thumbnail blob is gone.
- **`services/image_moderation.rs` cannot run.** This is a product decision, not a
  technical one: E2EE DMs mean unsolicited images are not scanned. Recommended
  posture: keep scanning on server channels, drop it in E2EE DMs, and lean on
  §10.4 reporting plus the existing DM privacy controls (`dmPrivacy`, friends-only,
  lockdown) which are the real defence against strangers sending you things.

---

## 8. Notifications

`PushPayload` currently carries a server-composed `title`/`body`. For E2EE
channels the server has no body to compose.

Both clients can decrypt in the notification path, so the payload becomes:

```jsonc
{ "kind": "message", "channelId": "…", "messageId": "…",
  "encEpoch": 7, "ciphertext": "…base64…", "senderId": "…" }
```

- **Web:** the service worker opens IndexedDB, unwraps the CK, decrypts, and calls
  `showNotification` with the real text. Web Push payloads cap around 4 KB, so
  send at most the first ~1 KB of ciphertext and fall back to "New message" when
  it doesn't fit or decryption fails.
- **Android:** `NotificationHelper` does the same against the Keystore. Already
  data-only, so no FCM-side change.
- **Failure mode is a placeholder, never a crash.** "New message from Vakaris" is
  an acceptable notification; a swallowed exception that shows nothing is not.
- **The decrypt is opt-out.** A device-local "Show message text" setting (web:
  Settings → System → Notifications; Android: Settings → Privacy →
  Notifications) turns the placeholder into the only thing a notification ever
  says. When it is off the envelope is never opened at all — the keys stay
  unused rather than producing a plaintext the device has been told to withhold
  — and the server-composed `body` for a plaintext channel is dropped with it,
  so the setting does not lie about which conversations it covers. Device-local
  because which screens are safe to read over is a property of the phone, not of
  the account; the web copy lives in IndexedDB (`orangchat-e2ee`, store
  `settings`) because a service worker cannot see localStorage, and the worker is
  what runs when the app is closed.

Note the metadata reality: the push service (Google/Mozilla/Apple) learns that
this account received something, and when. Nothing we can do about that here.

---

## 9. What has to move client-side

Each of these is server code today that reads DM plaintext.

| Feature | Today | After |
|---|---|---|
| Search (`services/message.rs:553`, `content ILIKE`) | Postgres | Local index over decrypted cache; E2EE channels excluded from the server query |
| Link previews (`http/link_previews.rs`) | Server fetches OG tags | Client fetches through `/api/media/proxy` so the IP-leak fix still holds; sender-generated preview travels inside the encrypted envelope |
| Drafts (`model Draft`) | Plaintext in Postgres | Encrypt under a device-local key, or keep local-only for E2EE channels - a plaintext draft of an encrypted message is a comical hole |
| Image moderation | OpenAI on upload | Not possible (§7) |
| Reactions (`model Reaction.emoji`) | Plaintext | Leave plaintext in v1; encrypting them leaks the same metadata via row existence. Document it |
| Read state / unread | Message ids only | Unchanged |
| Pins, replies | Ids only | Unchanged; reply preview comes from the local cache |
| Message edit | Server rewrites `content` | Client re-encrypts under the current epoch and PUTs a new envelope |

**Local message cache is now load-bearing**, not an optimisation. Search, reply
previews and notification history all depend on it. Web: IndexedDB, encrypted at
rest under a non-extractable key. Android: Room with SQLCipher, or plain Room
inside app-private storage with a Keystore-wrapped key.

---

## 10. Open decisions

1. **Auto-enable or opt-in? Settled, and built.** Encryption itself is automatic
   the moment every participant has at least one E2EE-capable device - no
   verification precondition in default mode (§6). Mixed-capability conversations
   stay plaintext behind a banner naming who is holding it back
   (`PlaintextNotice.tsx`; `waitingOn` on Android's `ConversationEncryptionInfo`),
   and a plaintext conversation is labelled plaintext, never "encrypted,
   pending". Strict mode is the opt-in layer on top, per §6.5. There is
   deliberately **no per-conversation encrypted/plaintext switch** - the only
   choice a user makes is the §6 mode, and the UI must not imply otherwise.
2. **Recovery.** Lose every authorized device and the E2EE identity can never add
   another device. Account recovery creates a new genesis identity. Every peer
   sees a hard identity-change failure (§6) - indistinguishable from an attack,
   by design - which strict-mode contacts clear by re-verifying and default-mode
   contacts clear by explicitly accepting the new identity, never silently.
   Optional opt-in, **not built**: a
   128-bit recovery code, shown once, wraps a copy of the CK archive server-side.
   It can restore readable history locally after an identity reset, but it cannot
   authorize the replacement device into the old identity. This is a real
   weakening (anyone with the code can read the archive), so it is off by default
   and, for now, absent entirely - which means §10.3 is what actually happens.
3. **History after the last device is lost.** Without a recovery code, old
   messages show as "encrypted on a device you no longer have." Future messages
   use new conversations or epochs bound to the newly verified genesis identity.
   Neither TOTP nor an account administrator may bypass the identity reset.
4. **Reporting. Done.** A recipient can report from the message menu. For an
   encrypted message the client deliberately discloses the one-message key
   `HKDF(CK, senderDeviceId ‖ seq)`; it never uploads CK. The server retrieves
   its own stored ciphertext and epoch, opens the GCM tag with that derived key,
   and verifies **the sender's per-message signature (§2)** against the signing
   device in the author's append-only log before preserving the plaintext. The
   disclosed key cannot derive an earlier/later key. The GCM tag alone proves
   only that someone holding CK wrote it - in a group that includes the reporter,
   so a tag-only report is forgeable by the person filing it. The signature is
   what makes franking mean anything, which is why it is mandatory on every
   message rather than attached at report time.
5. **Calls.** LiveKit media is DTLS-SRTP to the SFU today, so the SFU sees media.
   LiveKit supports E2EE via insertable streams with an external key provider -
   the per-conversation CK is a natural fit. Separate project; note it and move on.
6. **Injected client code (plugins, themes, custom CSS).** Not a blocker - plugins
   are reviewed source bundled at build time, and review is a real boundary. Three
   things do need doing, in this order:

   - **CSP is the high-leverage fix, and it's not plugin-specific. Done**, in
     `deploy/nginx/chat.oranges.lt.conf`. A tight `connect-src`/`img-src` stops
     exfiltration whether the code arrived via a bad plugin PR, a theme, or a
     markdown XSS. Note that CSS alone is an exfil channel - attribute selectors
     plus `background: url(https://attacker/…)` can leak decrypted message text
     with no JavaScript at all, which sandboxing plugins would not fix but
     `img-src` does. `style-src` keeps `'unsafe-inline'` because community
     themes and profile CSS are injected as inline stylesheets; that is the
     trade, and `img-src` is what stops it being an exfiltration primitive.
     **Verify against a running deployment before relying on it** - a CSP is
     only as good as the first page load that does not break.
   - **Correct the API doc. Done.** `packages/marketplace/src/plugins/api.ts`
     used to claim "a plugin cannot reach past what is handed to it." That is
     false: `plugin.start(ctx)` is a same-realm call, so a plugin can reach
     `indexedDB`, `fetch` and the DOM regardless of how narrow `PluginContext`
     is. The sentence was harmless in itself and dangerous the moment it
     convinced a reviewer the API was load-bearing. It now says plugins are
     trusted because they are reviewed.
   - **Sandboxing (iframe/Worker + postMessage) becomes mandatory only if plugins
     ever load at runtime.** Keep that as the explicit condition. Until then it's
     defence in depth, and it can land after E2EE ships.

   `PluginMessage.content` already hands plugins message plaintext, so nothing
   about the exposure is new - E2EE only changes the blast radius.

---

## 11. Implementation order

1. **Done.** `Device` model + `DeviceLogEntry`, enrolment of the *current*
   device, device list UI, revocation. `services/e2ee.rs`, `http/e2ee.rs`,
   `features/e2ee/identity.ts`, `settings/EncryptionTab.tsx`.
2. **Done.** Crypto core in `packages/shared/src/e2ee.ts` with pinned test
   vectors in `e2ee.test.ts`, mirrored by `crypto/E2ee.kt`, `E2eeQr.kt` and
   `E2eePayload.kt`, whose `E2eeTest` reproduces the same hex vectors. Same
   discipline as `emoji.ts` ↔ `EmojiTokens.kt`: one source of truth for the
   grammar or the clients drift and messages become undecryptable. The Rust
   server mirrors the statement encodings.
3. **Done.** Transparency log, consistency replay, self-monitoring on every
   start, safety numbers and group safety numbers, the three domain-separated QR
   payloads with cross-kind rejection, heads gossiped inside every encrypted
   message and checked on receipt, strict mode (§6.5) and the verification UI
   (§6.6/§6.7): the DM header indicator, the safety-number and QR dialog, the
   friend-add-by-code path, and the full-screen interstitial for identity
   changes, forks and rollbacks. The plain-language explainer, the two-card mode
   chooser and the plaintext banner of §6.6 and §10.1 landed afterwards, on both
   clients; the mechanism had been complete for a while and remained
   unexplainable to the people it was for.
4. **Done.** `ChannelEpoch` / `KeyEnvelope`, epoch minting and rotation, all
   server-side and content-blind.
5. **Done for DMs and group DMs on web and Android.** Encryption turns itself on
   once every participant has a device (§10.1); there is no flag. Plaintext
   fallback exists only for conversations that were never encrypted, and the
   server *refuses* a plaintext send into a latched channel rather than trusting
   clients to.
6. **Done.** Decrypted bodies are cached in IndexedDB sealed under the same
   non-extractable vault key as the conversation keys, rehydrated when a
   conversation opens, and searched locally - the search dialog runs against
   this device for any encrypted conversation rather than asking a server whose
   copy of `content` is empty. Android keeps the same cache in its Keystore-backed
   preferences. Drafts in encrypted channels are local-only for the same reason.
7. **Done.** Android has Keystore-backed identity keys (StrongBox where the
   hardware offers it), enrolment and self-monitoring, epoch sync, encrypted
   send/receive, push decryption, contact verification by deep link, and an
   Encryption settings screen.
8. **Done.** Group DMs ride the same path as DMs; rotation on membership change
   and the group safety number are in. Strict mode deliberately does not apply
   to them (§6.3).
9. **Done.** Transfer ids, TOTP-gated grants, the opaque relay and authorized
   enrolment on the server; on the client, the new device's code, the SAS
   comparison, the history bundle over a LAN-restricted WebRTC data channel with
   the relay as fallback, and the old device signing the new one into the log.
10. **Done.** Client-side per-file keys, filenames and content types sealed into
    the message payload, an opaque upload path that skips the server's own
    sealing, client-generated encrypted thumbnails, and decryption on download.
11. **Done.** The server sends the envelope instead of a body for encrypted
    conversations; the web service worker (built from the shared crypto core
    rather than a hand-copied `sw.js`) and Android's `FcmService` open it and
   show the real text, falling back to a placeholder.
12. **Done.** Recipient reports disclose one message rather than a conversation:
    the server validates the original GCM tag with a one-message derived key,
    verifies the sender-device signature and stores the cryptographically
    attributable plaintext for review. Web and Android both explain this
    disclosure before the user confirms.

Limits worth stating plainly rather than discovering:

- **A notification for an epoch this device has not yet seen shows a
  placeholder.** The service worker holds no access token, so it cannot fetch
  the envelope for a key minted while the device was asleep. Epochs rotate on
  membership changes and long intervals, not per message, so this is the
  exception; it is still a real one.
- **Large sealed attachments are decrypted on demand, not streamed.** AES-GCM
  has one tag over the whole file, so inline playback means the whole file in
  memory. Past 64 MB the UI offers a decrypt-and-download instead.
- **The LAN restriction on device transfer is a heuristic, not a proof.** Two
  machines on one corporate VPN can look local. The QR plus the six-digit
  comparison is the guarantee (§4.1).
- **`image_moderation` does not run in encrypted DMs**, by construction (§7).
- The Android notification quick-reply path now seals on the device before
  posting, so a reply from the shade works in an encrypted conversation instead
  of being refused.
