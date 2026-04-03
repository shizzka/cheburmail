# PITFALLS.md -- E2E Encrypted Messenger over Email Transport (Android)

Critical mistakes and common pitfalls when building an encrypted messenger
using Yandex/Mail.ru IMAP/SMTP as transport, X25519 + XSalsa20 crypto,
and Kotlin/Jetpack Compose on Android.

---

## 1. Crypto Pitfalls

### 1.1 Nonce Reuse with XSalsa20-Poly1305

**The disaster:** If the same nonce is ever used twice with the same key,
an attacker can XOR the two ciphertexts to recover the XOR of the two
plaintexts. With Poly1305 authentication, nonce reuse also lets the attacker
forge new valid ciphertexts. This is a **complete cryptographic break**.

**Warning signs:**
- Counter-based nonce generation with persistence bugs (counter resets after
  app reinstall, data wipe, or crash before saving)
- Using a 64-bit counter in a 192-bit nonce field without understanding
  the key-nonce pair uniqueness requirement
- Sharing the same X25519 shared secret across multiple conversations
  without per-conversation key derivation

**Prevention:**
- Use `randombytes_buf()` from libsodium to generate random 24-byte nonces
  for every message. XSalsa20's 192-bit nonce space makes random collision
  probability negligible (~2^-96 after 2^48 messages per key pair).
- Never implement counter-based nonces unless you have bulletproof persistent
  storage -- random nonces are safer for mobile.
- Use `crypto_box_easy()` (NaCl box), which handles nonce correctly internally,
  rather than raw XSalsa20 + separate Poly1305.

**Phase:** Architecture / Day 1. Must be correct before writing any crypto code.

---

### 1.2 Not Using Authenticated Encryption (AE)

**The disaster:** Using XSalsa20 without Poly1305 means ciphertexts are
malleable. An attacker can flip bits in the ciphertext and the corresponding
plaintext bits flip predictably. No error is raised on decryption.

**Warning signs:**
- Using `crypto_stream_xsalsa20_xor()` directly instead of `crypto_box_easy()`
  or `crypto_secretbox_easy()`
- Rolling your own encrypt-then-MAC with separate primitives
- MAC verification happening after partial processing of plaintext

**Prevention:**
- Always use libsodium's combined AEAD constructions: `crypto_box_easy()`
  for public-key encryption, `crypto_secretbox_easy()` for symmetric.
  These are XSalsa20-Poly1305 with authentication built in.
- Never process or display any decrypted data before MAC verification succeeds.
  Libsodium's `_open()` functions return -1 on MAC failure -- check this.

**Phase:** Architecture / Day 1.

---

### 1.3 Android SecureRandom Entropy Failures

**The disaster:** In 2013, Android's `java.security.SecureRandom` had a
catastrophic PRNG seeding bug that caused nonce/key reuse across Bitcoin
wallets, leading to real theft of funds. The PRNG was not properly seeded
from `/dev/urandom` on some Android versions, producing predictable output.

**Warning signs:**
- Using `SecureRandom` directly instead of libsodium's `randombytes_buf()`
- Testing only on emulators (which may have different entropy sources)
- Not calling `randombytes_buf()` through the native libsodium JNI binding
  (e.g., using a pure-Java reimplementation)

**Prevention:**
- Use libsodium's `randombytes_buf()` exclusively for all random generation.
  Libsodium reads from `/dev/urandom` on Linux/Android and handles seeding
  correctly.
- Use the official `lazysodium-android` or `libsodium-jni` bindings, not
  pure-Java ports.
- On first app launch, generate a test random sequence and verify it differs
  from a hardcoded expected value (sanity check).

**Phase:** Foundation / library selection.

---

### 1.4 Key Storage Mistakes

**The disaster:** Private keys stored in SharedPreferences, plain files,
or app databases are trivially extractable on rooted devices. Even on
non-rooted devices, backup extraction or filesystem access via ADB can
expose keys.

**Warning signs:**
- Key material in SharedPreferences (even EncryptedSharedPreferences has
  caveats -- see 3.4)
- Keys stored as Base64 strings in SQLite
- Keys included in Android backup (`allowBackup=true` in manifest)
- Key material lingering in memory after use (no zeroing)

**Prevention:**
- Store private keys in Android Keystore (hardware-backed TEE when available).
  For X25519 keys that Keystore may not natively support, encrypt them with
  an AES key that IS in Keystore.
- Set `android:allowBackup="false"` and `android:fullBackupContent="false"`
  in AndroidManifest.xml.
- Use libsodium's `sodium_memzero()` to wipe key material from memory
  after use.
- Never log key material, even in debug builds. Use ProGuard/R8 to strip
  debug logs in release.

