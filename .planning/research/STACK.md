# CheburMail — Technology Stack Reference

> E2E encrypted Android messenger using email (Yandex / Mail.ru) as transport layer.
> Researched: April 2026. All versions verified against Maven Central / official docs.

---

## 1. Build System & Platform Targets

| Parameter        | Value                        |
|------------------|------------------------------|
| Language          | Kotlin 2.3.20               |
| Build system      | Gradle 9.3.1, Kotlin DSL    |
| AGP               | 9.1.0                       |
| minSdk            | 26 (Android 8.0)            |
| targetSdk         | 35 (Android 15)             |
| compileSdk        | 35                           |
| Java target       | 17                           |

### Why these values

- **minSdk 26** — Drops ~2% market share but gains: mandatory TLS 1.2 in `SSLSocket`, `java.time.*`, `AutofillService`, better `JobScheduler`. API 23/24 devices are increasingly rare and carry crypto-library compat headaches.
- **targetSdk 35** — Required by Google Play for new apps as of 2026. API 36 requirement not yet enforced.
- **AGP 9.1.0** — Kotlin support built-in (no separate `kotlin-android` plugin needed). Compose compiler bundled.
- **Kotlin 2.3.20** — Latest stable (March 2026). Full Compose compiler compatibility.

### `build.gradle.kts` (project root)

```kotlin
plugins {
    id("com.android.application") version "9.1.0" apply false
}
```

### `build.gradle.kts` (app)

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "ru.cheburmail.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}
```

---

## 2. Core Dependencies (with exact versions)

### 2.1 Jetpack Compose

```kotlin
val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
implementation(composeBom)
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-graphics")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.activity:activity-compose:1.10.1")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
implementation("androidx.navigation:navigation-compose:2.9.0")
debugImplementation("androidx.compose.ui:ui-tooling")
```

### 2.2 Encryption — Lazysodium (libsodium)

```kotlin
implementation("com.goterl:lazysodium-android:5.2.0")
implementation("net.java.dev.jna:jna:5.14.0@aar")
```

Lazysodium wraps libsodium via JNA. Provides:
- `crypto_box_keypair()` — X25519 key generation
- `crypto_box_easy()` / `crypto_box_open_easy()` — X25519 + XSalsa20-Poly1305 authenticated encryption
- `crypto_box_seal()` / `crypto_box_seal_open()` — Sealed boxes (anonymous sender)
- `crypto_secretbox_easy()` — Symmetric XSalsa20-Poly1305 for local storage encryption
- `randombytes_buf()` — Secure random generation

**Do NOT add `net.java.dev.jna:jna` without `@aar`** — the plain JAR includes x86 Linux `.so` files that bloat the APK and conflict with Android ABIs.

### 2.3 Email Transport — JavaMail for Android

```kotlin
implementation("com.sun.mail:android-mail:1.6.2")
implementation("com.sun.mail:android-activation:1.6.2")
```

**Why JavaMail and not Jakarta Mail / Angus Mail:**
- `com.sun.mail:android-mail` is the only mail library with **verified Android compatibility** (min API 19).
- Jakarta Mail 2.x / Eclipse Angus Mail target Java EE / Jakarta EE runtimes and have **no official Android support**. They pull in `jakarta.*` namespace classes and activation frameworks that conflict with Android's classpath.
- Version 1.6.2 is the final release of the `javax.mail` line but it is stable, fully functional, and covers all needed IMAP/SMTP operations.
- Built-in OAuth2 support (useful if Yandex/Mail.ru ever require it).

### 2.4 Local Database — Room

```kotlin
// Use Room 2.x (stable). Room 3.0 is alpha-only as of April 2026.
val roomVersion = "2.8.4"
implementation("androidx.room:room-runtime:$roomVersion")
implementation("androidx.room:room-ktx:$roomVersion")
ksp("androidx.room:room-compiler:$roomVersion")
```

Room 3.0.0-alpha01 exists (March 2026) but is KMP-focused and alpha. Stick with 2.8.4 for production stability.

### 2.5 Encrypted Key-Value Storage — DataStore + Tink

```kotlin
// EncryptedSharedPreferences is DEPRECATED (1.1.0-alpha07). Use DataStore + Tink instead.
implementation("androidx.datastore:datastore:1.2.1")
implementation("androidx.datastore:datastore-tink:1.3.0-alpha07")
implementation("com.google.crypto.tink:tink-android:1.8.0")
```

DataStore + Tink replaces `EncryptedSharedPreferences`. Encrypts the entire file with AEAD. Coroutine-based, no main-thread I/O. Stores: IMAP/SMTP credentials, private key material backup password, app settings.

### 2.6 QR Code — Scanning (CameraX + ML Kit) & Generation (ZXing)

```kotlin
// QR scanning: CameraX + Google ML Kit
val cameraxVersion = "1.6.0"
implementation("androidx.camera:camera-core:$cameraxVersion")
implementation("androidx.camera:camera-camera2:$cameraxVersion")
implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
implementation("androidx.camera:camera-view:$cameraxVersion")
implementation("androidx.camera:camera-mlkit-vision:$cameraxVersion")
implementation("com.google.mlkit:barcode-scanning:17.3.0")

