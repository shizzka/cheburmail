# CheburMail Architecture

Android E2E encrypted messenger using Yandex/Mail.ru email (IMAP/SMTP) as transport layer.

---

## 1. Module Structure

```
cheburmail/
├── app/                          # Application module — DI, navigation, entry point
│   ├── di/                       # Hilt modules wiring everything together
│   ├── CheburMailApp.kt          # Application class
│   └── MainActivity.kt           # Single-activity Compose host
│
├── core/                         # Shared utilities, models, constants
│   ├── model/                    # Domain models (Message, Contact, Chat, KeyBundle)
│   ├── result/                   # Result wrappers, error types
│   └── util/                     # Base64, time, ID generation
│
├── crypto/                       # Encryption module — zero Android framework deps
│   ├── KeyManager.kt             # Generate, load, store X25519 keypairs
│   ├── MessageEncryptor.kt       # Encrypt plaintext -> CheburMailEnvelope
│   ├── MessageDecryptor.kt       # CheburMailEnvelope -> plaintext
│   ├── GroupKeyManager.kt        # Pairwise key resolution for group chats
│   └── QrCodeCodec.kt           # Encode/decode public key + metadata for QR
│
├── transport/                    # Email transport — IMAP/SMTP via JavaMail
│   ├── smtp/
│   │   ├── SmtpClient.kt        # Send email (blocking, runs on IO dispatcher)
│   │   └── SmtpConfig.kt        # Server, port, credentials
│   ├── imap/
│   │   ├── ImapClient.kt        # Fetch emails (blocking)
│   │   ├── ImapIdleClient.kt    # IMAP IDLE listener (optional, provider-dependent)
│   │   └── ImapConfig.kt        # Server, port, credentials
│   ├── EmailFormatter.kt        # Domain model -> RFC 822 email (MimeMessage)
│   ├── EmailParser.kt           # RFC 822 email -> domain model
│   └── SyncWorker.kt            # WorkManager periodic sync job
│
├── storage/                      # Persistence — Room DB + encrypted key store
│   ├── db/
│   │   ├── CheburDatabase.kt    # Room database definition
│   │   ├── MessageDao.kt        # Messages CRUD
│   │   ├── ChatDao.kt           # Chats CRUD
│   │   └── ContactDao.kt        # Contacts + public keys CRUD
│   ├── entity/                   # Room entities
│   ├── KeyStore.kt              # EncryptedSharedPreferences wrapper for private key
│   └── OutboxQueue.kt           # Pending messages awaiting send
│
└── ui/                           # Jetpack Compose UI
    ├── setup/                    # Onboarding: email creds, key generation
    ├── chatlist/                 # Chat list screen
    ├── chat/                     # Chat conversation screen
    ├── contacts/                 # Contact list, add contact
    ├── qr/                       # QR code display + scanner (CameraX)
    ├── settings/                 # App settings
    └── theme/                    # Material 3 theme, colors, typography
```

### Module Dependency Graph

```
         ┌─────┐
         │ app │  (depends on all modules, wires DI)
         └──┬──┘
    ┌───────┼───────┬──────────┐
    v       v       v          v
 ┌────┐ ┌──────┐ ┌─────────┐ ┌─────────┐
 │ ui │ │crypto│ │transport│ │ storage │
 └─┬──┘ └──────┘ └────┬────┘ └────┬────┘
   │        ^          │           │
   │        │          v           v
   │     ┌──┴───────────────────────┐
   └────>│          core            │
         └──────────────────────────┘

Key rules:
 - crypto has ZERO Android dependencies (pure Kotlin + Lazysodium)
 - transport depends on crypto (encrypts before send, decrypts after receive)
 - storage depends on core models only
 - ui depends on storage (via ViewModels observing Room Flows)
 - app wires everything via Hilt
```

---

## 2. Email Message Format

Every CheburMail message is a standard RFC 822 email. The provider sees valid email metadata but the body is an opaque encrypted blob.

### Envelope Structure

```
From: alice@yandex.ru
To: bob@yandex.ru
Subject: CM/1/<chat-id>/<message-uuid>
Date: Thu, 03 Apr 2026 12:00:00 +0300
MIME-Version: 1.0
Content-Type: application/x-cheburmail; charset=utf-8
X-CM-Version: 1
X-CM-Type: msg
X-CM-Sender-Key-ID: <first-8-hex-of-sender-pubkey>

<base64-encoded encrypted payload>
```