**Phase:** Foundation / Sprint 1.

---

### 1.5 Timing Side Channels in MAC Verification

**The disaster:** Comparing MAC tags with `Arrays.equals()` or `==` leaks
timing information. An attacker sending crafted ciphertexts can determine
the correct MAC byte-by-byte by measuring response times.

**Warning signs:**
- Manual MAC comparison anywhere in the code
- Custom decryption wrappers that don't use libsodium's built-in verification

**Prevention:**
- Use `crypto_box_open_easy()` / `crypto_secretbox_open_easy()` which
  perform constant-time MAC comparison internally.
- Never implement your own MAC verification.
- If you must compare byte arrays for any auth purpose, use
  `sodium_memcmp()`.

**Phase:** Implementation. Enforced by code review.

---

### 1.6 Missing Key Derivation for Shared Secrets

**The disaster:** Using the raw X25519 shared secret directly as an
encryption key. The X25519 output is a 32-byte value that is not uniformly
distributed -- it's a point on the curve. Using it directly as a key for
XSalsa20 is technically safe with `crypto_box` (which does HSalsa20
internally), but dangerous if used with raw `crypto_secretbox`.

**Warning signs:**
- Calling `crypto_scalarmult()` and using the result directly with
  `crypto_secretbox_easy()` without hashing
- No session key rotation (same shared secret used forever)

**Prevention:**
- Use `crypto_box_easy()` which internally derives symmetric keys via
  HSalsa20 from the X25519 output.
- If you need symmetric keys from the shared secret, use
  `crypto_generichash()` (BLAKE2b) to derive them.
- Implement session key rotation: derive per-conversation or per-session
  keys using the shared secret + conversation ID + message counter as
  input to a KDF.

**Phase:** Architecture / Protocol design.

---

## 2. Email Transport Pitfalls

### 2.1 Yandex Rate Limiting

**Known limits:**
- **500 emails per day** per account via SMTP
- **100 SMTP connections per day** (reported by some sources)
- Spam complaints from recipients trigger **24-hour send block** that
  resets if you attempt to send during the block (extending it another 24h)
- No officially documented IMAP concurrent connection limit, but
  empirically similar to other providers (~10-15 simultaneous connections)

**Warning signs:**
- Group chat with 20 members sends 19 emails per message = 26 messages
  exhaust daily limit
- Automated retry logic that hammers SMTP during a rate-limit block,
  extending the block indefinitely
- IMAP IDLE connections accumulating (one per folder being watched)

**Prevention:**
- Track send counts per account per day. Hard-stop at 400 (safety margin).
- Implement exponential backoff on SMTP errors. On 24h block detection,
  stop all sending immediately.
- For group chats, consider using BCC (single email, multiple recipients)
  instead of fan-out when metadata privacy is less critical.
- Pool IMAP connections: one connection for INBOX polling, not one per
  conversation.
- For heavy users, support multiple email accounts to distribute load.

**Phase:** Architecture. Must design around these limits from the start.

---

### 2.2 Mail.ru Rate Limiting

**Known limits:**
- Some sources claim "no limits" on Mail.ru SMTP sending, but this is
  misleading -- Mail.ru **does** enforce anti-spam throttling that kicks
  in with high-volume automated sending.
- Mail.ru is more aggressive about blocking accounts that exhibit bot-like
  behavior (rapid-fire sends, identical content to multiple recipients).
- OAuth2 support is limited; app passwords are the primary auth method.

**Warning signs:**
- Account suddenly stops sending with no clear error
- "Too many connections" IMAP errors during peak usage
- Account locked requiring CAPTCHA or phone verification to unlock

**Prevention:**
- Same daily budget approach as Yandex: track and limit sends.
- Add random jitter (1-5 seconds) between consecutive SMTP sends to
  avoid pattern detection.
- Vary the email body slightly per recipient (include unique encrypted
  payload = naturally unique content).
- Implement health-check: periodically verify the account can still send.

**Phase:** Architecture / Sprint 1.

---

### 2.3 Spam Detection Triggering

**The disaster:** Your messenger emails get classified as spam by Yandex/
Mail.ru, either for the sender (account blocked) or receiver (messages go
to spam folder, never seen by IMAP polling of INBOX).

**Warning signs:**
- Emails with only Base64-encoded bodies (looks like spam/malware)
- Identical Subject lines across all messages
- High send frequency from a new account
- Emails to recipients who never reply (one-way communication pattern)

**Prevention:**
- Use a natural-looking email structure: text/plain part with a brief
  human-readable note ("Encrypted message from CheburMail"), plus the
  encrypted payload as an attachment or in a multipart section.
