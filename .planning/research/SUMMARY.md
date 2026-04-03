# CheburMail Research Summary

Decision document synthesized from detailed research files. See individual files for depth: [STACK.md](STACK.md), [FEATURES.md](FEATURES.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PITFALLS.md](PITFALLS.md).

---

## 1. Executive Summary

CheburMail is an Android E2E encrypted messenger that uses Yandex/Mail.ru email (IMAP/SMTP) as its transport layer. No custom servers, no Firebase, no centralized infrastructure.

**Key insight:** Email is unkillable transport. Blocking Yandex Mail or Mail.ru would break business email for the entire Russian internet. Unlike Telegram or Signal, which depend on specific servers that can be blocked by IP or DPI, CheburMail rides on infrastructure that is too deeply embedded in the economy to shut down.

The trade-off: email is slower, rate-limited, and leaks metadata (who talks to whom). We accept these limitations in exchange for censorship resistance and zero server dependency.

---

## 2. Recommended Stack

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Kotlin | 2.3.20 |
| Build | Gradle + AGP (Kotlin DSL) | 9.3.1 / 9.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2026.03.00 |
| Crypto | Lazysodium (libsodium via JNA) | 5.2.0 |
| Email transport | JavaMail for Android (`com.sun.mail:android-mail`) | 1.6.2 |
| Local DB | Room | 2.8.4 |
| Key storage | DataStore + Tink (backed by Android Keystore) | 1.2.1 / 1.8.0 |
| QR scan | CameraX + ML Kit | 1.6.0 / 17.3.0 |
| QR generate | ZXing core | 3.5.3 |
| Background work | WorkManager | 2.11.1 |
| Target | minSdk 26, targetSdk 35, Java 17 | |

**Explicitly rejected:** BouncyCastle (complex, conflicts), Firebase/FCM (Google dependency), Jakarta Mail 2.x (no Android support), Signal Protocol (overkill for email transport), KMP (premature), any custom backend server.

---

## 3. Table Stakes for MVP

These must work before the first public APK:

1. **Account setup** -- enter Yandex/Mail.ru email + app password, validate IMAP/SMTP
2. **Key generation** -- X25519 keypair on first launch, stored in DataStore+Tink
3. **QR key exchange** -- display and scan public key + email in person
4. **Contact list** -- people you have exchanged keys with
5. **1-on-1 encrypted messaging** -- send via SMTP, receive via IMAP, crypto_box_easy E2E
6. **Local encrypted message history** -- Room DB, messages deleted from server after decryption
7. **Delivery confirmation** -- single checkmark (SMTP accepted)
8. **Offline queue** -- messages queued in Room, drained by WorkManager on connectivity
9. **Notifications** -- foreground service with IMAP IDLE, WorkManager fallback

---

## 4. Architecture Highlights

- **Module split:** `core/` (models), `crypto/` (pure Kotlin, zero Android deps), `transport/` (IMAP/SMTP), `storage/` (Room + DataStore), `ui/` (Compose). Wired via Hilt in `app/`.
- **Message format:** Standard RFC 822 email. Subject: `CM/1/<chat-id>/<msg-uuid>`. Body: Base64-encoded `crypto_box_easy()` ciphertext. Custom MIME type `application/x-cheburmail`.
- **Polling:** Dual-mode. WorkManager every 15 min (reliable, survives Doze) + IMAP IDLE foreground service (sub-5s latency). Both run; IDLE handles real-time, WorkManager is the safety net.
- **Groups (pairwise):** Each group message encrypted N-1 times (once per recipient). Same email cost as sender-keys over email (no multicast). Sender Keys deferred to v2 with relay.
- **Cleanup:** Messages older than 7 days deleted from IMAP server. Configurable 1-30 day retention.
- **Dedicated folder:** `CREATE "CheburMail"` on IMAP. Server-side filter moves `CM/` subjects into it. Fallback: client-side move from INBOX.

---

## 5. Critical Pitfalls (P0)

These must be handled on day 1 or the system is fundamentally broken:

| Pitfall | Consequence | Mitigation |
|---------|------------|------------|
| **Nonce reuse** | Complete crypto break (plaintext XOR recovery) | Always use `randombytes_buf()` for 24-byte random nonces. XSalsa20's 192-bit space makes collision negligible |
| **No authenticated encryption** | Ciphertext forgery, bit-flip attacks | Only use `crypto_box_easy()` / `crypto_secretbox_easy()` -- never raw XSalsa20 |
| **Entropy failure** | Predictable keys/nonces | Use libsodium's `randombytes_buf()` exclusively, never `java.security.SecureRandom` directly |
| **Rate limits unaccounted** | App unusable for groups, accounts blocked | Design around limits from day 1 (see section 6) |
| **Metadata honesty** | False security promises, user trust violation | Document clearly: provider sees From/To/When. Content is encrypted; metadata is not |

---

## 6. Rate Limit Reality

| Provider | Daily limit | Hourly estimate | Source |
|----------|------------|-----------------|--------|
| Yandex | ~500 emails/day | ~50/hr (bursty OK, but 24h block on spam complaints) | Documented |
| Mail.ru | ~300-500/day | Unknown (aggressive anti-bot throttling) | Estimated, undocumented |

**What this means for groups:**