### Subject Convention

```
CM/<version>/<chat-id>/<message-uuid>
```

- **CM** -- fixed prefix, used to filter CheburMail messages on IMAP fetch
- **version** -- protocol version (starts at `1`)
- **chat-id** -- deterministic chat identifier (see below)
- **message-uuid** -- UUIDv4, for deduplication

Chat ID derivation:
- **1-on-1:** `SHA256(sorted(alice_pubkey ∥ bob_pubkey))[:16]` (hex) -- deterministic, same for both parties
- **Group:** random UUIDv4, shared when group is created

### Custom Headers

| Header | Purpose |
|--------|---------|
| `X-CM-Version` | Protocol version for forward compatibility |
| `X-CM-Type` | `msg` (message), `key-announce` (group key distribution), `ack` (delivery receipt) |
| `X-CM-Sender-Key-ID` | First 8 hex chars of sender public key -- helps receiver look up the right decryption key without parsing body |
| `X-CM-Group-Epoch` | (groups only) Key epoch number for group key rotation |

### Body Encoding

The body is the raw output of `crypto_box_easy()` encoded as **standard Base64** (RFC 4648). No JSON wrapping, no extra framing -- just the ciphertext.

```
Plaintext structure (before encryption):
{
  "t": "hello world",           // text content
  "ts": 1743674400,             // unix timestamp (sender clock)
  "re": null                    // reply-to message UUID or null
}
```

The plaintext is UTF-8 JSON, then encrypted, then Base64-encoded into the email body. JSON keeps the wire format extensible (add fields in v2 for media, reactions, etc.) without breaking older clients.

### MIME Type

`application/x-cheburmail` -- a custom type that ensures:
1. Email clients will not try to render or index the body
2. CheburMail can filter its messages by Content-Type during IMAP fetch
3. Providers are unlikely to flag it as spam (it looks like an app-specific attachment)

### Message Size Budget

- Typical text message: ~200 bytes plaintext
- After crypto_box overhead (16 bytes MAC + 24 bytes nonce): ~240 bytes
- After Base64: ~320 bytes
- With headers: ~600 bytes total
- Well within any email provider's limits (Yandex max message size: 30 MB)

---

## 3. IMAP Polling Strategy

### Primary: WorkManager Periodic Sync

```
┌──────────────┐     every 15 min     ┌──────────────┐
│  WorkManager │ ──────────────────── > │  SyncWorker  │
│  (Android OS)│                       │  .doWork()   │
└──────────────┘                       └──────┬───────┘
                                              │
                          ┌───────────────────┼──────────────────┐
                          v                   v                  v
                    ┌───────────┐     ┌──────────────┐   ┌────────────┐
                    │ IMAP fetch│     │ Parse + decrypt│  │ Room insert│
                    │ new emails│     │ CheburMail msgs│  │ notify UI  │
                    └───────────┘     └──────────────┘   └────────────┘
```

**Configuration:**
- `PeriodicWorkRequest` with 15-minute interval (Android minimum)
- Constraint: `NetworkType.CONNECTED` (any network)
- `ExistingPeriodicWorkPolicy.KEEP` to prevent duplicates
- `BackoffPolicy.EXPONENTIAL` starting at 30 seconds on failure

**Fetch logic:**
1. Connect to IMAP (`imap.yandex.ru:993` or `imap.mail.ru:993`, SSL)
2. Open the CheburMail folder (see section 7)
3. `SEARCH UNSEEN SUBJECT "CM/"` -- fetch only unread CheburMail messages
4. For each message: parse headers, extract body, decrypt, insert into Room
5. Mark processed messages as `\Seen`
6. Disconnect

### Secondary: IMAP IDLE (Real-time, Best-Effort)

Both Yandex and Mail.ru IMAP servers support the IDLE extension (RFC 2177). IDLE allows the client to hold an open connection and receive push notifications when new mail arrives.

**Implementation via ForegroundService:**

