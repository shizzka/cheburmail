---
plan: 02
title: Крипто-модуль
wave: 1
depends_on: []
autonomous: true
files_modified:
  - app/src/main/java/ru/cheburmail/app/crypto/CryptoConstants.kt
  - app/src/main/java/ru/cheburmail/app/crypto/KeyPairGenerator.kt
  - app/src/main/java/ru/cheburmail/app/crypto/MessageEncryptor.kt
  - app/src/main/java/ru/cheburmail/app/crypto/MessageDecryptor.kt
  - app/src/main/java/ru/cheburmail/app/crypto/NonceGenerator.kt
  - app/src/main/java/ru/cheburmail/app/crypto/CryptoProvider.kt
  - app/src/main/java/ru/cheburmail/app/crypto/model/KeyPair.kt
  - app/src/main/java/ru/cheburmail/app/crypto/model/EncryptedEnvelope.kt
  - app/src/test/java/ru/cheburmail/app/crypto/KeyPairGeneratorTest.kt
  - app/src/test/java/ru/cheburmail/app/crypto/MessageEncryptorTest.kt
  - app/src/test/java/ru/cheburmail/app/crypto/MessageDecryptorTest.kt
  - app/src/test/java/ru/cheburmail/app/crypto/NonceGeneratorTest.kt
  - app/src/test/java/ru/cheburmail/app/crypto/RoundTripTest.kt
---

# Крипто-модуль

## Цель

Реализовать ядро криптографии CheburMail: генерацию X25519 ключевых пар, шифрование/дешифрование через crypto_box_easy (XSalsa20-Poly1305), безопасную генерацию nonce. Модуль написан на чистом Kotlin + Lazysodium без Android-зависимостей и полностью покрыт unit-тестами, которые запускаются через `./gradlew test` (JVM, без эмулятора).

## Задачи

<task id="1" name="Модели данных криптографии" type="feat">
Создать data-классы в пакете `ru.cheburmail.app.crypto.model`:

1. `app/src/main/java/ru/cheburmail/app/crypto/model/KeyPair.kt`:
   ```kotlin
   package ru.cheburmail.app.crypto.model

   /**
    * X25519 ключевая пара.
    * @param publicKey 32 байта — публичный ключ Curve25519
    * @param privateKey 32 байта — приватный ключ Curve25519
    */
   data class KeyPair(
       val publicKey: ByteArray,
       val privateKey: ByteArray
   ) {
       init {
           require(publicKey.size == 32) { "Public key must be 32 bytes, got ${publicKey.size}" }
           require(privateKey.size == 32) { "Private key must be 32 bytes, got ${privateKey.size}" }
       }

       /** Обнуляет приватный ключ в памяти. Вызывать после использования. */
       fun wipePrivateKey() {
           privateKey.fill(0)
       }

       override fun equals(other: Any?): Boolean {
           if (this === other) return true
           if (other !is KeyPair) return false
           return publicKey.contentEquals(other.publicKey) &&
                  privateKey.contentEquals(other.privateKey)
       }

       override fun hashCode(): Int {
           return 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
       }
   }
   ```

2. `app/src/main/java/ru/cheburmail/app/crypto/model/EncryptedEnvelope.kt`:
   ```kotlin
   package ru.cheburmail.app.crypto.model

   /**
    * Зашифрованное сообщение с метаданными для передачи.
    * @param nonce 24 байта — уникальный nonce для данного сообщения
    * @param ciphertext зашифрованный текст (crypto_box_easy output, включает 16-байт MAC)
    */
   data class EncryptedEnvelope(
       val nonce: ByteArray,
       val ciphertext: ByteArray
   ) {
       init {
           require(nonce.size == 24) { "Nonce must be 24 bytes, got ${nonce.size}" }
           require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
       }

       /** Сериализация для email-тела: nonce (24 байта) + ciphertext. */
       fun toBytes(): ByteArray = nonce + ciphertext

       companion object {
           /** Десериализация из байтового массива (nonce + ciphertext). */
           fun fromBytes(data: ByteArray): EncryptedEnvelope {
               require(data.size > 24) { "Data too short: must be > 24 bytes, got ${data.size}" }
               val nonce = data.copyOfRange(0, 24)
               val ciphertext = data.copyOfRange(24, data.size)
               return EncryptedEnvelope(nonce, ciphertext)
           }
       }

       override fun equals(other: Any?): Boolean {
           if (this === other) return true
           if (other !is EncryptedEnvelope) return false
           return nonce.contentEquals(other.nonce) &&
                  ciphertext.contentEquals(other.ciphertext)
       }

       override fun hashCode(): Int {
           return 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
       }
   }
   ```
