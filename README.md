# CheburMail

**End-to-end encrypted messenger for Android that runs on top of regular email.**

No custom servers. No phone number required. Your messages travel through standard IMAP/SMTP (Yandex Mail, Mail.ru, Rambler), but the email provider can only see metadata — never the content.

[README на русском](README.ru.md)

## Status

- **Stable**: `v0.3.8` — published on [GitHub Releases](https://github.com/shizzka/cheburmail/releases/latest)
- **Beta**: `v0.4.6` — active development on the [`dev`](https://github.com/shizzka/cheburmail/tree/dev) branch, available as debug pre-releases via [@my_fabrica_bot](https://t.me/my_fabrica_bot)

The feature list below describes the `v0.4.6` beta. Stable `v0.3.8` is missing: Rambler provider, multi-email auto-fallback, group chats with admin approval, SQLCipher database encryption, manual key exchange, network diagnostics screen, app re-lock on background, screenshot protection. Those will land in stable once beta testing concludes.

## Why CheburMail and not Signal / Telegram / WhatsApp?

| | CheburMail | Signal | Telegram | WhatsApp |
|---|---|---|---|---|
| **Works in Russia without VPN** | Yes — uses Yandex/Mail.ru/Rambler (whitelisted domestic servers) | Blocked since 2024, requires VPN | Partially blocked, unstable | Works, but Meta servers can be blocked at any time |
| **Survives "sovereign internet" (Cheburnet)** | Yes — email is domestic infrastructure | No — depends on foreign servers | No — servers abroad | No — servers abroad |
| **Survives targeted SMTP blocking** | Yes — auto-fallback between your accounts (e.g. Mail.ru SMTP blocked → app silently sends through Yandex) | N/A | N/A | N/A |
| **Requires phone number** | No — just an email account | Yes | Yes | Yes |
| **Custom servers to maintain** | None — piggybacks on email | Signal Foundation servers | Telegram servers | Meta servers |
| **E2E encryption** | Yes (X25519 + XSalsa20-Poly1305) | Yes (Signal Protocol) | Only "secret chats" | Yes (Signal Protocol) |
| **Open source** | Yes | Yes | Client only | No |
| **Can be shut down by blocking a domain** | No — works over any Russian email provider | Yes | Yes | Yes |

**The core idea**: Russian email providers (Yandex, Mail.ru, Rambler) are part of the country's approved communications infrastructure. They will be the last services to be blocked — if ever. CheburMail uses them as a transport layer while encrypting everything client-side. The provider sees who talks to whom, but never the content.

**"Cheburnet"** is the colloquial term for Russia's sovereign internet — a scenario where foreign services are cut off. In that world, Signal is dead, Telegram is unreliable, but **email still works**. CheburMail is built for that scenario.

## How It Works

CheburMail turns your email account(s) into a secure messaging channel:

1. **You sign in** with your existing Yandex / Mail.ru / Rambler email (app password)
2. **(Optional) Add a second account** — gives you auto-fallback if one provider's SMTP gets blocked
3. **Exchange keys** by scanning a QR code in person — your QR carries all your aliases at once
4. **Messages are encrypted** on your device before sending — the email provider sees only gibberish
5. **Delivered via IMAP/SMTP** — no middleman servers, no accounts to create

```
You (encrypt) → SMTP → Email Provider → IMAP → Recipient (decrypt)
                        ↑ sees only metadata
```

If the SMTP for one of your accounts gets blocked at the operator level (TSPU does this targetedly to specific services), CheburMail detects the failure and **silently retries through your other account** without you noticing. The recipient recognizes you by your public key regardless of which alias you sent from.

## Security

| Layer | Technology |
|-------|-----------|
| Key exchange | X25519 (Curve25519 Diffie-Hellman) |
| Encryption | XSalsa20-Poly1305 (authenticated) |
| Key storage | Android Keystore + Tink AEAD |
| Local database | SQLCipher (AES-256), passphrase in encrypted DataStore |
| Implementation | libsodium via lazysodium-android |
| Media | Per-file encryption with separate keys |
| App lock | PBKDF2WithHmacSHA256 (200k iterations) + per-install salt + throttling lockout |

- **Zero-knowledge**: the email provider cannot read your messages
- **No custom servers**: nothing to hack, shut down, or subpoena
- **Encrypted at rest**: contacts, chats, messages, fingerprints — all stored in a SQLCipher database; a stolen or rooted device cannot read the DB without the app-bound passphrase
- **Unique nonce per message**
- **Verification**: fingerprint comparison via QR code or manual 60-digit safety number
- **Authorized control messages**: remote DELETE only honored from the message author in the same chat; group MEMBER_REMOVED only from the verified admin (alias-aware)
- **App-lock re-lock on background**: when the app goes to background, PIN is required again on resume
- **Screenshot protection** in chat screens (on by default)
- **No subject-only delivery receipts** — would be forgeable, deliberately not implemented

## Features

### Working
- **1-on-1 chats** with E2E encryption
- **Group chats** with E2E encryption (fan-out: each member gets their own ciphertext, no shared key)
- **Admin-approved group joins** — non-admin adds go through an approval queue, admin confirms by fingerprint before the invite is sent
- **Multi-email identity** — one cryptographic identity, multiple email addresses (Yandex + Mail.ru + Rambler). The recipient recognizes you by your public key, not by email
- **Auto-fallback when SMTP is blocked** — if your primary SMTP gets cut by TSPU/operator, the app silently retries through your other account; user sees a snackbar `"Sent via yandex (mail.ru blocked)"`
- **Per-account SMTP health tracking** — failed accounts are quarantined with exponential backoff (5 min → 1 h cap), state survives app restarts
- **Network diagnostics screen** — DNS + TCP probes for SMTP/IMAP/HTTPS to all providers, gives concrete diagnoses like "TSPU is blocking Mail.ru SMTP — use Yandex or VPN"
- **Media messages**: images, files, voice recordings — all encrypted
- **QR code key exchange** — scan in person, no server needed; QR carries all your aliases at once
- **Manual key exchange without QR** — paste a short code when scanning isn't possible
- **Key fingerprint verification** — 60-digit safety number (QR or manual)
- **Automatic re-keyex after reinstall** — the app detects a peer that lost its keys and re-exchanges, with anti-spam rate limiting (`ReactiveKeyexGate`)
- **Reply, quote, and authorized delete** messages (delete is author-only, also removes from IMAP)
- **Rename chats**
- **Background sync** via WorkManager + IMAP IDLE
- **Stuck-fetch detection** — if an IMAP fetch hangs for >90 sec, its thread is interrupted to free the lock
- **PIN / biometric lock** with PBKDF2-derived hash, salt, and brute-force throttling (5 fails → 5 min lockout, exponential backoff)
- **Re-lock on background** — leaving the app for any reason requires re-entering the PIN on return
- **Screenshot protection** in chat screens (on by default)
- **What's new dialog** on first launch after upgrade
- **Encrypted local database** (SQLCipher) with transparent migration from older plaintext installs
- **IMAP auto-cleanup** — automatically delete processed emails older than 7 days
- **Auto-update checker** — get notified when a new version is available (separate debug / release channels)
- **No tracking, no analytics, no ads**

### In Development
- **UI-driven re-invite** for group members who reinstalled the app
- **Tombstones for deleted groups** so archived IMAP mail can't resurrect a deleted chat

### Intentionally out of scope
- **Delivery / read receipts** and **disappearing messages** — both require extra control-message traffic over IMAP/SMTP for every sent message, which is the exact opposite of what CheburMail is optimizing for (low provider-side footprint, small metadata surface). Won't be implemented.

## Supported Providers

| Provider | IMAP | SMTP | Notes |
|----------|------|------|-------|
| Yandex Mail | `imap.yandex.ru:993` | `smtp.yandex.ru:465` | App password required |
| Mail.ru | `imap.mail.ru:993` | `smtp.mail.ru:465` | App password required; covers `@mail.ru`, `@bk.ru`, `@list.ru`, `@inbox.ru` (same servers) |
| Rambler | `imap.rambler.ru:993` | `smtp.rambler.ru:465` | Account password (Rambler doesn't have separate app passwords); independent infrastructure from Yandex/Mail.ru |

> You need to generate an **app password** in Yandex/Mail.ru security settings. CheburMail never sees your real password. For Rambler, the regular account password is used (enable IMAP/SMTP access in security settings first).

**Tip**: adding two providers (e.g. Yandex + Mail.ru) is highly recommended — gives you auto-fallback if one of them gets targeted by SMTP blocking.

## Building from Source

**Requirements**: JDK 17, Android SDK (API 35)

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

git clone https://github.com/shizzka/cheburmail.git
cd cheburmail
./gradlew assembleDebug --no-daemon
# APK → app/build/outputs/apk/debug/app-debug.apk
```

For release builds, set `CHEBURMAIL_STORE_PASSWORD` / `CHEBURMAIL_KEY_PASSWORD` env vars or create `keystore.properties` in the repo root (gitignored) with `storePassword` / `keyPassword` keys.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Crypto**: lazysodium-android (libsodium) + Google Tink
- **Transport**: JavaMail (IMAP/SMTP)
- **Storage**: Room over SQLCipher + Encrypted DataStore
- **Sync**: WorkManager + IMAP IDLE foreground service
- **QR**: ZXing (generation) + Google Code Scanner (scanning)
- **Min SDK**: 26 (Android 8.0)

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                       UI Layer                        │
│             Jetpack Compose + ViewModels              │
├──────────┬──────────┬──────────┬──────────┬──────────┤
│  crypto/ │transport/│ account/ │  storage/│  sync/   │
│          │          │          │          │          │
│ X25519   │ SMTP     │ Multi-   │ Room +   │ WorkMgr  │
│ XSalsa20 │ IMAP     │ email +  │ SQLCipher│ IMAP     │
│ libsodium│ JavaMail │ health   │ Tink AEAD│ IDLE FG  │
│          │          │ tracker  │          │          │
└──────────┴──────────┴──────────┴──────────┴──────────┘
              ↕                          ↕
       Email Providers              Local Storage
   (Yandex/Mail.ru/Rambler)       (encrypted keys + db)
```

## Email Format

Messages use a custom format invisible to regular email clients:

- **Subject**: `CM/1/<chatId>/<messageUuid>`
- **Body**: `Base64(nonce || ciphertext)`
- **Content-Type**: `application/x-cheburmail`

## FAQ

**Why email?**
Email is federated, battle-tested infrastructure that already exists everywhere. No servers to maintain, no infrastructure costs, no single point of failure.

**Can my email provider read my messages?**
No. Messages are encrypted on your device before they ever reach the email server. The provider sees the sender, recipient, and timestamps — but the message content is indistinguishable from random data.

**What happens if I lose my device?**
You lose your messages and keys. This is by design — there is no backup, no server-side recovery, no way to extract your keys. If someone steals your phone, your contacts just need to re-exchange keys with your new device; the local database is encrypted with SQLCipher, so the thief cannot read your chats even with root.

**What if my SMTP gets blocked while I'm sending?**
If you have a second email account configured, the app silently retries through it and shows a snackbar telling you the fallback fired. If only one account is configured, the message goes into a queue and retries with exponential backoff. The Diagnostics screen in Settings → Network gives you a concrete picture of which servers are reachable right now.

**What if someone gets added to a group chat without my approval?**
They can't. Only group admins can add new members. When a non-admin tries to add someone, the admin sees a verification request with the new member's key fingerprint and must explicitly approve before the invite is sent to the group.

**Can I use Gmail / Outlook?**
Not yet, and probably not in Russia anyway — TSPU blocks foreign mail providers' SMTP. Adding Gmail/Outlook would be straightforward technically (PRs welcome), but they're irrelevant for the "Cheburnet" scenario CheburMail is designed for.

## Download

- **Telegram bot**: [@my_fabrica_bot](https://t.me/my_fabrica_bot) — press "Stable" for the verified release or "Debug" for the latest build with new features
- **GitHub Releases**: [latest release](https://github.com/shizzka/cheburmail/releases/latest)

## License

MIT