```
┌──────────────────────────────────────────────────────┐
│ ImapIdleService (Foreground Service)                 │
│                                                      │
│  1. Connect IMAP, authenticate                       │
│  2. SELECT "CheburMail" folder                       │
│  3. folder.idle()  // blocks until server notifies   │
│  4. On EXISTS notification: fetch new messages        │
│  5. Loop back to step 3                              │
│                                                      │
│  KeepAlive: re-issue IDLE every 25 min (RFC: <29min) │
│  Reconnect: on IOException, exponential backoff      │
│  Notification: persistent "CheburMail connected"     │
└──────────────────────────────────────────────────────┘
```

**Trade-offs:**

| Approach | Latency | Battery | Reliability |
|----------|---------|---------|-------------|
| WorkManager only | 0-15 min | Excellent | High (survives Doze) |
| IDLE ForegroundService | <5 sec | Moderate | Medium (OEM kill) |
| Both (recommended) | <5 sec active, 15 min Doze | Good | High |

**Recommended strategy:** Run IDLE in a ForegroundService when the app is active or recently used. WorkManager runs unconditionally as a reliable fallback. When IDLE is connected, WorkManager sync becomes a no-op (no new unseen messages).

### Battery Optimization Notes

- Request users to disable battery optimization for CheburMail (prompt via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
- On Chinese OEM ROMs (Xiaomi, Huawei, Oppo): guide users to "autostart" settings
- ForegroundService with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 14+
- IDLE connection timeout set to 25 minutes (below RFC 2177 recommended 29-minute max)
- On connectivity change (`ConnectivityManager` callback): immediately trigger sync + reconnect IDLE

### Yandex/Mail.ru IMAP Settings Reference

| Provider | Server | Port | Security | Auth |
|----------|--------|------|----------|------|
| Yandex | imap.yandex.ru | 993 | SSL/TLS | App password |
| Mail.ru | imap.mail.ru | 993 | SSL/TLS | App password |

Note: Both providers require enabling IMAP access in web settings and generating an app-specific password.

---

## 4. Crypto Flow

### Key Generation (First Launch)

```
┌─────────────┐
│ First Launch │
└──────┬──────┘
       │
       v
┌──────────────────────────────────────────────┐
│ LazySodium.cryptoBoxKeypair()                │
│  -> X25519 public key  (32 bytes)            │
│  -> X25519 private key (32 bytes)            │
└──────────────────────────────────────────────┘
       │
       ├── Public key  -> stored in Room (ContactDao, self-contact)
       │                  displayed in QR code for sharing
       │
       └── Private key -> EncryptedSharedPreferences
                          (backed by Android Keystore AES-256-GCM)
```

### Key Storage

```
┌─────────────────────────────────────────────────────────┐
│                    Android Keystore                      │
│  ┌────────────────────────────────────────────────────┐ │
│  │ AES-256-GCM master key (hardware-backed if avail.) │ │
│  └────────────────────┬───────────────────────────────┘ │
└───────────────────────┼─────────────────────────────────┘
                        │ encrypts
                        v
┌─────────────────────────────────────────────────────────┐
│         EncryptedSharedPreferences                       │
│  ┌─────────────────────────────────────────────────────┐│
│  │ "x25519_private_key" = <AES-GCM encrypted blob>    ││
│  │ "x25519_public_key"  = <hex string>                 ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

The private key never leaves EncryptedSharedPreferences. Android Keystore provides hardware-level protection on devices with a secure element (TEE/StrongBox).

### QR Code Key Exchange

```
Alice's phone                              Bob's phone
┌──────────┐                               ┌──────────┐
│ Display  │   Alice shows QR to Bob       │  Scan    │
│ QR code  │ ─────────────────────────── > │  Camera  │
│          │                               │          │
│ Payload: │                               │ Extracts:│
│ pubkey   │                               │ pubkey   │
│ email    │                               │ email    │
│ name     │                               │ name     │
└──────────┘                               └──────────┘
                                                │
                 Bob shows QR to Alice          │ saves Alice
              < ───────────────────────────     │ as contact
                                                v
Alice saves Bob ◄──────────────────────   ┌──────────┐
as contact                                │ Display  │
                                          │ QR code  │
                                          └──────────┘
```

**QR payload format (JSON, UTF-8):**
```json
{
  "v": 1,
  "pk": "<base64url-encoded 32-byte X25519 public key>",
  "e": "alice@yandex.ru",
  "n": "Alice"
}
```

Compact enough to fit in a standard QR code (version 6, ~200 bytes).

### Encrypt Pipeline (Sending)

```
Plaintext JSON                    Recipient's public key (from ContactDao)
     │                                      │
     v                                      v
┌─────────────────────────────────────────────────┐
│ 1. Generate random 24-byte nonce                │
│    LazySodium.randomBytesBuf(24)                │
│                                                  │
│ 2. crypto_box_easy(                              │
│       message = plaintext_bytes,                 │
│       nonce   = nonce,                           │
│       pk      = recipient_public_key,            │
│       sk      = sender_private_key               │
│    )                                             │
│    -> ciphertext (plaintext.len + 16 bytes MAC)  │
│                                                  │
│ 3. Envelope = nonce(24) ∥ ciphertext             │
│    (nonce prepended, not secret)                 │
│                                                  │
│ 4. Base64.encode(envelope) -> email body         │
└─────────────────────────────────────────────────┘
```

Using `crypto_box_easy` (not sealed box) because:
- Both parties know each other's public keys (exchanged via QR)
- Provides authentication (sender is verified)
- Sealed box is anonymous -- we want sender verification

### Decrypt Pipeline (Receiving)

```
Base64 email body
     │
     v
┌─────────────────────────────────────────────────┐
│ 1. Base64.decode(body) -> envelope bytes         │
│                                                  │
│ 2. Split: nonce = envelope[0:24]                 │
│           ciphertext = envelope[24:]             │
│                                                  │
│ 3. Look up sender public key:                    │
│    X-CM-Sender-Key-ID header -> ContactDao       │
│    Fallback: match by From: email address        │
│                                                  │
│ 4. crypto_box_open_easy(                         │
│       ciphertext = ciphertext,                   │
│       nonce      = nonce,                        │
│       pk         = sender_public_key,            │
│       sk         = receiver_private_key          │
│    )                                             │
│    -> plaintext bytes                            │
│                                                  │
│ 5. Parse JSON -> Message domain object           │
└─────────────────────────────────────────────────┘
```

If decryption fails (unknown sender, tampered message): log a warning, mark email as seen, skip. Do not crash.

### Group Key Management: Pairwise Approach

**Decision: Pairwise encryption (not Sender Keys)**

For MVP, CheburMail uses pairwise `crypto_box` for group messages. Each message to a group of N participants is encrypted N-1 times, once per recipient, using the sender's private key and each recipient's public key.

**Why pairwise over Sender Keys:**

| Factor | Pairwise | Sender Keys |
|--------|----------|-------------|
| Complexity | Simple -- reuse 1-on-1 crypto | Complex -- symmetric key distribution, ratcheting |
| Implementation | Zero new crypto code | New key management protocol |
| Forward secrecy | Per-message (each uses unique nonce) | Requires chain ratchet |
| Member add/remove | Trivial (just update recipient list) | Requires re-keying, new epoch |
| Cost | N-1 emails per message | N-1 emails per message (same!) |
| Crypto overhead | N-1 encrypt operations | 1 encrypt + key management |

The cost of sending is identical (N-1 emails either way, since email has no multicast). The only downside of pairwise is N-1 encrypt operations per send, but `crypto_box_easy` is sub-millisecond even on low-end Android. Sender Keys become valuable only when transport supports multicast (e.g., a server fan-out), which email does not.

**Sender Keys can be added in v2** if a relay server is introduced.

---

## 5. Data Flow

### Send Message Flow

```
┌────────┐  text   ┌───────────┐  Message   ┌───────────────┐
│   UI   │ ──────> │ ViewModel │ ─────────> │ SendUseCase   │
│ (Chat  │         │           │            │               │
│ Screen)│         └───────────┘            └───────┬───────┘
└────────┘                                          │
                                                    │ 1. Insert into Room
                                                    │    status = SENDING
                                                    │
                                                    v
                                        ┌───────────────────┐
                                        │  OutboxQueue      │
                                        │  (Room table)     │
                                        └───────┬───────────┘
                                                │
                                                │ 2. For each recipient:
                                                v
                                    ┌───────────────────────┐
                                    │  MessageEncryptor     │
                                    │  crypto_box_easy()    │
                                    │  per recipient pubkey │
                                    └───────────┬───────────┘
                                                │
                                                │ 3. Format as email
                                                v
                                    ┌───────────────────────┐
                                    │  EmailFormatter       │
                                    │  -> MimeMessage       │
                                    └───────────┬───────────┘
                                                │
                                                │ 4. Send via SMTP
                                                v
                                    ┌───────────────────────┐
                                    │  SmtpClient           │
                                    │  Transport.send()     │
                                    └───────────┬───────────┘
                                                │
                                                │ 5. Update Room
                                                │    status = SENT
                                                v
                                    ┌───────────────────────┐
                                    │  Room MessageDao      │
                                    │  Flow emits to UI     │
                                    └───────────────────────┘
```

**Sending happens on `Dispatchers.IO`** via coroutine launched from ViewModel. The OutboxQueue pattern ensures messages survive app kill (WorkManager picks up unsent messages on next run).

### Receive Message Flow

```
┌──────────────────┐       ┌──────────────────┐
│  WorkManager     │  OR   │ IMAP IDLE Service │
│  (periodic sync) │       │ (foreground svc)  │
└────────┬─────────┘       └────────┬──────────┘
         │                          │
         └──────────┬───────────────┘
                    │
                    v
         ┌──────────────────┐
         │  ImapClient      │
         │  SEARCH UNSEEN   │
         │  SUBJECT "CM/"   │
         └────────┬─────────┘
                  │
                  │ raw MimeMessage[]
                  v
         ┌──────────────────┐
         │  EmailParser     │
         │  extract headers │
         │  extract body    │
         └────────┬─────────┘
                  │
                  │ CheburMailEnvelope
                  v
         ┌──────────────────┐
         │ MessageDecryptor │
         │ look up sender   │
         │ crypto_box_open  │
         └────────┬─────────┘
                  │
                  │ plaintext Message
                  v
         ┌──────────────────┐
         │ Room MessageDao  │
         │ INSERT OR IGNORE │
         │ (dedup by UUID)  │
         └────────┬─────────┘
                  │
                  │ Flow<List<Message>>
                  v
         ┌──────────────────┐
         │ UI (Compose)     │
         │ recomposes with  │
         │ new messages     │
         └──────────────────┘
```

**Deduplication:** `message_uuid` (from Subject header) is a UNIQUE column in Room. `INSERT OR IGNORE` ensures the same message fetched by both WorkManager and IDLE is stored only once.

**Notification:** After inserting new messages, fire an Android notification if the app is not in the foreground.

---

## 6. Group Chat Design

### Creation Flow

```
Creator (Alice)
     │
     │ 1. Generate random group_id (UUIDv4)
     │ 2. Define member list: [Bob, Carol, Dave]
     │ 3. Send X-CM-Type: group-invite to each member
     │
     ├──> Email to Bob:    group-invite (group_id, members list, group name)
     ├──> Email to Carol:  group-invite (group_id, members list, group name)
     └──> Email to Dave:   group-invite (group_id, members list, group name)
```

**group-invite payload (encrypted per recipient):**
```json
{
  "action": "group-invite",
  "group_id": "a1b2c3d4-...",
  "group_name": "Friends",
  "members": [
    {"e": "alice@yandex.ru", "pk": "<base64url>", "n": "Alice"},
    {"e": "bob@mail.ru",     "pk": "<base64url>", "n": "Bob"},
    {"e": "carol@yandex.ru", "pk": "<base64url>", "n": "Carol"},
    {"e": "dave@mail.ru",    "pk": "<base64url>", "n": "Dave"}
  ]
}
```

This distributes public keys of all members to everyone, so any member can encrypt messages to any other member without prior QR exchange. Trust is transitive through the group creator.

### Sending a Group Message

```
Alice sends "Hello group" to group with Bob, Carol, Dave
     │
     │  For each recipient (N-1 = 3 emails):
     │
     ├──> SMTP to Bob:
     │    Subject: CM/1/<group_id>/<msg_uuid>
     │    Body: crypto_box(plaintext, alice_sk, bob_pk)
     │
     ├──> SMTP to Carol:
     │    Subject: CM/1/<group_id>/<msg_uuid>
     │    Body: crypto_box(plaintext, alice_sk, carol_pk)
     │
     └──> SMTP to Dave:
          Subject: CM/1/<group_id>/<msg_uuid>
          Body: crypto_box(plaintext, alice_sk, dave_pk)
```

**Key insight:** The same `message_uuid` in the Subject ensures all recipients can deduplicate (they each get one copy). The `group_id` in the Subject routes the message to the correct group chat in the UI.

### Rate Limit Considerations

```
Yandex: ~500 emails/day
Mail.ru: ~300-500 emails/day (estimated, undocumented)

Group of 5 people, all chatting:
  - Each message = 4 emails sent
  - At 500/day limit: ~125 messages/day per person
  - At moderate chat pace (1 msg/min): runs out in ~2 hours

Mitigation strategies (v2):
  - Batching: collect messages for 5-10 sec, send as single email with multiple payloads
  - Compression: gzip JSON before encryption
  - Multiple mailboxes: rotate sender accounts
```

For MVP, groups are limited to ~10 members. The UI shows a warning when approaching rate limits.

### Member Management

| Action | Implementation |
|--------|---------------|
| Add member | Creator sends `group-invite` to new member + `member-added` to existing members (includes new member's pubkey) |
| Remove member | Creator sends `member-removed` to all remaining members |
| Leave group | Member sends `member-left` to all members |

No re-keying needed in pairwise model -- removed members simply stop receiving emails.

---

## 7. Email Folder Management

### Dedicated Folder

On first setup, CheburMail creates a dedicated IMAP folder:

```
IMAP command: CREATE "CheburMail"
```

Both Yandex and Mail.ru support custom IMAP folders. This folder serves as the exclusive mailbox for CheburMail messages.

### Server-Side Filter

**Yandex:** Create a filter rule via Yandex Mail web UI or API:
- Condition: Subject contains "CM/"
- Action: Move to "CheburMail" folder

**Mail.ru:** Similar filter via web settings.

**Fallback (if no server filter):** The app searches INBOX for `SUBJECT "CM/"` and moves matching messages to the CheburMail folder via IMAP `COPY` + `STORE \Deleted` + `EXPUNGE` on INBOX.

### Folder Structure

```
INBOX/                  # User's normal email (untouched)
CheburMail/             # All CheburMail messages land here
  \Seen                 # Processed messages (flagged as read)
  \Deleted              # Messages marked for cleanup
```

### Cleanup Strategy

```
┌─────────────────────────────────────────────────┐
│ Cleanup runs as part of periodic SyncWorker     │
│                                                  │
│ 1. Messages older than 7 days AND \Seen:        │
│    -> STORE +FLAGS (\Deleted)                    │
│    -> EXPUNGE                                    │
│                                                  │
│ 2. Rationale:                                    │
│    - 7-day window covers offline scenarios       │
│    - Messages are already in local Room DB       │
│    - Reduces mailbox size on provider servers    │
│    - Prevents provider storage quota exhaustion  │
│                                                  │
│ 3. Configurable: user can set 1-30 day retention │
└─────────────────────────────────────────────────┘
```

### Preventing Spam Filter Triggers

To avoid Yandex/Mail.ru marking CheburMail emails as spam:

1. **Consistent From/To pattern** -- real mailboxes communicating, not bulk
2. **Custom MIME type** -- `application/x-cheburmail` looks like app traffic, not phishing
3. **Subject is structured but not suspicious** -- `CM/1/...` is a short, consistent pattern
4. **Message size is small** -- sub-1KB emails do not trigger attachment scanners
5. **Rate limiting in-app** -- throttle sends to ~1 per second, max 50/hour

---

## 8. Offline Handling

### Outbox Queue Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        OutboxQueue                           │
│                     (Room table: outbox)                     │
│                                                              │
│  ┌──────┬──────────┬───────────┬────────┬──────────────────┐│
│  │  id  │ chat_id  │ plaintext │ status │ retry_count      ││
│  ├──────┼──────────┼───────────┼────────┼──────────────────┤│
│  │  1   │ abc123   │ "hello"   │ QUEUED │ 0                ││
│  │  2   │ abc123   │ "world"   │ FAILED │ 3                ││
│  │  3   │ def456   │ "test"    │ SENT   │ 0                ││
│  └──────┴──────────┴───────────┴────────┴──────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### Send Flow with Offline Support

```
User taps Send
     │
     ├──> Insert into messages table (status = SENDING)
     ├──> Insert into outbox table (status = QUEUED)
     │
     ├── Network available?
     │   ├── YES: encrypt + SMTP send immediately
     │   │        on success: outbox status = SENT, message status = SENT
     │   │        on failure: outbox status = FAILED, retry_count++
     │   │
     │   └── NO: remain QUEUED
     │            UI shows clock icon (pending)
     │
     └── WorkManager "OutboxDrainWorker" (runs on connectivity change):
         picks up QUEUED and FAILED (retry_count < 5) entries
         processes them sequentially
         exponential backoff: 30s, 1m, 2m, 4m, 8m
```

### Connectivity Monitoring

```kotlin
// Registered in Application.onCreate()
connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // Trigger one-time WorkManager task to drain outbox
        WorkManager.getInstance(context).enqueueUniqueWork(
            "outbox-drain",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxDrainWorker>().build()
        )
        // Reconnect IDLE if service is running
        ImapIdleService.reconnect()
    }
})
```

### Receive Sync on Reconnect

When connectivity returns:
1. OutboxDrainWorker sends queued messages
2. SyncWorker triggers an immediate IMAP fetch (one-time, in addition to periodic)
3. IMAP IDLE reconnects if ForegroundService is active

### Conflict Resolution

No conflicts possible by design:
- Messages are append-only (no edits in v1)
- UUIDs prevent duplicates
- Timestamps are sender-authoritative (no server clock)

---

## 9. Build Order

### Dependency Graph (What Blocks What)

```
Phase 1: Foundation (no dependencies)
─────────────────────────────────────
  [core/model]  [crypto]  [storage/db schema]

