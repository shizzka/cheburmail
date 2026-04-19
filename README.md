# CheburMail

**End-to-end encrypted messenger for Android that runs on top of regular email.**

No custom servers. No phone number required. Your messages travel through standard IMAP/SMTP (Yandex Mail, Mail.ru), but the email provider can only see metadata — never the content.

[README на русском](README.ru.md)

## Why CheburMail and not Signal / Telegram / WhatsApp?

| | CheburMail | Signal | Telegram | WhatsApp |
|---|---|---|---|---|
| **Works in Russia without VPN** | Yes — uses Yandex/Mail.ru (whitelisted domestic servers) | Blocked since 2024, requires VPN | Partially blocked, unstable | Works, but Meta servers can be blocked at any time |
| **Survives "sovereign internet" (Cheburnet)** | Yes — email is domestic infrastructure | No — depends on foreign servers | No — servers abroad | No — servers abroad |
| **Requires phone number** | No — just an email account | Yes | Yes | Yes |
| **Custom servers to maintain** | None — piggybacks on email | Signal Foundation servers | Telegram servers | Meta servers |
| **E2E encryption** | Yes (X25519 + XSalsa20-Poly1305) | Yes (Signal Protocol) | Only "secret chats" | Yes (Signal Protocol) |
| **Open source** | Yes | Yes | Client only | No |
| **Can be shut down by blocking a domain** | No — works over any Russian email provider | Yes | Yes | Yes |

**The core idea**: Russian email providers (Yandex, Mail.ru) are part of the country's approved communications infrastructure. They will be the last services to be blocked — if ever. CheburMail uses them as a transport layer while encrypting everything client-side. The provider sees who talks to whom, but never the content.

**"Cheburnet"** is the colloquial term for Russia's sovereign internet — a scenario where foreign services are cut off. In that world, Signal is dead, Telegram is unreliable, but **email still works**. CheburMail is built for that scenario.

## How It Works

CheburMail turns your email account into a secure messaging channel:

1. **You sign in** with your existing Yandex or Mail.ru email (app password)
2. **Exchange keys** by scanning a QR code in person
3. **Messages are encrypted** on your device before sending — the email provider sees only gibberish
4. **Delivered via IMAP/SMTP** — no middleman servers, no accounts to create

```
You (encrypt) → SMTP → Email Provider → IMAP → Recipient (decrypt)
                        ↑ sees only metadata
```

## Security

| Layer | Technology |
|-------|-----------|
| Key exchange | X25519 (Curve25519 Diffie-Hellman) |
| Encryption | XSalsa20-Poly1305 (authenticated) |
| Key storage | Android Keystore + Tink AEAD |
| Local database | SQLCipher (AES-256), passphrase in encrypted DataStore |
| Implementation | libsodium via lazysodium-android |
| Media | Per-file encryption with separate keys |

- **Zero-knowledge**: the email provider cannot read your messages
- **No custom servers**: nothing to hack, shut down, or subpoena
- **Encrypted at rest**: contacts, chats, messages, fingerprints — all stored in a SQLCipher database; a stolen or rooted device cannot read the DB without the app-bound passphrase
- **Unique nonce per message**
- **Verification**: fingerprint comparison via QR code or manual 60-digit safety number

## Features

### Working
- **1-on-1 chats** with E2E encryption
- **Group chats** with E2E encryption (fan-out: each member gets their own ciphertext, no shared key)
- **Admin-approved group joins** — non-admin adds go through an approval queue, admin confirms by fingerprint before the invite is sent
- **Media messages**: images, files, voice recordings — all encrypted
- **QR code key exchange** — scan in person, no server needed
- **Manual key exchange without QR** — paste a short code when scanning isn't possible
- **Key fingerprint verification** — 60-digit safety number (QR or manual)
- **Automatic re-keyex after reinstall** — the app detects a peer that lost its keys and re-exchanges, with anti-spam rate limiting (`ReactiveKeyexGate`)
- **Reply, quote, and delete** messages (deletes from IMAP too)
- **Rename chats**
- **Background sync** via WorkManager + IMAP IDLE
- **Screenshot protection** in chat screens
- **Encrypted local database** (SQLCipher) with transparent migration from older plaintext installs
- **No tracking, no analytics, no ads**

- **PIN / biometric lock** — protect the app with a PIN code or fingerprint
- **IMAP auto-cleanup** — automatically delete processed emails older than 7 days
- **Auto-update checker** — get notified when a new version is available (separate debug / release channels)

### In Development
- **Delivery receipts** and read status
- **Disappearing messages** with configurable timer
- **Multi-account** — switch between email accounts
- **UI-driven re-invite** for group members who reinstalled the app
- **Tombstones for deleted groups** so archived IMAP mail can't resurrect a deleted chat

## Supported Providers

| Provider | IMAP | SMTP |
|----------|------|------|
| Yandex Mail | `imap.yandex.ru:993` | `smtp.yandex.ru:465` |
| Mail.ru | `imap.mail.ru:993` | `smtp.mail.ru:465` |

> You need to generate an **app password** in your email provider's security settings. CheburMail never sees your real password.

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

## Tech Stack

- **Language**: Kotlin 2.3
- **UI**: Jetpack Compose + Material 3
- **Crypto**: lazysodium-android (libsodium) + Google Tink
- **Transport**: JavaMail (IMAP/SMTP)
- **Storage**: Room over SQLCipher + Encrypted DataStore
- **Sync**: WorkManager + IMAP IDLE foreground service
- **QR**: ZXing (generation) + Google Code Scanner (scanning)
- **Min SDK**: 26 (Android 8.0)

## Architecture

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│        Jetpack Compose + ViewModels          │
├──────────┬──────────┬───────────┬────────────┤
│  crypto/ │transport/│  storage/ │   sync/    │
│          │          │           │            │
│ X25519   │ SMTP     │ Room+     │ WorkManager│
│ XSalsa20 │ IMAP     │ SQLCipher │ IMAP IDLE  │
│ libsodium│ JavaMail │ Tink AEAD │            │
└──────────┴──────────┴───────────┴────────────┘
         ↕                    ↕
   Email Provider        Local Storage
  (Yandex/Mail.ru)     (encrypted keys)
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

**What if someone gets added to a group chat without my approval?**
They can't. Only group admins can add new members. When a non-admin tries to add someone, the admin sees a verification request with the new member's key fingerprint and must explicitly approve before the invite is sent to the group.

**Can I use Gmail / Outlook?**
Not yet. Currently supports Yandex Mail and Mail.ru. Adding more providers is straightforward — PRs welcome.

## Download

- **Telegram bot**: [@my_fabrica_bot](https://t.me/my_fabrica_bot) — press "Download APK"
- **GitHub Releases**: [latest release](https://github.com/shizzka/cheburmail/releases/latest)

## License

MIT