</task>

<task id="2" name="CryptoProvider — инициализация Lazysodium" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/CryptoProvider.kt`:

```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Singleton-провайдер инстанса Lazysodium.
 * На Android использует LazySodiumAndroid (загружает нативную .so).
 * Для JVM-тестов потребуется LazySodiumJava — см. тестовый провайдер.
 */
object CryptoProvider {
    val lazySodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }
}
```

ВАЖНО: Для unit-тестов на JVM (не Android) нужен отдельный тестовый провайдер, использующий `LazySodiumJava` + `SodiumJava`. Добавить тестовую зависимость:
```kotlin
testImplementation("com.goterl:lazysodium-java:5.1.4")
```

Создать тестовый провайдер в `app/src/test/java/ru/cheburmail/app/crypto/TestCryptoProvider.kt`:
```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

object TestCryptoProvider {
    val lazySodium: LazySodiumJava by lazy {
        LazySodiumJava(SodiumJava())
    }
}
```

Для тестируемости все крипто-классы должны принимать `LazySodium` (общий интерфейс) через конструктор, а не обращаться к `CryptoProvider` напрямую.
</task>

<task id="3" name="CryptoConstants" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/CryptoConstants.kt`:

```kotlin
package ru.cheburmail.app.crypto

/**
 * Константы криптографических операций CheburMail.
 * Все размеры в байтах.
 */
object CryptoConstants {
    /** Длина публичного ключа X25519 */
    const val PUBLIC_KEY_BYTES = 32

    /** Длина приватного ключа X25519 */
    const val PRIVATE_KEY_BYTES = 32

    /** Длина nonce для crypto_box (XSalsa20-Poly1305) */
    const val NONCE_BYTES = 24

    /** Длина MAC-тега Poly1305 (добавляется crypto_box_easy к ciphertext) */
    const val MAC_BYTES = 16
}
```
</task>

<task id="4" name="NonceGenerator — генерация уникальных nonce" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/NonceGenerator.kt`:

```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Random

/**
 * Генерация криптографически безопасных 24-байтных nonce через libsodium randombytes_buf().
 *
 * ВАЖНО: используем случайные nonce (не счётчик). При 192-битном nonce-пространстве XSalsa20
 * вероятность коллизии пренебрежимо мала (~2^-96 после 2^48 сообщений на одну пару ключей).
 */
class NonceGenerator(private val random: Random) {

    /**
     * Генерирует новый 24-байтный nonce.
     * Каждый вызов возвращает уникальное значение.
     */
    fun generate(): ByteArray {
        val nonce = ByteArray(CryptoConstants.NONCE_BYTES)
        random.randomBytesBuf(nonce, nonce.size)
        return nonce
    }
}
```

`Random` — интерфейс Lazysodium, реализуемый и `LazySodiumAndroid`, и `LazySodiumJava`.
</task>

<task id="5" name="KeyPairGenerator — генерация X25519 ключей" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/KeyPairGenerator.kt`:

```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.KeyPair

/**
 * Генерация X25519 ключевых пар через crypto_box_keypair().
 *
 * Требование CRYPT-01: приложение генерирует ключевую пару при первом запуске
 * через libsodium randombytes_buf().
 */
class KeyPairGenerator(private val box: Box.Lazy) {

    /**
     * Генерирует новую X25519 ключевую пару.
     * @return KeyPair с 32-байтными публичным и приватным ключами
     * @throws CryptoException если генерация не удалась
     */
    fun generate(): KeyPair {
        try {
            val keyPair = box.cryptoBoxKeypair()
            return KeyPair(
                publicKey = keyPair.publicKey.asBytes,
                privateKey = keyPair.secretKey.asBytes
            )
        } catch (e: Exception) {
            throw CryptoException("Failed to generate X25519 keypair", e)
        }
    }
}
```