Phase 2: Wiring (depends on Phase 1)
─────────────────────────────────────
  [transport/smtp]     depends on: crypto, core
  [transport/imap]     depends on: crypto, core
  [storage/KeyStore]   depends on: crypto

Phase 3: Integration (depends on Phase 2)
─────────────────────────────────────
  [transport/SyncWorker]    depends on: imap, storage
  [transport/OutboxQueue]   depends on: smtp, storage

Phase 4: UI (depends on Phase 2-3)
─────────────────────────────────────
  [ui/setup]       depends on: storage, crypto
  [ui/qr]          depends on: crypto
  [ui/chatlist]    depends on: storage
  [ui/chat]        depends on: storage, outbox

Phase 5: Polish (depends on Phase 4)
─────────────────────────────────────
  [IMAP IDLE service]
  [Group chat]
  [Notifications]
  [Settings]
```

### Recommended Build Sequence

```
Sprint 1 (Week 1-2): Crypto + Storage Foundation
─────────────────────────────────────────────────
  1. core/model/          Define Message, Contact, Chat data classes
  2. crypto/KeyManager    X25519 keypair generation + EncryptedSharedPrefs storage
  3. crypto/Encryptor     crypto_box_easy encrypt/decrypt (unit-testable, pure Kotlin)
  4. storage/db/          Room database, DAOs, entities
  5. storage/KeyStore     EncryptedSharedPreferences wrapper
  DELIVERABLE: Unit tests for encrypt -> decrypt round-trip