| Group size | Emails per message | Messages before daily limit (500) | Time at 1 msg/min |
|------------|-------------------|----------------------------------|-------------------|
| 2 (1-on-1) | 1 | 500 | 8+ hours |
| 5 | 4 | 125 | ~2 hours |
| 10 | 9 | 55 | ~55 min |
| 20 | 19 | 26 | ~26 min |

Delivery receipts double the email cost (message + ACK). Reactions cost 1 email each.

**Practical limit:** Groups of 5-10 people max for moderate chat pace. UI must show rate limit budget and warn at 80%.

**Mitigations (v2):** message batching (5-10s window), multi-account rotation, BCC fan-out (trades metadata privacy for quota), message compression.

---

## 7. Key Tensions

| Tension | Trade-off |
|---------|-----------|
| **Latency vs battery** | IMAP IDLE gives sub-5s delivery but requires foreground service + persistent notification. WorkManager is battery-friendly but 15-min minimum. Recommendation: both, user-selectable |
| **Group size vs rate limits** | Larger groups burn daily email quota faster. No architectural fix without a relay server |
| **Security vs UX** | QR key exchange requires physical meeting (secure but doesn't scale). Contact introduction (key relay via trusted friend) helps but weakens trust model. TOFU pragmatic for most threats |
| **Metadata vs architecture** | From/To headers expose communication graph. Cannot fix without relay infrastructure, which defeats the serverless premise. Must be honest with users |
| **Spam avoidance vs stealth** | Encrypted blobs look suspicious to spam filters. Natural-looking email structure helps but reduces plausible deniability. Steganographic mode is a future research project |
| **Forward secrecy vs email model** | Double Ratchet doesn't map to store-and-forward email. Static keys with periodic rotation is the pragmatic choice. Document the limitation |
| **Onboarding friction vs security** | User must enable IMAP, create app password, exchange QR codes. 10x more friction than Telegram. In-app wizard with provider-specific screenshots is critical |
| **OEM compatibility vs simplicity** | Xiaomi/Huawei/Samsung kill background services aggressively. Needs per-OEM guidance (dontkillmyapp.com). Test on real devices, not emulators |

---

## 8. Recommended Build Order

Based on dependency analysis and critical path:

**Sprint 1 (Weeks 1-2): Crypto + Storage Foundation**
- Domain models (Message, Contact, Chat)
- X25519 key generation + DataStore/Tink storage
- crypto_box encrypt/decrypt (unit-testable, pure Kotlin)
- Room database, DAOs, entities
- Deliverable: encrypt-decrypt round-trip tests passing

**Sprint 2 (Weeks 3-4): Email Transport**
- SMTP send, IMAP fetch via JavaMail
- EmailFormatter (Message -> MimeMessage) and EmailParser (reverse)
- Dedicated CheburMail folder creation
- Deliverable: send encrypted email from account A, fetch and decrypt on account B

**Sprint 3 (Weeks 5-6): Core UI + Onboarding**
- Email credential input + IMAP/SMTP validation wizard
- QR code display + CameraX scanner
- Contact list, chat list, chat screen
- Deliverable: working 1-on-1 chat between two physical devices

**Sprint 4 (Weeks 7-8): Background Sync + Offline**
- WorkManager periodic IMAP sync
- Outbox queue + drain worker
- IMAP IDLE foreground service
- Android notifications
- Deliverable: messages arrive in background, survive offline periods

**Sprint 5 (Weeks 9-10): Groups + Polish**
- Group creation (group-invite message type)
- Group fan-out (N-1 encrypted sends)
- Rate limit budget tracker + UI warnings
- Settings screen
- Deliverable: full MVP with 1-on-1 and group chats

**Critical path to first demo:** ~4 weeks (Sprints 1-2 + minimal UI). Full MVP: ~10 weeks.

---

## 9. Open Questions

These need testing or validation before committing to a design:

1. **Spam flagging:** Does Yandex/Mail.ru flag frequent small Base64-blob emails as spam? Needs real-account testing with `application/x-cheburmail` MIME type and `CM/` subjects at sustained volume.
2. **IMAP IDLE reliability:** How long do Yandex and Mail.ru actually maintain IDLE connections before dropping them? Documented as "supported" but real-world behavior on mobile networks may differ.
3. **Mail.ru actual rate limits:** No official documentation. Need empirical testing to find the real daily/hourly ceiling and what error codes are returned.
4. **OEM kill behavior:** Does IMAP IDLE foreground service survive on Xiaomi MIUI and Huawei EMUI without manual battery optimization exclusion? Which specific models are worst?
5. **Keystore reliability:** How often does Android Keystore lose keys on screen lock type change across popular devices? Need a fallback strategy (passphrase-encrypted key backup) designed from day 1.
6. **BCC for groups:** Does using BCC (single email, multiple recipients) trigger different spam heuristics than individual sends? Does it actually save sender quota or does the provider count per-recipient?
7. **Account warm-up:** How many days of low-volume sending does a new Yandex/Mail.ru account need before it can reliably send 500/day without triggering anti-abuse?
8. **IMAP folder creation:** Do both providers allow `CREATE "CheburMail"` via IMAP? Some providers restrict custom folder creation from clients.
9. **Subject line trade-off:** `CM/1/<chat-id>/<msg-uuid>` in Subject leaks conversation structure as metadata. Is a fixed subject like "Encrypted Message" better (less metadata but easier to filter as spam)?

---

*Synthesized: 2026-04-03. Source files: STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md*
