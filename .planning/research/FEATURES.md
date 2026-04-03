# CheburMail Feature Research

Analysis of E2E encrypted messenger features in the context of email (IMAP/SMTP) as transport layer. Reference messengers: Signal, WhatsApp, Telegram, Element/Matrix, Briar, Session.

---

## 1. Table Stakes (Must Have)

Features users expect from any messenger. Missing any of these makes the app feel broken.

### 1.1 One-on-One Messaging

| Aspect | Detail |
|--------|--------|
| **What** | Send and receive text messages between two users |
| **Email mapping** | One message = one email. SMTP to send, IMAP IDLE/poll to receive. Encrypted payload in email body (base64). Custom headers (`X-CheburMail-Version`, `X-CM-MsgID`) to distinguish from regular email |
| **Complexity** | Low. Core feature, built first |
| **Dependencies** | Account setup, key exchange, crypto layer |

### 1.2 Account Setup (Email Credentials)

| Aspect | Detail |
|--------|--------|
| **What** | User enters Yandex/Mail.ru email + app password. App validates IMAP/SMTP connectivity |
| **Email mapping** | IMAP LOGIN/AUTHENTICATE to validate credentials. Store in EncryptedSharedPreferences |
| **Complexity** | Low. Standard IMAP/SMTP configuration. Yandex requires app-specific passwords (not OAuth for third-party). Mail.ru similar |
| **Dependencies** | None. First thing user does |
| **Notes** | Must handle: wrong credentials, 2FA-required, app password vs regular password. Show clear error messages for each case |

### 1.3 Key Generation and Exchange

| Aspect | Detail |
|--------|--------|
| **What** | Generate X25519 keypair on first launch. Exchange public keys via QR code at physical meeting |
| **Email mapping** | No email involved. Pure on-device operation + camera for QR scan |
| **Complexity** | Medium. QR generation/scanning is well-documented. Must encode: public key (32 bytes) + email address + display name. Consider: what if user reinstalls? New keypair = must re-exchange with all contacts |
| **Dependencies** | Camera permission, Lazysodium initialization |
| **Notes** | QR payload format matters. Include version byte for future compatibility. Consider also NFC tap as alternative exchange method |

### 1.4 Contact List

| Aspect | Detail |
|--------|--------|
| **What** | List of people you have exchanged keys with. Display name, email, last message preview, unread count |
| **Email mapping** | Purely local (Room DB). Contacts are added only through QR exchange, not from email address book |
| **Complexity** | Low. Standard RecyclerView/LazyColumn with Room queries |
| **Dependencies** | Key exchange (contacts created during QR scan) |

### 1.5 Chat Screen with Message History

| Aspect | Detail |
|--------|--------|
| **What** | Chronological list of messages in a conversation. Sent/received indicators. Scroll to load older messages |
| **Email mapping** | Messages stored locally in Room after decryption. Email is only transport; once decrypted and saved, the email can be deleted from server (IMAP STORE \Deleted + EXPUNGE) |
| **Complexity** | Low-Medium. Standard chat UI. Must handle: message ordering by timestamp (not email arrival order, since SMTP delivery is not ordered) |
| **Dependencies** | Messaging, local storage |

### 1.6 Message Delivery Confirmation

| Aspect | Detail |
|--------|--------|
| **What** | Sender knows message was delivered to recipient's device (not just to email server) |
| **Email mapping** | **Two-phase:** (1) SMTP accepted = "sent to server" (checkmark 1), (2) Recipient sends back a lightweight "delivery receipt" email = "delivered to device" (checkmark 2). Receipt is a small encrypted email with type=`ack` and original message ID |
| **Complexity** | Medium. Receipts double the email count. Must handle: receipt lost, recipient offline for days. Do NOT use SMTP DSN (Delivery Status Notification) -- those are unreliable and leak metadata |
| **Dependencies** | Message ID system, bidirectional messaging |
| **Rate limit impact** | Each message now costs 2 emails (message + ack). With 50 msg/hr limit, effective throughput is 25 messages/hr per direction |

### 1.7 Offline Message Queue