Sprint 2 (Week 3-4): Email Transport
─────────────────────────────────────
  6. transport/SmtpClient     Send raw email via JavaMail
  7. transport/ImapClient     Fetch emails via JavaMail
  8. transport/EmailFormatter  Message -> MimeMessage
  9. transport/EmailParser     MimeMessage -> Message
  10. Integration test: send encrypted email from test account A,
      fetch and decrypt on test account B
  DELIVERABLE: End-to-end message send/receive via real Yandex accounts

Sprint 3 (Week 5-6): Core UI + Onboarding
──────────────────────────────────────────
  11. ui/setup/         Email credentials input, IMAP/SMTP validation
  12. ui/setup/         Key generation on first launch
  13. ui/qr/            QR code display (ZXing) + CameraX scanner
  14. ui/contacts/      Contact list (from Room)
  15. ui/chatlist/      Chat list screen
  16. ui/chat/          Chat screen (send + display messages)
  DELIVERABLE: Working 1-on-1 chat between two physical devices

Sprint 4 (Week 7-8): Background Sync + Offline
───────────────────────────────────────────────
  17. transport/SyncWorker      WorkManager periodic IMAP fetch
  18. storage/OutboxQueue       Offline message queuing
  19. transport/OutboxDrainWorker  Send queued messages on reconnect
  20. Notifications              Android notifications for new messages
  21. Email folder management    Create CheburMail folder, cleanup
  DELIVERABLE: Messages arrive in background, survive offline periods