// QR generation: ZXing core (no need for zxing-android-embedded, we generate bitmaps directly)
implementation("com.google.zxing:core:3.5.3")
```

**Why this split:**
- **Scanning** — ML Kit + CameraX is the Google-recommended stack. It handles autofocus, auto-zoom, and works in Compose via `CameraController`. No need for the heavy `zxing-android-embedded` wrapper.
- **Generation** — ZXing core (`3.5.3`) generates QR `BitMatrix` in-memory; we render it to `Bitmap` ourselves (10 lines of code). The `zxing-android-embedded` library (4.3.0) adds an entire scanner UI we do not need.

### 2.7 Background Work — WorkManager

```kotlin
implementation("androidx.work:work-runtime-ktx:2.11.1")
```

Used for periodic IMAP polling (see Section 3).

### 2.8 Additional Jetpack

```kotlin
implementation("androidx.core:core-ktx:1.16.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
implementation("androidx.lifecycle:lifecycle-service:2.9.0")  // for foreground service
```

---

## 3. Networking — IMAP/SMTP on Android

### 3.1 Architecture

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                       │
│         Jetpack Compose + ViewModels             │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│              Repository Layer                     │
│   ConversationRepository / ContactRepository     │
└──────┬──────────────────────────────┬───────────┘
       │                              │
┌──────▼──────────┐    ┌──────────────▼───────────┐
│   Room Database  │    │     MailTransport         │
│   (messages,     │    │  (ImapClient, SmtpClient) │
│    contacts,     │    │                           │
│    keys)         │    │  Runs on Dispatchers.IO   │
└─────────────────┘    └──────────────┬───────────┘
                                      │
                       ┌──────────────▼───────────┐
                       │  WorkManager / Foreground │
                       │  Service (polling loop)   │
                       └──────────────────────────┘
```

### 3.2 IMAP Polling Strategy

**Two modes, user-selectable:**

1. **WorkManager periodic** (battery-friendly, default):
   - `PeriodicWorkRequestBuilder<MailPollWorker>(15, TimeUnit.MINUTES)` — minimum interval.
   - Constraint: `NetworkType.CONNECTED`.
   - On each run: open IMAP connection, IDLE or SEARCH UNSEEN, fetch new messages, decrypt, store in Room, close.
   - Good for casual use. 15-minute minimum delay.

2. **Foreground Service with IMAP IDLE** (real-time):
   - A `LifecycleService` holds a persistent IMAP connection.
   - Uses IMAP `IDLE` command (RFC 2177) — the server pushes notifications of new mail.
   - Requires a persistent notification (Android requirement for foreground services).
   - Falls back to polling every 5 minutes if IDLE is not supported or connection drops.
   - Yandex and Mail.ru both support IMAP IDLE.

**Implementation notes:**
- All IMAP/SMTP operations run on `Dispatchers.IO` inside coroutines.
- JavaMail `Session` and `Store`/`Transport` objects are **not thread-safe**. Create per-operation or use a single-threaded dispatcher.
- Connection timeouts: set `mail.imap.connectiontimeout=15000` and `mail.imap.timeout=30000`.
- Use `mail.imap.ssl.enable=true` — never plaintext.

### 3.3 SMTP Sending

- Open connection, authenticate, send `MimeMessage` with encrypted body as `application/octet-stream` attachment or inline Base64.
- Close immediately after send. No persistent connection needed.
- Wrap in a coroutine launched from `viewModelScope` or a `OneTimeWorkRequest` if sending from background.

### 3.4 Message Format

Each email sent by CheburMail:
- **To:** recipient's email
- **From:** sender's email
- **Subject:** Fixed tag, e.g., `[cheburmail:v1]` + message UUID (for deduplication)
- **Body:** Base64-encoded `crypto_box_easy()` ciphertext (nonce prepended)
- **Content-Type:** `text/plain` (the Base64 blob is plain text to email servers)

Regular email clients will see gibberish. CheburMail clients decode and decrypt.

---

## 4. Security

### 4.1 Cryptographic Primitives

| Operation | Primitive | Libsodium function |
|-----------|-----------|-------------------|
| Key exchange | X25519 (Curve25519 ECDH) | `crypto_box_keypair()` |
| Message encryption | XSalsa20-Poly1305 | `crypto_box_easy()` |
| Local DB encryption | XSalsa20-Poly1305 (symmetric) | `crypto_secretbox_easy()` |
| Key derivation from passphrase | Argon2id | `crypto_pwhash()` |
| Random | /dev/urandom via libsodium | `randombytes_buf()` |

### 4.2 Key Storage

**Private key** is the crown jewel. Storage strategy:

1. Generate X25519 keypair on first launch.
2. Derive a 256-bit symmetric key from user's passphrase using `crypto_pwhash()` (Argon2id).
3. Encrypt private key with this derived key using `crypto_secretbox_easy()`.
4. Store the encrypted blob + salt + nonce in DataStore (Tink-encrypted file).
5. The passphrase-derived key is held in memory only while the app is unlocked.

**Android Keystore** role:
- Keystore does NOT hold the X25519 private key (Keystore does not support Curve25519).
- Keystore holds the AES-256-GCM key that Tink uses internally for DataStore encryption.
- This provides hardware-backed protection for the DataStore encryption key on devices with StrongBox/TEE.

### 4.3 QR Key Exchange Flow

1. Alice opens "Add Contact" and displays QR code containing:
   ```json
   {"pk": "<base64 X25519 public key>", "email": "alice@yandex.ru", "v": 1}
   ```
2. Bob scans the QR code with CameraX + ML Kit.
3. Bob's app stores Alice's public key and email in Room.
4. Bob displays his own QR code for Alice to scan (mutual exchange).
5. Both can now encrypt messages to each other using `crypto_box_easy(message, nonce, recipient_pk, sender_sk)`.

### 4.4 R8 / ProGuard Rules

```proguard
# proguard-rules.pro

# Lazysodium + JNA — critical: JNA uses reflection heavily
-keep class com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn com.sun.jna.**

# JavaMail for Android — uses ServiceLoader and reflection
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# Tink — uses reflection for key type registration
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ML Kit barcode
-keep class com.google.mlkit.** { *; }

# ZXing core
-keep class com.google.zxing.** { *; }

# Room — generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
```

### 4.5 Network Security Config

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <!-- Only allow TLS connections to mail servers -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">yandex.com</domain>
        <domain includeSubdomains="true">yandex.ru</domain>
        <domain includeSubdomains="true">mail.ru</domain>
    </domain-config>

    <!-- Block all cleartext globally -->
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

---

## 5. Yandex Mail & Mail.ru — Server Settings

### 5.1 Yandex Mail

| Protocol | Server | Port | Security | Auth |
|----------|--------|------|----------|------|
| IMAP | `imap.yandex.com` | 993 | SSL/TLS | App password |
| SMTP | `smtp.yandex.com` | 465 | SSL/TLS | App password |
| SMTP (alt) | `smtp.yandex.com` | 587 | STARTTLS | App password |

**Setup requirements (must be done by user in Yandex web UI):**
1. Go to Yandex Mail Settings > "Mail clients" (Почтовые программы).
2. Enable "With server imap.yandex.com via IMAP" (С сервера imap.yandex.com по IMAP).
3. Go to https://id.yandex.com > Security > App passwords.
4. Create an app password for "Mail" type. Use this password in CheburMail (NOT the account password).

**JavaMail properties:**
```kotlin
val props = Properties().apply {
    put("mail.store.protocol", "imaps")
    put("mail.imaps.host", "imap.yandex.com")
    put("mail.imaps.port", "993")
    put("mail.imaps.ssl.enable", "true")
    put("mail.imaps.connectiontimeout", "15000")
    put("mail.imaps.timeout", "30000")

    put("mail.transport.protocol", "smtps")
    put("mail.smtps.host", "smtp.yandex.com")
    put("mail.smtps.port", "465")
    put("mail.smtps.ssl.enable", "true")
    put("mail.smtps.auth", "true")
    put("mail.smtps.connectiontimeout", "15000")
    put("mail.smtps.timeout", "30000")
}
```

### 5.2 Mail.ru

| Protocol | Server | Port | Security | Auth |
|----------|--------|------|----------|------|
| IMAP | `imap.mail.ru` | 993 | SSL/TLS | App password (if 2FA) or regular password |
| SMTP | `smtp.mail.ru` | 465 | SSL/TLS | App password (if 2FA) or regular password |
| SMTP (alt) | `smtp.mail.ru` | 587 | STARTTLS | App password (if 2FA) or regular password |

**Setup requirements:**
1. If 2FA is enabled, generate an app password in Mail.ru security settings.
2. If 2FA is off, the regular account password works (but 2FA is recommended).
3. IMAP access is enabled by default on Mail.ru.

**JavaMail properties:**
```kotlin
val props = Properties().apply {
    put("mail.store.protocol", "imaps")
    put("mail.imaps.host", "imap.mail.ru")
    put("mail.imaps.port", "993")
    put("mail.imaps.ssl.enable", "true")
    put("mail.imaps.connectiontimeout", "15000")
    put("mail.imaps.timeout", "30000")

    put("mail.transport.protocol", "smtps")
    put("mail.smtps.host", "smtp.mail.ru")
    put("mail.smtps.port", "465")
    put("mail.smtps.ssl.enable", "true")
    put("mail.smtps.auth", "true")
    put("mail.smtps.connectiontimeout", "15000")
    put("mail.smtps.timeout", "30000")
}
```

### 5.3 Domains supported by Mail.ru IMAP/SMTP

All these domains use the same `imap.mail.ru` / `smtp.mail.ru` servers:
- `@mail.ru`
- `@inbox.ru`
- `@list.ru`
- `@bk.ru`

---

## 6. What NOT to Use (and Why)

### BouncyCastle
**Do not use.** Lazysodium/libsodium is simpler, faster, and purpose-built for the exact primitives we need (X25519, XSalsa20-Poly1305). BouncyCastle is a kitchen-sink crypto library (3+ MB) with a confusing API surface. It also conflicts with Android's bundled BouncyCastle provider, requiring `bcprov-jdk15to18` and careful provider registration. Not worth the complexity.

### Firebase Cloud Messaging (FCM)
**Do not use.** Requires Google Play Services, which:
- May not be installed (Huawei devices, custom ROMs, Russian-market phones without GMS).
- Adds Google dependency to a privacy-focused messenger (contradicts the E2E premise).
- Use IMAP IDLE + foreground service for push-like behavior instead.

### Jakarta Mail 2.x / Eclipse Angus Mail
**Do not use on Android.** No official Android support. The `jakarta.*` namespace and activation framework cause classpath issues on Android. Stick with `com.sun.mail:android-mail:1.6.2`.

### EncryptedSharedPreferences
**Deprecated** as of `security-crypto:1.1.0-alpha07`. Plagued by keyset corruption on some OEM devices, main-thread I/O, and no migration path. Use DataStore + Tink instead.

### Room 3.0
**Do not use yet.** Only `3.0.0-alpha01` (March 2026). KMP-focused, Kotlin-only, breaking API changes (`androidx.room3` package). Use Room 2.8.4 for stability.

### Retrofit / OkHttp
**Not needed.** There are no HTTP APIs. All communication is IMAP/SMTP via JavaMail. Adding Retrofit would be dead weight.

### Signal Protocol / libsignal
**Overkill.** Signal Protocol provides Double Ratchet (forward secrecy per message), which is valuable for real-time messaging but adds enormous complexity for an email-transport messenger where messages may arrive out of order or with multi-minute delays. Simple `crypto_box` (static X25519 keys + per-message nonces) is sufficient and dramatically simpler.

### Kotlin Multiplatform (KMP) for this project
**Premature.** The app is Android-only. KMP adds build complexity (expect/actual, shared module, platform-specific source sets). Build for Android first, extract shared logic later if iOS is planned.

### WebSocket / custom server
**Defeats the purpose.** The entire point is serverless E2E messaging over existing email infrastructure. No custom backend.

---

## 7. Full Dependency Summary

```kotlin
// build.gradle.kts (app module)

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")

    // Room (local DB)
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore + Tink (encrypted preferences replacement)
    implementation("androidx.datastore:datastore:1.2.1")
    implementation("androidx.datastore:datastore-tink:1.3.0-alpha07")
    implementation("com.google.crypto.tink:tink-android:1.8.0")

    // Crypto — libsodium via Lazysodium
    implementation("com.goterl:lazysodium-android:5.2.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Email transport — JavaMail for Android
    implementation("com.sun.mail:android-mail:1.6.2")
    implementation("com.sun.mail:android-activation:1.6.2")

    // QR scanning — CameraX + ML Kit
    val cameraxVersion = "1.6.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-mlkit-vision:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // QR generation
    implementation("com.google.zxing:core:3.5.3")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.11.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

---

## 8. Version Pinning Quick Reference

| Library | Version | Released | Notes |
|---------|---------|----------|-------|
| Kotlin | 2.3.20 | Mar 2026 | Latest stable |
| AGP | 9.1.0 | Mar 2026 | Kotlin built-in |
| Gradle | 9.3.1 | Mar 2026 | Compat with AGP 9.1 |
| Compose BOM | 2026.03.00 | Mar 2026 | |
| Room | 2.8.4 | 2025 | Stable; skip 3.0 alpha |
| DataStore | 1.2.1 | 2025 | Stable core |
| DataStore-Tink | 1.3.0-alpha07 | 2026 | Only way to get encrypted DataStore |
| Tink Android | 1.8.0 | 2025 | |
| Lazysodium Android | 5.2.0 | 2024 | Wraps libsodium 1.0.18+ |
| JNA | 5.14.0 | 2024 | Required by Lazysodium |
| JavaMail Android | 1.6.2 | 2018 | Final release, stable, Android-verified |
| CameraX | 1.6.0 | Mar 2026 | Stable, CameraPipe backend |
| ML Kit Barcode | 17.3.0 | 2026 | Bundled model |
| ZXing Core | 3.5.3 | 2024 | QR generation only |
| WorkManager | 2.11.1 | Jan 2026 | minSdk 23 |