Также создать `app/src/main/java/ru/cheburmail/app/crypto/CryptoException.kt`:
```kotlin
package ru.cheburmail.app.crypto

/**
 * Исключение криптографических операций CheburMail.
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
```
</task>

<task id="6" name="MessageEncryptor — шифрование сообщений" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/MessageEncryptor.kt`:

```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Шифрование сообщений через crypto_box_easy (X25519 + XSalsa20-Poly1305).
 *
 * Требования: CRYPT-05 (crypto_box), CRYPT-06 (уникальный nonce на каждое сообщение).
 */
class MessageEncryptor(
    private val box: Box.Native,
    private val nonceGenerator: NonceGenerator
) {

    /**
     * Шифрует текстовое сообщение для получателя.
     *
     * @param plaintext исходный текст (UTF-8 байты)
     * @param recipientPublicKey 32-байтный публичный ключ получателя
     * @param senderPrivateKey 32-байтный приватный ключ отправителя
     * @return EncryptedEnvelope с nonce и ciphertext
     * @throws CryptoException если шифрование не удалось
     */
    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedEnvelope {
        require(recipientPublicKey.size == CryptoConstants.PUBLIC_KEY_BYTES) {
            "Recipient public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes"
        }
        require(senderPrivateKey.size == CryptoConstants.PRIVATE_KEY_BYTES) {
            "Sender private key must be ${CryptoConstants.PRIVATE_KEY_BYTES} bytes"
        }

        val nonce = nonceGenerator.generate()
        val ciphertext = ByteArray(plaintext.size + CryptoConstants.MAC_BYTES)

        val success = box.cryptoBoxEasy(
            ciphertext,
            plaintext,
            plaintext.size.toLong(),
            nonce,
            recipientPublicKey,
            senderPrivateKey
        )

        if (!success) {
            throw CryptoException("crypto_box_easy failed")
        }

        return EncryptedEnvelope(nonce = nonce, ciphertext = ciphertext)
    }

    /** Convenience-метод для шифрования строки. */
    fun encrypt(
        message: String,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedEnvelope = encrypt(
        plaintext = message.toByteArray(Charsets.UTF_8),
        recipientPublicKey = recipientPublicKey,
        senderPrivateKey = senderPrivateKey
    )
}
```
</task>

<task id="7" name="MessageDecryptor — дешифрование сообщений" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/crypto/MessageDecryptor.kt`:

```kotlin
package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Дешифрование сообщений через crypto_box_open_easy.
 *
 * MAC-верификация выполняется ВНУТРИ libsodium (constant-time).
 * При неверном MAC метод бросает CryptoException — plaintext не возвращается.
 */
class MessageDecryptor(private val box: Box.Native) {

    /**
     * Расшифровывает сообщение.
     *
     * @param envelope зашифрованный конверт (nonce + ciphertext)
     * @param senderPublicKey 32-байтный публичный ключ отправителя
     * @param recipientPrivateKey 32-байтный приватный ключ получателя
     * @return расшифрованный plaintext (байты)
     * @throws CryptoException если MAC-верификация не пройдена или дешифрование не удалось
     */
    fun decrypt(
        envelope: EncryptedEnvelope,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        require(senderPublicKey.size == CryptoConstants.PUBLIC_KEY_BYTES) {
            "Sender public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes"
        }
        require(recipientPrivateKey.size == CryptoConstants.PRIVATE_KEY_BYTES) {
            "Recipient private key must be ${CryptoConstants.PRIVATE_KEY_BYTES} bytes"
        }

        val plaintext = ByteArray(envelope.ciphertext.size - CryptoConstants.MAC_BYTES)

        val success = box.cryptoBoxOpenEasy(
            plaintext,
            envelope.ciphertext,
            envelope.ciphertext.size.toLong(),
            envelope.nonce,
            senderPublicKey,
            recipientPrivateKey
        )

        if (!success) {
            throw CryptoException(
                "crypto_box_open_easy failed: MAC verification failed or invalid ciphertext"
            )
        }

        return plaintext
    }

    /** Convenience-метод: расшифровка в строку. */
    fun decryptToString(
        envelope: EncryptedEnvelope,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): String = decrypt(envelope, senderPublicKey, recipientPrivateKey)
        .toString(Charsets.UTF_8)
}
```
</task>

<task id="8" name="Unit-тесты: KeyPairGenerator" type="test">
Создать `app/src/test/java/ru/cheburmail/app/crypto/KeyPairGeneratorTest.kt`:

```kotlin
package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeyPairGeneratorTest {

    private lateinit var generator: KeyPairGenerator

    @Before
    fun setUp() {
        generator = KeyPairGenerator(TestCryptoProvider.lazySodium)
    }

    @Test
    fun `generate returns keypair with 32-byte public key`() {
        val keyPair = generator.generate()
        assertEquals(32, keyPair.publicKey.size)
    }

    @Test
    fun `generate returns keypair with 32-byte private key`() {
        val keyPair = generator.generate()
        assertEquals(32, keyPair.privateKey.size)
    }

    @Test
    fun `generate produces different keypairs on each call`() {
        val kp1 = generator.generate()
        val kp2 = generator.generate()
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
        assertFalse(kp1.privateKey.contentEquals(kp2.privateKey))
    }

    @Test
    fun `public key is not all zeros`() {
        val keyPair = generator.generate()
        assertFalse(keyPair.publicKey.all { it == 0.toByte() })
    }

    @Test
    fun `private key is not all zeros`() {
        val keyPair = generator.generate()
        assertFalse(keyPair.privateKey.all { it == 0.toByte() })
    }

    @Test
    fun `wipePrivateKey zeroes out private key bytes`() {
        val keyPair = generator.generate()
        keyPair.wipePrivateKey()
        assertTrue(keyPair.privateKey.all { it == 0.toByte() })
    }
}
```
</task>

<task id="9" name="Unit-тесты: NonceGenerator" type="test">
Создать `app/src/test/java/ru/cheburmail/app/crypto/NonceGeneratorTest.kt`:

```kotlin
package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NonceGeneratorTest {

    private lateinit var nonceGenerator: NonceGenerator

    @Before
    fun setUp() {
        nonceGenerator = NonceGenerator(TestCryptoProvider.lazySodium)
    }

    @Test
    fun `generate returns 24-byte nonce`() {
        val nonce = nonceGenerator.generate()
        assertEquals(24, nonce.size)
    }

    @Test
    fun `generate returns different nonce each time`() {
        val nonce1 = nonceGenerator.generate()
        val nonce2 = nonceGenerator.generate()
        assertFalse(nonce1.contentEquals(nonce2))
    }

    @Test
    fun `generate returns non-zero nonce`() {
        val nonce = nonceGenerator.generate()
        assertFalse(nonce.all { it == 0.toByte() })
    }

    @Test
    fun `100 consecutive nonces are all unique`() {
        val nonces = (1..100).map { nonceGenerator.generate() }
        val uniqueCount = nonces.distinctBy { it.toList() }.size
        assertEquals(100, uniqueCount)
    }
}
```
</task>

<task id="10" name="Unit-тесты: шифрование и дешифрование" type="test">
Создать `app/src/test/java/ru/cheburmail/app/crypto/MessageEncryptorTest.kt`:

```kotlin
package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageEncryptorTest {

    private lateinit var encryptor: MessageEncryptor
    private lateinit var keyGen: KeyPairGenerator

    @Before
    fun setUp() {
        val ls = TestCryptoProvider.lazySodium
        encryptor = MessageEncryptor(ls, NonceGenerator(ls))
        keyGen = KeyPairGenerator(ls)
    }

    @Test
    fun `encrypt returns envelope with 24-byte nonce`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val envelope = encryptor.encrypt("hello", recipient.publicKey, sender.privateKey)
        assertEquals(24, envelope.nonce.size)
    }

    @Test
    fun `encrypt returns ciphertext longer than plaintext by MAC_BYTES`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val plaintext = "hello world"
        val envelope = encryptor.encrypt(plaintext, recipient.publicKey, sender.privateKey)
        assertEquals(plaintext.toByteArray().size + CryptoConstants.MAC_BYTES, envelope.ciphertext.size)
    }

    @Test
    fun `encrypt generates unique nonce for each call`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val e1 = encryptor.encrypt("msg1", recipient.publicKey, sender.privateKey)
        val e2 = encryptor.encrypt("msg2", recipient.publicKey, sender.privateKey)
        assertFalse(e1.nonce.contentEquals(e2.nonce))
    }

    @Test
    fun `encrypt same message twice produces different ciphertext`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val e1 = encryptor.encrypt("same", recipient.publicKey, sender.privateKey)
        val e2 = encryptor.encrypt("same", recipient.publicKey, sender.privateKey)
        assertFalse(e1.ciphertext.contentEquals(e2.ciphertext))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects wrong-size recipient key`() {
        val sender = keyGen.generate()
        encryptor.encrypt("test", ByteArray(16), sender.privateKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects wrong-size sender key`() {
        val recipient = keyGen.generate()
        encryptor.encrypt("test", recipient.publicKey, ByteArray(16))
    }
}
```

Создать `app/src/test/java/ru/cheburmail/app/crypto/MessageDecryptorTest.kt`:

```kotlin
package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class MessageDecryptorTest {

    private lateinit var decryptor: MessageDecryptor
    private lateinit var encryptor: MessageEncryptor
    private lateinit var keyGen: KeyPairGenerator

    @Before
    fun setUp() {
        val ls = TestCryptoProvider.lazySodium
        decryptor = MessageDecryptor(ls)
        encryptor = MessageEncryptor(ls, NonceGenerator(ls))
        keyGen = KeyPairGenerator(ls)
    }

    @Test(expected = CryptoException::class)
    fun `decrypt with wrong sender key throws CryptoException`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val imposter = keyGen.generate()
        val envelope = encryptor.encrypt("secret", recipient.publicKey, sender.privateKey)
        decryptor.decrypt(envelope, imposter.publicKey, recipient.privateKey)
    }

    @Test(expected = CryptoException::class)
    fun `decrypt with wrong recipient key throws CryptoException`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val wrong = keyGen.generate()
        val envelope = encryptor.encrypt("secret", recipient.publicKey, sender.privateKey)
        decryptor.decrypt(envelope, sender.publicKey, wrong.privateKey)
    }

    @Test(expected = CryptoException::class)
    fun `decrypt tampered ciphertext throws CryptoException`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val envelope = encryptor.encrypt("secret", recipient.publicKey, sender.privateKey)
        // Повреждаем ciphertext
        val tampered = envelope.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        val tamperedEnvelope = EncryptedEnvelope(envelope.nonce, tampered)
        decryptor.decrypt(tamperedEnvelope, sender.publicKey, recipient.privateKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt rejects wrong-size sender key`() {
        val envelope = EncryptedEnvelope(ByteArray(24), ByteArray(32))
        decryptor.decrypt(envelope, ByteArray(16), ByteArray(32))
    }
}
```
</task>

<task id="11" name="Unit-тесты: round-trip encrypt -> decrypt" type="test">
Создать `app/src/test/java/ru/cheburmail/app/crypto/RoundTripTest.kt`:

```kotlin
package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Ключевой тест фазы 1: encrypt -> decrypt round-trip.
 * Покрывает требования CRYPT-05, CRYPT-06.
 */
class RoundTripTest {

    private lateinit var encryptor: MessageEncryptor
    private lateinit var decryptor: MessageDecryptor
    private lateinit var keyGen: KeyPairGenerator

    @Before
    fun setUp() {
        val ls = TestCryptoProvider.lazySodium
        encryptor = MessageEncryptor(ls, NonceGenerator(ls))
        decryptor = MessageDecryptor(ls)
        keyGen = KeyPairGenerator(ls)
    }

    @Test
    fun `encrypt then decrypt returns original message`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val original = "Привет, мир! Hello, world! 🔐"

        val envelope = encryptor.encrypt(original, recipient.publicKey, sender.privateKey)
        val decrypted = decryptor.decryptToString(envelope, sender.publicKey, recipient.privateKey)

        assertEquals(original, decrypted)
    }

    @Test
    fun `round-trip works for empty string`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val original = ""

        val envelope = encryptor.encrypt(original, recipient.publicKey, sender.privateKey)
        val decrypted = decryptor.decryptToString(envelope, sender.publicKey, recipient.privateKey)

        assertEquals(original, decrypted)
    }

    @Test
    fun `round-trip works for large message`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val original = "A".repeat(100_000)

        val envelope = encryptor.encrypt(original, recipient.publicKey, sender.privateKey)
        val decrypted = decryptor.decryptToString(envelope, sender.publicKey, recipient.privateKey)

        assertEquals(original, decrypted)
    }

    @Test
    fun `round-trip works with serialized envelope bytes`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val original = "test via serialization"

        val envelope = encryptor.encrypt(original, recipient.publicKey, sender.privateKey)

        // Сериализация -> десериализация (имитация передачи через email)
        val bytes = envelope.toBytes()
        val restored = ru.cheburmail.app.crypto.model.EncryptedEnvelope.fromBytes(bytes)

        val decrypted = decryptor.decryptToString(restored, sender.publicKey, recipient.privateKey)
        assertEquals(original, decrypted)
    }

    @Test
    fun `bidirectional communication works`() {
        val alice = keyGen.generate()
        val bob = keyGen.generate()

        // Alice -> Bob
        val msgToBob = "Привет, Боб!"
        val envToBob = encryptor.encrypt(msgToBob, bob.publicKey, alice.privateKey)
        val decryptedByBob = decryptor.decryptToString(envToBob, alice.publicKey, bob.privateKey)
        assertEquals(msgToBob, decryptedByBob)

        // Bob -> Alice
        val msgToAlice = "Привет, Алиса!"
        val envToAlice = encryptor.encrypt(msgToAlice, alice.publicKey, bob.privateKey)
        val decryptedByAlice = decryptor.decryptToString(envToAlice, bob.publicKey, alice.privateKey)
        assertEquals(msgToAlice, decryptedByAlice)
    }

    @Test
    fun `each encryption generates unique 24-byte nonce`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()
        val nonces = (1..50).map {
            encryptor.encrypt("msg$it", recipient.publicKey, sender.privateKey).nonce
        }

        // Все nonce уникальны
        val uniqueCount = nonces.distinctBy { it.toList() }.size
        assertEquals(50, uniqueCount)

        // Все nonce 24 байта
        nonces.forEach { assertEquals(24, it.size) }
    }

    @Test
    fun `unicode and special characters survive round-trip`() {
        val sender = keyGen.generate()
        val recipient = keyGen.generate()

        val testCases = listOf(
            "Кириллица: абвгд АБВГД",
            "日本語テスト",
            "Emoji: 😀🔑🔒✅❌",
            "Special: \n\t\r\u0000\\\"'",
            "Mixed: Hello Мир 世界 🌍"
        )

        for (original in testCases) {
            val envelope = encryptor.encrypt(original, recipient.publicKey, sender.privateKey)
            val decrypted = decryptor.decryptToString(envelope, sender.publicKey, recipient.privateKey)
            assertEquals("Failed for: $original", original, decrypted)
        }
    }
}
```
</task>

## must_haves

- [ ] `KeyPairGenerator.generate()` возвращает ключевую пару с публичным и приватным ключами по 32 байта каждый
- [ ] `MessageEncryptor.encrypt()` генерирует уникальный 24-байтный nonce при каждом вызове
- [ ] `encrypt -> decrypt` round-trip возвращает исходное сообщение для произвольного UTF-8 текста
- [ ] MAC-верификация: подмена ciphertext или использование неверного ключа бросает `CryptoException`
- [ ] `EncryptedEnvelope.toBytes()` / `fromBytes()` корректно сериализуют/десериализуют данные
- [ ] Все тесты запускаются на JVM без Android-эмулятора: `./gradlew test`
- [ ] Модуль не имеет Android-зависимостей (кроме Lazysodium-android для production и Lazysodium-java для тестов)

## Верификация

1. `./gradlew test --tests "ru.cheburmail.app.crypto.*"` — все тесты проходят
2. Проверить что `KeyPairGeneratorTest` содержит тест на длину ключей (32 байта)
3. Проверить что `RoundTripTest` содержит тест encrypt -> decrypt round-trip
4. Проверить что `NonceGeneratorTest` подтверждает уникальность 24-байтных nonce
5. Проверить что `MessageDecryptorTest` подтверждает что повреждённый ciphertext и неверные ключи приводят к ошибке