Sprint 5 (Week 9-10): Group Chat + Polish
──────────────────────────────────────────
  22. Group creation flow       group-invite message type
  23. Group send (fan-out)      N-1 encrypted emails per message
  24. Group UI                  Member list, add/remove
  25. IMAP IDLE service         Real-time message delivery
  26. Settings screen           Retention, sync interval, about
  DELIVERABLE: Full MVP with 1-on-1 and group chats
```

### Critical Path

The longest dependency chain determines minimum time to first working prototype:

```
KeyManager -> Encryptor -> EmailFormatter -> SmtpClient -> SendUseCase -> ChatScreen
                                                                              |
ImapClient -> EmailParser -> Decryptor -> SyncWorker -> Room -> ChatScreen ---+

Minimum viable demo: ~4 weeks (Sprints 1-2 + minimal UI)
Full MVP: ~10 weeks (all 5 sprints)
```

### Testing Strategy Per Phase

| Phase | Test Type | What to Test |
|-------|-----------|-------------|
| 1 | Unit | Encrypt/decrypt round-trip, key serialization, Room DAO queries |
| 2 | Integration | Real SMTP send to Yandex test account, real IMAP fetch, email format parsing |
| 3 | UI (manual) | Onboarding flow, QR scan between two devices |
| 4 | Integration | WorkManager fires correctly, outbox drains, messages survive airplane mode |
| 5 | E2E (manual) | 3-person group chat, member add/remove, IDLE latency measurement |

---

## Appendix A: Technology Versions

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0+ | Language |
| Jetpack Compose BOM | 2025.01+ | UI toolkit |
| Lazysodium-android | 5.1.0+ | Libsodium bindings |
| Room | 2.6+ | SQLite ORM |
| WorkManager | 2.9+ | Background scheduling |
| JavaMail (jakarta.mail) | 2.1+ | IMAP/SMTP |
| ZXing | 3.5+ | QR code generation |
| CameraX | 1.4+ | QR code scanning |
| Hilt | 2.51+ | Dependency injection |
| EncryptedSharedPreferences | 1.1+ | Secure key storage |

## Appendix B: Email Provider Limits Summary

| Limit | Yandex | Mail.ru (estimated) |
|-------|--------|---------------------|
| IMAP server | imap.yandex.ru:993 (SSL) | imap.mail.ru:993 (SSL) |
| SMTP server | smtp.yandex.ru:465 (SSL) | smtp.mail.ru:465 (SSL) |
| Daily send limit | ~500 emails | ~300-500 emails |
| Max message size | 30 MB | 25 MB |
| IMAP IDLE | Supported | Supported |
| Custom folders | Supported | Supported |
| App passwords | Required (2FA accounts) | Required (2FA accounts) |

## Appendix C: Threat Model Summary

| Threat | Mitigation |
|--------|-----------|
| Email provider reads messages | E2E encryption -- provider sees only ciphertext |
| Email provider sees who talks to whom | **Not mitigated in v1.** From/To metadata visible. V2: relay accounts |
| MITM on key exchange | QR code at physical meeting -- no network involved |
| Device compromise | Private key in EncryptedSharedPreferences (Android Keystore backed) |
| Message replay | UUID deduplication in Room |
| Message tampering | crypto_box Poly1305 MAC authentication |
| Forward secrecy compromise | Each message uses unique nonce; compromising one nonce does not reveal others. Full forward secrecy (ratcheting) deferred to v2 |
| Rate limiting / account suspension | In-app throttling, user warning at 80% of daily limit |