- Vary Subject lines (include conversation hash prefix or timestamp).
- Have recipients "reply" occasionally (even automated read-receipts
  count as bidirectional communication for spam scoring).
- Warm up new accounts gradually: start with low volume, increase over days.
- Always check the Spam/Junk folder via IMAP, not just INBOX.

**Phase:** Sprint 1-2. Requires iterative testing with real accounts.

---

### 2.4 OAuth2 vs App Passwords

**The disaster:** Yandex and Mail.ru have inconsistent OAuth2 support for
third-party apps. Relying on OAuth2 may break without warning. App passwords
require users to navigate provider-specific settings.

**Warning signs:**
- OAuth2 tokens expiring and refresh failing silently
- Users unable to figure out how to create app passwords
- Provider changes OAuth2 scopes or deprecates endpoints

**Prevention:**
- Support both OAuth2 and app passwords, but design for app passwords as
  the primary path (more reliable).
- Create in-app visual guides with screenshots for creating app passwords
  on Yandex and Mail.ru specifically.
- Implement clear error messages: "Authentication failed. Your app password
  may have expired. Here's how to create a new one."
- Cache credentials in Android Keystore, not in plain text.

**Phase:** Sprint 1 (auth flow).

---

### 2.5 TLS Certificate Pinning Pitfalls

**The disaster:** Pinning Yandex/Mail.ru IMAP/SMTP server certificates
seems like good security, but these providers rotate certificates. Pinned
certs expire -> app stops working -> users locked out.

**Warning signs:**
- Hard-pinned leaf certificates (shortest lifetime)
- No certificate update mechanism in the app
- Users behind corporate proxies with MITM certificates

**Prevention:**
- If pinning, pin the intermediate CA or root CA, not the leaf certificate.
- Better: rely on Android's default certificate validation (system trust
  store) plus certificate transparency checks.
- Do NOT pin for email providers -- the added security is minimal (you
  already trust the provider with metadata) and the breakage risk is high.
- Focus encryption efforts on the E2E layer, not the transport layer.

**Phase:** Sprint 1. Decide early and document the decision.

---

### 2.6 IMAP Connection Management

**The disaster:** IMAP connections are stateful TCP connections. On mobile,
they drop constantly (network switches, doze mode, NAT timeouts). Leaked
connections exhaust server-side connection limits.

**Warning signs:**
- "Too many simultaneous connections" errors
- IMAP IDLE hanging without heartbeat
- JavaMail `Store` objects not properly closed in `finally` blocks
- Memory leaks from unclosed `Folder` objects

**Prevention:**
- Use a single IMAP connection with IDLE for new message notification,
  not one per folder.
- Implement connection pooling with max 2-3 connections per account.
- Set JavaMail session timeout properties:
  ```
  mail.imap.timeout=30000
  mail.imap.connectiontimeout=15000
  mail.imap.writetimeout=15000
  ```
- Always close resources in try-finally. Use Kotlin's `use {}` extension.
- Implement reconnection with exponential backoff (not immediate retry).
- Handle `FolderClosedException` and `StoreClosedException` gracefully.

**Phase:** Sprint 1. Core infrastructure.

---

## 3. Android Platform Pitfalls

### 3.1 Doze Mode Kills Background Email Polling

**The disaster:** Android Doze mode (API 23+) suspends all network access
and defers JobScheduler/WorkManager work when the device is idle and
unplugged. Your IMAP IDLE connection dies. No new messages until the user
picks up the phone. Users think the app is broken.

**Warning signs:**
- Messages arrive in bursts after the user unlocks the phone
- IMAP IDLE connections timing out every 15-30 minutes on idle devices
- WorkManager periodic tasks running hours late

**Prevention:**
- Use FCM (Firebase Cloud Messaging) high-priority push as the primary
  notification mechanism. FCM high-priority messages bypass Doze.
- Architecture: lightweight notification server that monitors IMAP for all
  users and sends FCM pushes. This moves IMAP polling off the device.
- If no server component: use `setAndAllowWhileIdle()` alarms as a
  fallback, but these are limited to ~1 per 9 minutes in Doze.
- WorkManager with `Constraints.Builder().setRequiredNetworkType(CONNECTED)`
  will defer work until maintenance windows in Doze -- this is NOT
  real-time.
- Inform users about battery optimization exclusion (Settings > Apps >
  Battery > Unrestricted). Provide a one-tap intent to the settings page.

**Phase:** Architecture. The decision between server-assisted push vs
pure-P2P fundamentally shapes the app.

---

### 3.2 WorkManager Is Not Real-Time

**The disaster:** WorkManager's minimum periodic interval is 15 minutes.
Even `OneTimeWorkRequest` with no constraints may be deferred by the OS.
Using WorkManager as your message polling mechanism means 15+ minute
message delivery latency.