| Aspect | Detail |
|--------|--------|
| **What** | Messages composed while offline are queued and sent when connectivity restores |
| **Email mapping** | Queue in Room DB with status `pending`. Background worker (WorkManager) attempts SMTP send when network available. On success, update status to `sent` |
| **Complexity** | Medium. Android WorkManager with network constraint. Must handle: partial failures (some messages sent, some not), ordering preservation |
| **Dependencies** | Local storage, WorkManager |

### 1.8 Local Message Storage (Encrypted)

| Aspect | Detail |
|--------|--------|
| **What** | All messages stored locally in encrypted database. Survives app restart. Searchable |
| **Email mapping** | Room DB with SQLCipher (or Android Keystore-backed encryption). Messages decrypted from email are stored in plaintext in encrypted DB. Original emails deleted from server after processing |
| **Complexity** | Medium. SQLCipher integration or EncryptedRoom. Must handle: DB migration, backup/restore |
| **Dependencies** | Room, encryption key derived from user passphrase or device keystore |

### 1.9 Notifications

| Aspect | Detail |
|--------|--------|
| **What** | User sees notification when new message arrives while app is backgrounded |
| **Email mapping** | Background IMAP polling via WorkManager (periodic, e.g., every 30-60s). On new email detected: decrypt, save, show notification. Alternative: IMAP IDLE in a foreground service (persistent notification required on Android 8+) |
| **Complexity** | High. Battery-efficient background polling is hard on Android. IMAP IDLE requires persistent connection = foreground service = permanent notification. Trade-off: latency vs battery |
| **Dependencies** | Background service, notification channels, IMAP connection management |
| **Notes** | This is the single hardest "table stakes" feature for email transport. Users expect instant notifications but IMAP gives us polling. IMAP IDLE is better but not all providers support it reliably, and Android kills background connections aggressively |

### 1.10 Message Timestamps

