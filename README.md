# CheburMail

**End-to-end encrypted messenger for Android that runs on top of regular email.**

No custom servers. No phone number required. Your messages travel through standard IMAP/SMTP (Yandex Mail, Mail.ru), but the email provider can only see metadata — never the content.

[README на русском](README.ru.md)

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
| Implementation | libsodium via lazysodium-android |
| Media | Per-file encryption with separate keys |

- **Zero-knowledge**: the email provider cannot read your messages
- **No custom servers**: nothing to hack, shut down, or subpoena
- **Forward secrecy**: unique nonce per message
- **Verification**: fingerprint comparison via QR code

## Features

- **1-on-1 and group chats** with E2E encryption
- **Media messages**: images, files, voice recordings — all encrypted
- **Reply, quote, and delete** messages (deletes from IMAP too)
- **Delivery receipts** and read status
- **Disappearing messages** with configurable timer
- **Key backup** — export/import your encryption keys
- **Multi-account** — switch between email accounts
- **Background sync** via WorkManager + IMAP IDLE
- **Screenshot protection** in chat screens
- **No tracking, no analytics, no ads**

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
- **Storage**: Room + Encrypted DataStore
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
│ X25519   │ SMTP     │ Room DB   │ WorkManager│
│ XSalsa20 │ IMAP     │ DataStore │ IMAP IDLE  │
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
Use the key backup feature to export your encryption keys. Without your keys, your messages cannot be decrypted — even by you.

**Can I use Gmail / Outlook?**
Not yet. Currently supports Yandex Mail and Mail.ru. Adding more providers is straightforward — PRs welcome.

## Download

Get the latest APK from the distribution bot: [@my_fabrica_bot](https://t.me/my_fabrica_bot) (send `/cheburmail`)

## License

MIT