**Warning signs:**
- Using `PeriodicWorkRequest` for message checking
- Users complaining about delayed messages
- Inconsistent delivery times across different OEM Android skins

**Prevention:**
- WorkManager is appropriate for: sending outbox messages, syncing message
  history, retrying failed sends.
- WorkManager is NOT appropriate for: real-time message reception.
- For real-time: foreground service with IMAP IDLE (shows persistent
  notification) OR FCM push notifications.
- Foreground service must show a notification (Android 8+). Users may
  find this annoying -- make it informative ("Connected, receiving
  messages").
- On Android 14+, foreground service types must be declared:
  `android:foregroundServiceType="dataSync"` in manifest.

**Phase:** Sprint 1. Polling architecture.

---

### 3.3 OEM Battery Optimization (Xiaomi, Samsung, Huawei)

**The disaster:** Chinese OEMs (Xiaomi MIUI, Huawei EMUI, Oppo ColorOS)
and Samsung OneUI have aggressive battery optimization that kills
background apps beyond stock Android Doze. Your foreground service gets
killed. WorkManager tasks never fire. Users on these devices get zero
messages in the background.

**Warning signs:**
- Bug reports only from Xiaomi/Huawei/Samsung users
- `onDestroy()` never called (process killed, not stopped)
- Foreground service killed despite persistent notification
- App removed from recent apps = process killed (Xiaomi default)

**Prevention:**
- Guide users to disable battery optimization for your app. Different
  path per OEM (dontkillmyapp.com has per-device instructions).
- Use `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
  (but Google may reject apps from Play Store that use this without
  justification).
- Implement "keep-alive" by scheduling exact alarms as backup wakeup.
- Test on real Xiaomi, Samsung, and Huawei devices, not just Pixel/
  emulator. Emulators do not reproduce OEM kill behavior.
- Consider a "connection health" indicator in the UI so users can see
  when background sync is broken.

**Phase:** Sprint 2-3 (after core works on Pixel/stock Android).

---

### 3.4 Android Keystore Quirks Across Manufacturers

**The disaster:** Android Keystore behavior varies significantly across
manufacturers and API levels:
- Some devices claim hardware-backed Keystore but use software fallback
- StrongBox (dedicated secure element) has severe performance penalties:
  AES-GCM on 1MB takes ~3 seconds even on Pixel 8
- Samsung has its own "TIMA" keystore with different behavior
- Keystore keys can be **deleted** when user changes screen lock type
  (documented Android bug, issue #36983155)
- Some Xiaomi devices have broken Keystore implementations that throw
  `KeyStoreException` on key generation

**Warning signs:**
- Crypto operations taking seconds instead of milliseconds (StrongBox)
- Keys mysteriously disappearing after screen lock change
- `InvalidKeyException` or `KeyPermanentlyInvalidatedException` on some
  devices
- Different behavior in testing vs production

**Prevention:**
- Check `KeyInfo.isInsideSecureHardware()` at runtime, don't assume.
- Do NOT require StrongBox -- use it opportunistically with fallback to
  TEE-backed or software Keystore.
- Handle `KeyPermanentlyInvalidatedException`: detect it, inform the user,
  regenerate keys, and trigger re-exchange with contacts.
- Keep an encrypted backup of key material (encrypted with a key derived
  from user's password/PIN) in case Keystore loses keys.
- Set `setUserAuthenticationRequired(false)` for message encryption keys
  (requiring auth per decrypt would make receiving messages impractical).

**Phase:** Sprint 1. Key storage architecture.

---

### 3.5 Memory Leaks with JavaMail IMAP

**The disaster:** JavaMail's IMAP implementation is not designed for
long-lived mobile connections. `IMAPFolder`, `IMAPStore`, and `Message`
objects hold references to each other and to underlying socket streams.
Leaked connections consume memory and file descriptors.

**Warning signs:**
- `OutOfMemoryError` after app running for hours
- File descriptor exhaustion (`Too many open files`)
- ANR (Application Not Responding) when fetching large mailboxes
- `Message` objects holding entire MIME content in memory

**Prevention:**
- Use `FetchProfile` to fetch only headers, not full message bodies.
- Process messages in batches, releasing references between batches.
- Set `mail.imap.fetchsize=16384` (16KB) to limit per-fetch memory.
- Close `Folder` and `Store` objects in `finally`/`use` blocks.
- Run IMAP operations on a dedicated thread with its own classloader
  scope if possible.
- Use `message.getContent()` only when the user opens a conversation,
  not during sync.
- Consider alternatives to JavaMail: `jakarta.mail` (modern fork) or
  raw IMAP protocol handling for tighter control.

**Phase:** Sprint 1-2. Must be correct in initial implementation.

---

## 4. Protocol Pitfalls

### 4.1 No Guaranteed Message Ordering

**The disaster:** Email delivery order is not guaranteed. Message A sent
before Message B may arrive after B, or never arrive at all. IMAP FETCH
returns messages by server UID, not by send time. Users see conversations
with jumbled message order.

**Warning signs:**
- Messages appearing out of order in conversation view
- "Reply" messages appearing before the message they reply to
- Edit/delete commands arriving before the original message

**Prevention:**
- Include a monotonically increasing **logical timestamp** (Lamport clock
  or vector clock) in each message's encrypted payload.
- Sort messages by logical timestamp for display, not by IMAP UID or
  email Date header.
- For each conversation, maintain a local sequence counter. Include it
  in the encrypted envelope.
- Handle gaps: if you receive message #5 but not #4, show #5 but mark
  a gap. Request retransmission of #4 from the sender.
- Never rely on the email `Date:` header for ordering -- it's set by
  the sender's clock, which may be wrong.

**Phase:** Protocol design (before implementation).

---

### 4.2 Duplicate Message Detection

**The disaster:** Email systems may deliver the same message multiple times
(server retries, IMAP sync quirks, user checking mail from multiple
devices). Without deduplication, users see every message 2-3 times.

**Warning signs:**
- Duplicate messages in conversation view
- Double-processing of commands (double key rotation, double payments)
- Inconsistent behavior between "first install" and "restore"

**Prevention:**
- Include a UUID (or random 128-bit ID) in each message's encrypted
  payload. Deduplicate on receive by checking a local seen-IDs database.
- Do NOT rely on email `Message-ID` header alone -- it can be rewritten
  by mail servers, and some providers generate their own.
- Store seen message IDs in a SQLite table with TTL (e.g., 30 days).
  Periodically prune old entries.
- Make all message processing **idempotent** where possible.

**Phase:** Protocol design.

---

### 4.3 Message ID Collisions and Email Threading

**The disaster:** Email clients and servers use `Message-ID`, `In-Reply-To`,
and `References` headers for threading. If your messenger sets these
headers, mail servers or webmail interfaces may group unrelated messages.
If you don't set them, some servers may assign their own threading.

**Warning signs:**
- Yandex webmail showing messenger messages in threaded conversations
  (exposing conversation structure to anyone with mailbox access)
- Messages "disappearing" because they're collapsed into a thread
- IMAP search by `Message-ID` returning unexpected results

**Prevention:**
- Generate unique `Message-ID` headers for every email. Format:
  `<uuid@cheburmail.local>`.
- Do NOT use `In-Reply-To` or `References` headers. Each message should
  appear as a standalone email to the mail server.
- Conversation threading must be handled entirely in the encrypted payload,
  invisible to the email layer.
- Use a custom header like `X-CM-Conv: <hash>` only if you accept that
  this leaks conversation grouping as metadata.

**Phase:** Protocol design.

---

### 4.4 Email Size Limits

**The disaster:** Most email providers limit message size to 25-50 MB.
Encrypted messages with attachments (images, files) plus Base64 encoding
overhead (33%) can hit this limit fast.

**Warning signs:**
- Large file sends failing silently
- Base64 encoding turning 18MB file into 25MB+ email
- Mail server rejecting with "552 Message size exceeds maximum permitted"

**Prevention:**
- Client-side file size check before encryption: max 15MB after encryption
  + Base64 (to stay under 25MB).
- For larger files: chunk into multiple emails with reassembly metadata
  in the encrypted payload.
- Consider out-of-band file transfer for large files (encrypted blob on
  a server, link in the email) but this adds infrastructure and
  attack surface.
- Compress before encrypting (encryption destroys compressibility).

**Phase:** Sprint 2 (file attachment feature).

---

### 4.5 Lack of Delivery Confirmation

**The disaster:** SMTP gives you a "250 OK" which means the server accepted
the email, not that it was delivered to the recipient's mailbox. The email
may be silently dropped by spam filters, bounced later, or stuck in a queue.

**Warning signs:**
- Messages showing "sent" but never received
- Users thinking the app is broken because messages don't arrive
- No feedback loop to detect delivery failures

**Prevention:**
- Implement application-level read receipts: receiver sends an encrypted
  ACK message back. Display "delivered" only after ACK.
- Implement "pending" -> "sent" -> "delivered" -> "read" status progression.
- Set reasonable timeouts: if no ACK within 5 minutes, show a warning.
  If no ACK within 1 hour, show "may not have been delivered".
- Monitor SMTP bounce-back emails (DSN) in the sender's INBOX.

**Phase:** Sprint 2. After basic send/receive works.

---

## 5. Group Chat Pitfalls

### 5.1 Quadratic Message Growth (N x N Problem)

**The disaster:** In fan-out architecture, each message to a group of N
members requires N-1 emails. A group of 20 people where each sends 5
messages = 95 emails. At Yandex's 500/day limit, a moderately active
group exhausts the sender's quota in ~1 hour.

**Warning signs:**
- Daily send limits hit quickly in active groups
- Send queue backing up for group messages
- Users in multiple groups hitting limits even faster

**Prevention:**
- Use BCC fan-out: single email to all recipients (saves sender quota
  but all recipients visible to mail server -- metadata leak).
- Better: encrypt the message body with a **group symmetric key** and
  send ONE email to the group address or via BCC. Each member who has
  the group key can decrypt.
- Rotate group key when members change. Distribute new key encrypted
  individually to each remaining member (N key-distribution emails per
  membership change).
- Limit group size explicitly (e.g., max 20 members) and warn users
  about email quota impact.

**Phase:** Architecture. Group design must account for this from day 1.

---

### 5.2 Member Key Rotation on Membership Change

**The disaster:** When a member is added, they need the group key. When
a member is removed, the group key must be rotated so the removed member
can't decrypt future messages. This key rotation is complex and error-prone.

**Warning signs:**
- Removed members can still read new messages (key not rotated)
- New members can't decrypt messages sent during key distribution lag
- Race condition: message sent with old key while rotation is in progress

**Prevention:**
- On member removal: generate new group key, encrypt it individually
  to each remaining member (N-1 emails), and include a "key epoch"
  number in every message.
- Buffer outgoing messages during key rotation until all members have
  ACKed the new key.
- Accept that during rotation there's a window where some members have
  the old key and some have the new key. Messages must include the
  key epoch so recipients know which key to try.
- On member addition: send current group key encrypted to the new
  member's public key. New member cannot decrypt messages from before
  they joined (by design, not a bug).

**Phase:** Sprint 3+ (group feature).

---

### 5.3 Forward Secrecy Is Practically Impossible

**The disaster:** Email-based transport has no concept of ephemeral sessions.
Once a private key is compromised, all past messages stored on the email
server can be decrypted. Signal Protocol's ratcheting doesn't map cleanly
to email's store-and-forward model.

**Warning signs:**
- Long-lived key pairs used for months/years
- Users not understanding that email servers retain all ciphertext
- No mechanism to "expire" old messages from the server

**Prevention:**
- Be honest in security documentation: this system does NOT provide
  forward secrecy. Email servers retain ciphertext indefinitely.
- Implement periodic key rotation (e.g., monthly) with new X25519 key
  pairs. Old keys are deleted from the device.
- Encourage users to delete old emails from the server (provide an
  in-app "delete server copies" feature that removes old IMAP messages).
- Consider a Double Ratchet adaptation, but acknowledge the limitations:
  email's asynchronous nature means many messages may be encrypted under
  the same ratchet state.

**Phase:** Architecture. Document the security model honestly.

---

### 5.4 Member Consistency in Groups

**The disaster:** Without a central server, each group member has their
own view of who is in the group. Member A adds member D, but member B
doesn't receive the notification. B sends a message encrypted only for
A and C. D never gets it.

**Warning signs:**
- Members disagreeing on group membership
- Messages decryptable by some members but not others
- "Who added this person?" confusion

**Prevention:**
- All membership changes must be signed by the admin(s) and sent to
  all current members.
- Each member maintains a local membership list with a version number.
- Messages include the membership version they were encrypted for.
  If a recipient's version differs, they request the latest membership
  list from the sender.
- Admin-only membership changes simplify consistency (single source
  of truth per change).

**Phase:** Sprint 3+ (group feature design).

---

## 6. Metadata Leaks

### 6.1 Email Headers Expose Communication Graph

**The disaster:** Even with E2E-encrypted message bodies, the email
`From:`, `To:`, `CC:`, `BCC:` headers are visible to:
- Yandex/Mail.ru servers (and their employees, law enforcement requests)
- Any network observer on the SMTP relay path
- Anyone with access to sender's or receiver's mailbox

This reveals **who is talking to whom** and **when**, which is often
more sensitive than message content.

**Warning signs:**
- Using real display names in `From:` headers
- Group emails with all recipients in `To:` field
- Communication patterns visible in mailbox (frequency, timing)

**Prevention:**
- Accept this as an inherent limitation of email transport. Document it
  clearly for users: "CheburMail encrypts message content, but your
  email provider can see who you communicate with and when."
- Minimize exposure: use BCC for groups (hides recipients from each
  other, but mail server still sees all).
- Consider using opaque email addresses (not real names) if users
  create dedicated accounts.
- Do NOT promise "anonymous messaging" -- email transport makes this
  impossible.

**Phase:** Architecture / security documentation. Be honest from day 1.

---

### 6.2 Subject Line Exposure

**The disaster:** Email Subject headers are not encrypted. If you put
conversation names, message previews, or any meaningful content in the
Subject, it's visible to the mail server and any eavesdropper.

**Warning signs:**
- Subject lines like "Re: Secret Project Discussion"
- Including conversation IDs in Subject (reveals conversation structure)
- Different subjects per conversation (reveals number of active
  conversations)

**Prevention:**
- Use a fixed, generic Subject for all messages: "Encrypted Message" or
  similar.
- Better: use an empty Subject or a random/rotating Subject.
- All real subject/conversation metadata goes inside the encrypted body.
- Note: fixed Subject for all messages from the app is itself a
  fingerprint ("this person uses CheburMail"), but this is unavoidable
  without blending into normal email traffic.

**Phase:** Protocol design / Sprint 1.

---

### 6.3 Message Size Analysis

**The disaster:** Encrypted message size correlates with plaintext size.
An observer can distinguish "typing indicator" (tiny) from "photo" (large)
from "voice message" (medium) by email size, even without decrypting.

**Warning signs:**
- Different message types producing predictably different sizes
- No padding in the encryption scheme

**Prevention:**
- Pad all messages to fixed size buckets (e.g., 1KB, 4KB, 16KB, 64KB,
  256KB). Libsodium's `sodium_pad()` / `sodium_unpad()` help.
- Accept that this is imperfect: very large messages (files) can't be
  padded to small sizes without enormous waste.
- For text messages, pad to a minimum of 1KB (hides message length for
  typical chat messages).

**Phase:** Sprint 2. Nice-to-have after core functionality works.

---

### 6.4 Timing Analysis

**The disaster:** Message send timestamps reveal activity patterns. If
Alice sends a message at 14:02 and Bob's account sends a message at 14:03,
an observer can infer they're in a conversation, even without seeing
the email content.

**Warning signs:**
- Immediate send on user action (predictable timing)
- Read receipts sent instantly on message open

**Prevention:**
- Add random delay (5-30 seconds) before sending, especially for
  automated messages like read receipts.
- Batch outgoing messages where possible (send accumulated messages
  every few minutes rather than immediately).
- Accept that this is a hard problem and perfect protection against
  timing analysis is impractical on email transport.

**Phase:** Sprint 3+. Polish/hardening phase.

---

## 7. UX Pitfalls

### 7.1 Email Latency vs User Expectations

**The disaster:** Users expect WhatsApp-like instant delivery. Email
delivery takes 1-30 seconds typically, but can take minutes during
server congestion. Combined with IMAP polling intervals, end-to-end
latency can be 30 seconds to several minutes.

**Warning signs:**
- Users complaining "messages are slow"
- Comparisons to Telegram/WhatsApp in app store reviews
- Users abandoning the app after first experience

**Prevention:**
- Set expectations in onboarding: "CheburMail trades speed for privacy.
  Messages typically arrive in 10-30 seconds."
- Show clear message states: "Sending..." -> "Sent to server" -> "Delivered"
  -> "Read".
- Optimize IMAP polling: use IDLE for near-instant notification when
  the connection is active.
- Pre-warm SMTP connections: keep a connection ready so send latency is
  just the SMTP transaction, not connection setup + TLS handshake.

**Phase:** Sprint 1 (UX) and ongoing.

---

### 7.2 IMAP Error Messages Are Cryptic

**The disaster:** JavaMail throws exceptions like
`javax.mail.AuthenticationFailedException`, `MessagingException`,
`FolderClosedException` with technical messages that mean nothing to
users.

**Warning signs:**
- Users seeing "javax.mail.MessagingException: Connection dropped"
- No differentiation between "wrong password", "server down",
  "rate limited", and "network error"
- Users can't self-diagnose problems

**Prevention:**
- Map every JavaMail exception to a human-readable message:
  - `AuthenticationFailedException` -> "Login failed. Check your app
    password in Yandex/Mail.ru settings."
  - `FolderClosedException` -> "Connection lost. Reconnecting..."
  - Connection timeout -> "Can't reach mail server. Check internet."
  - "Too many connections" -> "Mail server is busy. Will retry shortly."
- Include a "Connection Status" indicator in the app (green/yellow/red).
- Log technical details to a debug log the user can share with support.

**Phase:** Sprint 1-2. Error handling.

---

### 7.3 Key Verification Fatigue

**The disaster:** QR code key exchange requires physical proximity or a
video call. Users find this annoying and skip verification, or verify
once and never again (even after key rotation). Unverified conversations
are effectively MITM-vulnerable.

**Warning signs:**
- Most users never scan QR codes
- No indication of verification status in the conversation
- Key rotation silently breaks verification without user notification

**Prevention:**
- Make unverified conversations clearly marked (but not blocked -- users
  will just leave if they can't chat without meeting in person).
- Support multiple verification methods: QR code (in person), numeric
  comparison code (over phone call), key fingerprint comparison (manual).
- On key change, prominently notify: "Bob's security key changed.
  Verify again to ensure your conversation is secure."
- Consider TOFU (Trust On First Use): automatically trust the first key
  seen, warn loudly on key changes. This is SSH's model and is
  pragmatic for most threat models.

**Phase:** Sprint 2 (key exchange UX).

---

### 7.4 Onboarding Complexity

**The disaster:** Users must: (1) create/have a Yandex/Mail.ru account,
(2) enable IMAP in mail settings, (3) create an app password, (4)
enter these credentials in the app, (5) exchange keys with contacts.
This is 10x more friction than downloading Telegram.

**Warning signs:**
- High drop-off rate during onboarding
- Support requests about "IMAP not working" (user didn't enable it)
- Users confused by "app password" concept

**Prevention:**
- In-app step-by-step wizard with screenshots specific to Yandex and
  Mail.ru settings pages.
- Auto-detect provider from email address and show provider-specific
  instructions.
- Test IMAP/SMTP connection immediately after credential entry and
  show clear success/failure with actionable next steps.
- Consider deep-linking to provider settings pages where possible.
- Provide a "test message" feature that sends an email to self to
  verify the setup works.

**Phase:** Sprint 1. Critical for adoption.

---

### 7.5 Multi-Device Support

**The disaster:** User logs into CheburMail on a second device. Private
key is on device 1 only. Device 2 can see encrypted emails in IMAP but
can't decrypt them. User thinks the app is broken.

**Warning signs:**
- Support requests: "I can see messages on my old phone but not my new one"
- No key migration/backup flow
- Lost phone = lost access to all conversations forever

**Prevention:**
- Design a secure key export/import flow: encrypted key backup protected
  by a user-chosen passphrase (key derivation with Argon2id).
- QR code key transfer between own devices (scan from old phone to new).
- Clearly communicate during onboarding: "Your encryption keys are stored
  on this device. Back them up to avoid losing access."
- Consider encrypted cloud key backup (encrypted with passphrase, stored
  as a draft email in the user's own mailbox -- clever reuse of email
  as storage).

**Phase:** Sprint 2-3. Must be designed for in architecture even if
implemented later.

---

## Summary: Priority Matrix

| Priority | Pitfall | Impact if Ignored |
|----------|---------|-------------------|
| **P0 - Day 1** | Nonce reuse (1.1) | Complete crypto break |
| **P0 - Day 1** | Not using AE (1.2) | Ciphertext forgery |
| **P0 - Day 1** | Entropy failures (1.3) | Key/nonce prediction |
| **P0 - Day 1** | Rate limit design (2.1, 2.2) | App unusable for groups |
| **P0 - Day 1** | Metadata honesty (6.1) | False security promises |
| **P1 - Sprint 1** | Key storage (1.4) | Key theft on rooted devices |
| **P1 - Sprint 1** | Doze mode (3.1) | No background messages |
| **P1 - Sprint 1** | IMAP connection mgmt (2.6) | Resource leaks, crashes |
| **P1 - Sprint 1** | Onboarding UX (7.4) | Zero adoption |
| **P1 - Sprint 1** | Message ordering (4.1) | Garbled conversations |
| **P2 - Sprint 2** | Spam detection (2.3) | Account blocks |
| **P2 - Sprint 2** | Duplicate detection (4.2) | Double messages |
| **P2 - Sprint 2** | Key verification UX (7.3) | MITM vulnerability |
| **P2 - Sprint 2** | Error messages (7.2) | User confusion |
| **P3 - Sprint 3+** | Group N*N growth (5.1) | Group chat quota death |
| **P3 - Sprint 3+** | Key rotation (5.2) | Removed members read msgs |
| **P3 - Sprint 3+** | Forward secrecy (5.3) | Past msgs compromised |
| **P3 - Sprint 3+** | OEM battery kills (3.3) | Xiaomi/Huawei broken |
| **P3 - Sprint 3+** | Timing analysis (6.4) | Traffic correlation |
| **P3 - Sprint 3+** | Multi-device (7.5) | Lost phone = lost access |