| Aspect | Detail |
|--------|--------|
| **What** | Each message shows when it was sent (sender's local time) |
| **Email mapping** | Include sender timestamp in encrypted payload (not email Date header, which is server-set and leaks metadata). Display in local timezone on receiver side |
| **Complexity** | Low. Part of message payload format |
| **Dependencies** | Message format spec |

### 1.11 Multiple Conversations

| Aspect | Detail |
|--------|--------|
| **What** | User can have separate chats with different contacts simultaneously |
| **Email mapping** | Each contact = separate conversation. Incoming emails routed to correct conversation by sender email address. Custom header `X-CM-ConversationID` for future group chat support |
| **Complexity** | Low. Standard chat list pattern |
| **Dependencies** | Contact list, message routing |

### 1.12 Message Retry on Failure

| Aspect | Detail |
|--------|--------|
| **What** | If SMTP send fails, retry with exponential backoff. Show "failed" indicator with manual retry button |
| **Email mapping** | SMTP can fail for: network error, auth expired, rate limit (HTTP 421/451). Detect rate limit specifically and back off longer |
| **Complexity** | Medium. Exponential backoff + rate limit detection |
| **Dependencies** | Offline queue, error handling |

---

## 2. Differentiators (Competitive Advantage)

Features that make CheburMail stand out from generic encrypted messengers and justify using email as transport.

### 2.1 Works Under Censorship (Core Differentiator)

| Aspect | Detail |
|--------|--------|
| **What** | Functions when Telegram/Signal/WhatsApp are blocked. Uses domestic email infrastructure that cannot be blocked without breaking business email |
| **Email mapping** | This IS the email mapping. The entire architecture is the differentiator |
| **Complexity** | N/A -- this is architectural, not a feature to implement |
| **Dependencies** | All of the above |

### 2.2 Group Chats

| Aspect | Detail |
|--------|--------|
| **What** | Chat with 3+ participants. All members see all messages |
| **Email mapping** | **Sender-side fan-out:** sender encrypts message N times (once per recipient with their public key) and sends N separate emails. Each email has `X-CM-GroupID` header and encrypted group metadata (member list, group name) in payload. Alternatively: use a shared symmetric group key (sender-keys scheme) to encrypt once, then wrap the symmetric key for each recipient |
| **Complexity** | High. N participants = N emails per message. Key management: adding/removing members requires re-keying. Sender-keys (like Signal's) reduces crypto overhead but still N emails per message |
| **Rate limit impact** | Critical. Group of 10 = 10 emails per message. At 50/hr limit, only 5 messages/hr to the group. **Practical limit: groups of 5-10 people max** |
| **Dependencies** | 1-on-1 messaging, key exchange extended for groups, group metadata sync |
| **Notes** | Consider group key rotation on member removal. Member list itself is sensitive metadata -- must be encrypted |

### 2.3 Disappearing Messages

| Aspect | Detail |
|--------|--------|
| **What** | Messages auto-delete after a set time (e.g., 1hr, 24hr, 7 days) |
| **Email mapping** | TTL field in encrypted payload. Receiver's app enforces deletion from local DB after TTL expires. Also: sender should delete sent emails from their Sent folder (IMAP DELETE on sent items) |
| **Complexity** | Medium. Local enforcement only -- cannot guarantee recipient deleted (they could screenshot or modify the app). Include TTL in message payload, schedule Room deletion via WorkManager |
| **Dependencies** | Message format, local storage, WorkManager |
| **Notes** | Honest UX: communicate that this is "best effort" -- recipient's device enforces it, not the protocol. Same limitation as Signal/Telegram |

### 2.4 Message Reactions

| Aspect | Detail |
|--------|--------|
| **What** | React to a message with an emoji without sending a full reply |
| **Email mapping** | Small encrypted email with type=`reaction`, referencing original message ID, containing emoji. Lightweight but still costs one email per reaction per recipient |
| **Complexity** | Medium. Need message ID reference system. UI to display reactions under messages |
| **Rate limit impact** | Each reaction = 1 email. In active group conversation, reactions can consume rate limit quickly. Consider: batch reactions (send accumulated reactions every N seconds) |
| **Dependencies** | Message ID system, delivery confirmation |

### 2.5 Reply-to-Message (Quoting)

| Aspect | Detail |
|--------|--------|
| **What** | Reply to a specific message, showing quoted original |
| **Email mapping** | Include `replyToMessageId` in encrypted payload + snippet of original text (so receiver can display quote even if original was deleted) |
| **Complexity** | Low. Payload field + UI. No extra emails |
| **Dependencies** | Message ID system |

### 2.6 Message Editing

| Aspect | Detail |
|--------|--------|
| **What** | Edit a previously sent message |
| **Email mapping** | New email with type=`edit`, referencing original message ID, containing new text. Receiver replaces local copy |
| **Complexity** | Medium. Must handle: edit arriving before original (race condition), multiple edits, editing while offline |
| **Dependencies** | Message ID system, delivery confirmation |
| **Rate limit impact** | Each edit = 1 email |

### 2.7 Message Deletion (for Both Sides)

| Aspect | Detail |
|--------|--------|
| **What** | Delete a message from both sender's and receiver's devices |
| **Email mapping** | Small email with type=`delete`, referencing message ID. Receiver removes from local DB. Same caveat as disappearing messages: receiver could ignore the request |
| **Complexity** | Low-Medium. Similar to reactions in transport cost |
| **Dependencies** | Message ID system |

### 2.8 Multi-Account Support

| Aspect | Detail |
|--------|--------|
| **What** | Use multiple email accounts (e.g., one Yandex + one Mail.ru) for different contacts or redundancy |
| **Email mapping** | Multiple IMAP/SMTP configurations. Route outgoing messages through the most appropriate account (or the one with remaining rate limit budget) |
| **Complexity** | Medium. Account switching, per-account rate limit tracking |
| **Dependencies** | Account setup, rate limit tracking |
| **Notes** | Differentiator: if one provider starts blocking, switch to another. Also distributes rate limits across accounts (doubling effective throughput) |

### 2.9 Key Verification / Safety Numbers

| Aspect | Detail |
|--------|--------|
| **What** | Verify that the public key you have for a contact hasn't been tampered with. Display fingerprint for manual comparison |
| **Email mapping** | Purely local. Display SHA-256 fingerprint of the combined public keys (like Signal's safety numbers). Users compare in person or over a trusted channel |
| **Complexity** | Low. Crypto is straightforward. UI for displaying and comparing fingerprints |
| **Dependencies** | Key exchange |

### 2.10 Steganographic Mode (Future)

| Aspect | Detail |
|--------|--------|
| **What** | Disguise encrypted payload as normal-looking email content (e.g., embedded in an image or formatted as business correspondence) to avoid detection |
| **Email mapping** | Instead of raw base64 blob, encode payload in: HTML email with hidden data in attributes, image with steganographic payload, or text that looks like normal correspondence with encrypted data in whitespace |
| **Complexity** | Very High. Custom encoding/decoding. Risk of detection by sophisticated analysis. Research project more than feature |
| **Dependencies** | Core messaging working first |
| **Notes** | This addresses the risk of providers flagging "suspicious" encrypted blobs. Worth researching but not for MVP |

### 2.11 Contact Introduction / Key Relay

| Aspect | Detail |
|--------|--------|
| **What** | Alice introduces Bob to Carol by sending Carol's public key to Bob (and vice versa), removing the need for physical meeting |
| **Email mapping** | Encrypted email with type=`introduction`, containing the third party's public key + email + display name. Recipient must explicitly accept. Trust chain: "I trust this key because Alice vouched for it" |
| **Complexity** | Medium. Key trust model needs careful design. TOFU (Trust On First Use) vs explicit verification |
| **Dependencies** | Key exchange, contact management |
| **Notes** | Major UX improvement: physical QR exchange doesn't scale. This enables organic network growth while maintaining a trust chain |

### 2.12 Export/Import Keys (Backup)

| Aspect | Detail |
|--------|--------|
| **What** | Export private key + contacts (encrypted with a passphrase) for device migration or backup |
| **Email mapping** | No email involved. Export to encrypted file (Argon2 KDF + XSalsa20). Can email the backup to yourself as an attachment for storage |
| **Complexity** | Medium. Argon2 is CPU-intensive on mobile (tune parameters). Must be idiot-proof: losing the passphrase = losing all history |
| **Dependencies** | Key management, crypto layer |

---

## 3. Nice-to-Have (Lower Priority)

Features that improve UX but aren't critical for launch.

### 3.1 Link Previews (Local)

| Aspect | Detail |
|--------|--------|
| **What** | Render URL previews inline in chat |
| **Email mapping** | Receiver-side only. App fetches URL metadata locally (no email involved). Privacy consideration: fetching leaks IP to the linked server. Consider opt-in only |
| **Complexity** | Low-Medium. OGP/meta tag parsing. Must handle: timeouts, malicious URLs |
| **Dependencies** | None |

### 3.2 Message Search

| Aspect | Detail |
|--------|--------|
| **What** | Full-text search across all conversations |
| **Email mapping** | Purely local. FTS5 on Room/SQLite. Search over decrypted messages in local DB |
| **Complexity** | Low-Medium. SQLite FTS5 is well-supported |
| **Dependencies** | Local storage |

### 3.3 Chat Pinning and Muting

| Aspect | Detail |
|--------|--------|
| **What** | Pin important chats to top. Mute noisy chats |
| **Email mapping** | Purely local. No email involved |
| **Complexity** | Low. Local preference per conversation |
| **Dependencies** | Contact list |

### 3.4 File/Image Transfer

| Aspect | Detail |
|--------|--------|
| **What** | Send photos, documents, voice messages |
| **Email mapping** | Encrypt file, attach to email as MIME attachment. Email size limit: ~25MB (Yandex), ~20MB (Mail.ru). Must handle: multipart MIME, large attachments, compression |
| **Complexity** | High. MIME handling, progress indication, memory management for large files, thumbnail generation |
| **Rate limit impact** | Large attachments take longer to upload via SMTP, potentially blocking the connection for other messages |
| **Dependencies** | Core messaging. Marked as v2 in PROJECT.md |

### 3.5 Voice Messages

| Aspect | Detail |
|--------|--------|
| **What** | Record and send audio clips |
| **Email mapping** | Record audio (Opus codec for small size), encrypt, send as email attachment. 1 minute of Opus audio ~100KB -- well within email limits |
| **Complexity** | Medium. Audio recording, Opus encoding, playback UI |
| **Dependencies** | File transfer infrastructure |

---

## 4. Anti-Features (Deliberately NOT Building)

Features that would harm privacy, are impossible over email, or go against the project's values.

### 4.1 Read Receipts

| Aspect | Detail |
|--------|--------|
| **Why not** | Leaks behavioral metadata: when someone reads their messages reveals their schedule and habits. In a censorship/surveillance context, this is dangerous. Delivery confirmation (section 1.6) is sufficient -- it confirms the message arrived at the device, not that the human read it |
| **Feasibility** | Technically possible (send email on message read) but actively harmful |
| **Decision** | Do not build. Ever |

### 4.2 Typing Indicators

| Aspect | Detail |
|--------|--------|
| **Why not** | (1) Impossible to do well over email -- minimum latency is seconds, making the indicator meaningless and annoying. (2) Leaks real-time presence metadata. (3) Would consume rate limit budget for zero user value |
| **Feasibility** | Technically possible but useless with email latency |
| **Decision** | Do not build |

### 4.3 Online/Last Seen Status

| Aspect | Detail |
|--------|--------|
| **Why not** | Pure surveillance metadata. Reveals when someone uses their phone. In a hostile environment, this is a risk. Email transport has no concept of "online" anyway -- IMAP connections are transient |
| **Feasibility** | Would require periodic "heartbeat" emails = waste of rate limit, battery, and privacy |
| **Decision** | Do not build |

### 4.4 Server-Side Message Storage

| Aspect | Detail |
|--------|--------|
| **Why not** | Messages exist on email server only in transit. Once received and decrypted, they must be deleted from the server (IMAP STORE \Deleted + EXPUNGE). Leaving encrypted blobs on the server: (1) accumulates evidence of communication patterns, (2) vulnerable to future crypto breaks, (3) wastes mailbox quota |
| **Feasibility** | It's the default if we don't actively delete -- so this is about actively cleaning up |
| **Decision** | Actively delete from server after successful local decryption and storage |

### 4.5 Cloud Backup of Messages

| Aspect | Detail |
|--------|--------|
| **Why not** | Cloud backups (Google Drive, Yandex Disk) are accessible to the provider and potentially to authorities. This completely undermines E2E encryption. WhatsApp had this exact vulnerability for years |
| **Feasibility** | Easy to build, catastrophic for security |
| **Decision** | Do not build. Offer only local encrypted export (section 2.12) |

### 4.6 Contact Discovery via Email/Phone

| Aspect | Detail |
|--------|--------|
| **Why not** | Uploading address book to find who else uses CheburMail requires a central server (we have none) and leaks the user's social graph. Signal does this with SGX enclaves; we have no server infrastructure and no need |
| **Feasibility** | Would require a server. We are serverless by design |
| **Decision** | Do not build. Contacts are added exclusively via QR exchange or introduction (section 2.11) |

### 4.7 User Profiles / Avatars Synced via Email

| Aspect | Detail |
|--------|--------|
| **Why not** | Sending profile updates to all contacts via email wastes rate limit and provides marginal value. Avatars can be set locally per-contact by the contact owner |
| **Feasibility** | Possible but wasteful |
| **Decision** | Local-only avatars. User sets their display name during QR exchange; it's stored locally by the contact |

### 4.8 Message Forwarding to Non-CheburMail Users

| Aspect | Detail |
|--------|--------|
| **Why not** | Forwarding decrypted messages as plain email defeats the entire purpose. If users want to share content, they can screenshot (which we can't prevent anyway), but we should not build a feature that makes it one-tap easy |
| **Feasibility** | Trivial to build |
| **Decision** | Do not build |

### 4.9 Analytics / Telemetry

| Aspect | Detail |
|--------|--------|
| **Why not** | Any phone-home behavior in a censorship-circumvention tool is a liability. No crash reporting, no usage analytics, no update checks that could be intercepted |
| **Feasibility** | Easy to build |
| **Decision** | Zero telemetry. Updates distributed via APK sideload or F-Droid |

### 4.10 Centralized Push Notifications (FCM/HMS)

| Aspect | Detail |
|--------|--------|
| **Why not** | Firebase Cloud Messaging routes through Google servers (blocked or monitored). Huawei Push Kit (HMS) routes through Huawei. Both leak: "this device uses CheburMail" and "a message arrived at time T". We use IMAP polling instead |
| **Feasibility** | Would improve notification latency dramatically |
| **Decision** | Do not build. Accept higher latency in exchange for zero server dependency. IMAP IDLE is the compromise |

---

## 5. Email Transport Constraints Summary

These constraints affect every feature decision:

| Constraint | Impact | Mitigation |
|------------|--------|------------|
| **Rate limit: 50-100 emails/hr** | Limits message throughput. Groups multiply this by N. Reactions/receipts each cost 1 email | Rate limit budget tracker. Batch where possible. Prioritize: messages > receipts > reactions |
| **Latency: 1-30 seconds** | No real-time feel. Typing indicators useless. Conversations feel more like texting than IM | Set user expectations. Show "sending..." state. IMAP IDLE reduces to ~1-5s when supported |
| **SMTP is fire-and-forget** | No guarantee of delivery timing. Emails can be delayed by provider | Delivery receipts (section 1.6). Retry with backoff |
| **Email size limit: 20-25MB** | Limits file transfer. Even with encoding overhead, ~15-18MB usable | Compress before encrypt. Chunk large files across multiple emails (v2) |
| **Metadata visible to provider** | From/To headers expose who talks to whom, when, and how often | Cannot be mitigated without relay infrastructure. Accept this trade-off. Document it honestly for users |
| **IMAP IDLE not guaranteed** | Some providers may not support IDLE, falling back to polling | Detect IMAP IDLE capability. Fall back to periodic polling (30-60s) |
| **Provider may flag encrypted traffic** | Unusual email patterns (frequent, small, base64-only body) could trigger anti-spam | Steganographic mode (section 2.10, future). For now: use standard MIME structure, include innocuous subject line |
| **No server infrastructure** | Cannot implement features requiring central coordination | This is a feature, not a bug. Fully P2P via email. Accept limitations |

---

## 6. Feature Dependency Graph

```
Account Setup
    |
    v
Key Generation ──> QR Key Exchange ──> Contact List
    |                                       |
    v                                       v
SMTP/IMAP Transport ──────────────> 1-on-1 Messaging
    |                                       |
    |                    ┌──────────────────┼──────────────────┐
    |                    v                  v                  v
    |            Delivery Receipts   Message History    Notifications
    |                    |                  |
    |                    v                  v
    |            Message ID System   Local Search
    |                    |
    |         ┌──────────┼──────────┬──────────────┐
    |         v          v          v              v
    |    Reactions   Reply-to    Editing     Deletion
    |
    v
Offline Queue ──> Retry Logic ──> Rate Limit Tracker
                                       |
                                       v
                                 Group Chats
                                       |
                                       v
                               Multi-Account (throughput)
```

---

## 7. MVP Feature Set (Recommended)

Based on this analysis, the minimum set to ship a usable product:

**Must ship (Phase 1):**
1. Account setup with credential validation
2. Key generation + QR exchange
3. Contact list
4. 1-on-1 encrypted messaging (send + receive)
5. Local encrypted message history
6. Delivery confirmation (single checkmark for SMTP accepted)
7. Offline message queue with retry
8. Basic notifications (foreground service with IMAP IDLE, fallback to polling)
9. Server-side cleanup (delete emails after decryption)

**Ship soon after (Phase 2):**
1. Full delivery receipts (device-level, double checkmark)
2. Reply-to-message
3. Disappearing messages
4. Key verification / safety numbers
5. Contact introduction (key relay)
6. Message search
7. Key export/import for device migration

**Later (Phase 3):**
1. Group chats
2. Reactions
3. Message editing/deletion
4. File/image transfer
5. Multi-account support
6. Voice messages

---

*Research date: 2026-04-03*
*Context: CheburMail -- Android messenger using Yandex/Mail.ru email as transport with libsodium E2E encryption*
