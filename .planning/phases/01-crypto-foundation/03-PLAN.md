---
plan: 03
title: Безопасное хранение ключей
wave: 2
depends_on: [01, 02]
autonomous: true
files_modified:
  - app/src/main/java/ru/cheburmail/app/storage/SecureKeyStorage.kt
  - app/src/main/java/ru/cheburmail/app/storage/EncryptedDataStoreFactory.kt
  - app/src/main/java/ru/cheburmail/app/storage/KeyStorageSerializer.kt
  - app/src/main/java/ru/cheburmail/app/storage/model/StoredKeyData.kt
  - app/src/main/java/ru/cheburmail/app/CheburMailApp.kt
  - app/src/androidTest/java/ru/cheburmail/app/storage/SecureKeyStorageTest.kt
  - app/src/androidTest/java/ru/cheburmail/app/storage/KeyPersistenceTest.kt
---

# Безопасное хранение ключей

## Цель

Реализовать безопасное хранение приватного ключа X25519 через DataStore + Tink с привязкой к Android Keystore. Приватный ключ шифруется AES-256-GCM (ключ AES хранится в аппаратном Keystore/TEE). После перезапуска приложения ключ читается и корректно расшифровывает ранее зашифрованные данные. Покрывает требования CRYPT-02 и STOR-04.

## Задачи

<task id="1" name="Модель хранения ключей" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/storage/model/StoredKeyData.kt`:

```kotlin
package ru.cheburmail.app.storage.model

import java.io.Serializable

/**
 * Данные ключевой пары для персистентного хранения в DataStore.
 *
 * @param publicKey 32 байта — публичный ключ X25519 (не секретный, но хранится для удобства)
 * @param privateKey 32 байта — приватный ключ X25519 (СЕКРЕТ — шифруется Tink/Keystore)
 * @param createdAtMillis timestamp создания ключевой пары (Unix millis)
 */
data class StoredKeyData(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val createdAtMillis: Long
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredKeyData) return false
        return publicKey.contentEquals(other.publicKey) &&
               privateKey.contentEquals(other.privateKey) &&
               createdAtMillis == other.createdAtMillis
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + createdAtMillis.hashCode()
        return result
    }
}
```
</task>

<task id="2" name="Protobuf-сериализатор для DataStore" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/storage/KeyStorageSerializer.kt`:

```kotlin
package ru.cheburmail.app.storage

import androidx.datastore.core.Serializer
import ru.cheburmail.app.storage.model.StoredKeyData
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Сериализатор StoredKeyData для DataStore.
 *
 * Формат: [4 байта pubkey_len][pubkey][4 байта privkey_len][privkey][8 байт timestamp]
 *
 * ВАЖНО: сериализация минимальная и бинарная — избегаем лишних зависимостей (protobuf).
 * Tink шифрует ВЕСЬ файл DataStore — нам не нужна дополнительная защита на уровне полей.
 */
object KeyStorageSerializer : Serializer<StoredKeyData?> {

    override val defaultValue: StoredKeyData? = null

    override suspend fun readFrom(input: InputStream): StoredKeyData? {
        return try {
            val data = input.readBytes()
            if (data.isEmpty()) return null

            val buffer = ByteBuffer.wrap(data)

            val pubKeyLen = buffer.getInt()
            val pubKey = ByteArray(pubKeyLen)
            buffer.get(pubKey)

            val privKeyLen = buffer.getInt()
            val privKey = ByteArray(privKeyLen)
            buffer.get(privKey)

            val timestamp = buffer.getLong()

            StoredKeyData(
                publicKey = pubKey,
                privateKey = privKey,
                createdAtMillis = timestamp
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun writeTo(t: StoredKeyData?, output: OutputStream) {
        if (t == null) {
            output.write(ByteArray(0))
            return
        }

        val size = 4 + t.publicKey.size + 4 + t.privateKey.size + 8
        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(t.publicKey.size)
        buffer.put(t.publicKey)
        buffer.putInt(t.privateKey.size)
        buffer.put(t.privateKey)
        buffer.putLong(t.createdAtMillis)

        output.write(buffer.array())
    }
}
```
</task>

<task id="3" name="Фабрика зашифрованного DataStore" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/storage/EncryptedDataStoreFactory.kt`:

```kotlin
package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.datastore.tink.TinkSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import ru.cheburmail.app.storage.model.StoredKeyData

/**
 * Создание зашифрованного DataStore для хранения ключей.
 *
 * Цепочка защиты:
 * 1. Tink шифрует файл DataStore с AES-256-GCM AEAD
 * 2. Мастер-ключ Tink хранится в Android Keystore (аппаратный TEE где доступен)
 * 3. Keystore-ключ создаётся при первом запуске и не покидает аппаратный модуль
 *
 * Файл DataStore: "cheburmail_keys.pb" в приватной директории приложения.
 */
object EncryptedDataStoreFactory {

    private const val KEYSET_NAME = "cheburmail_master_keyset"
    private const val PREFERENCE_FILE = "cheburmail_master_key_prefs"
    private const val MASTER_KEY_URI = "android-keystore://cheburmail_master_key"
    private const val DATASTORE_FILE = "cheburmail_keys.pb"

    /**
     * Инициализация Tink. Вызвать ОДИН РАЗ в Application.onCreate().
     */
    fun initTink() {
        AeadConfig.register()
    }

    /**
     * Получить AEAD-примитив, привязанный к Android Keystore.
     */
    fun getAead(context: Context): Aead {
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Создать зашифрованный DataStore.
     *
     * Использование:
     * ```kotlin
     * val dataStore = EncryptedDataStoreFactory.create(applicationContext)
     * val keyData = dataStore.data.first()  // читает и расшифровывает
     * dataStore.updateData { newKeyData }    // шифрует и записывает
     * ```
     */
    fun create(context: Context): DataStore<StoredKeyData?> {
        val aead = getAead(context)

        val tinkSerializer = TinkSerializer(
            aead = aead,
            delegate = KeyStorageSerializer
        )

        return DataStoreFactory.create(
            serializer = tinkSerializer,
            produceFile = { context.dataStoreFile(DATASTORE_FILE) }
        )
    }
}
```

ПРИМЕЧАНИЕ: Точный API `datastore-tink` может отличаться в версии 1.3.0-alpha07. При реализации проверить актуальный API:
- Если `TinkSerializer` недоступен, использовать `EncryptedDataStoreSerializer` или аналог из `androidx.datastore.tink`
- Альтернативный путь: использовать `DataStore<Preferences>` + ручное шифрование через `Aead.encrypt()/decrypt()` для байтов ключа
- При любом варианте мастер-ключ AEAD должен быть в Android Keystore через `AndroidKeysetManager`
</task>

<task id="4" name="SecureKeyStorage — высокоуровневый API" type="feat">
Создать `app/src/main/java/ru/cheburmail/app/storage/SecureKeyStorage.kt`:

```kotlin
package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.model.KeyPair
import ru.cheburmail.app.storage.model.StoredKeyData

/**
 * Высокоуровневый API для безопасного хранения ключевой пары X25519.
 *
 * Требования: CRYPT-02, STOR-04.
 *
 * Жизненный цикл:
 * 1. getOrCreateKeyPair() — при первом запуске генерирует ключи и сохраняет;
 *    при последующих — читает из зашифрованного DataStore
 * 2. getPublicKey() — возвращает публичный ключ (для QR, для шифрования)
 * 3. getPrivateKey() — возвращает приватный ключ (для дешифрования)
 * 4. hasKeyPair() — проверяет наличие сохранённой пары
 * 5. deleteKeyPair() — удаляет пару (для wipe/reset)
 */
class SecureKeyStorage(
    private val dataStore: DataStore<StoredKeyData?>,
    private val keyPairGenerator: KeyPairGenerator
) {

    /**
     * Получает существующую ключевую пару или генерирует новую.
     *
     * Thread-safe: DataStore гарантирует атомарность.
     * @return KeyPair с публичным и приватным ключами
     */
    suspend fun getOrCreateKeyPair(): KeyPair {
        val existing = dataStore.data.first()
        if (existing != null) {
            return KeyPair(
                publicKey = existing.publicKey,
                privateKey = existing.privateKey
            )
        }

        // Первый запуск: генерируем и сохраняем
        val newKeyPair = keyPairGenerator.generate()
        val storedData = StoredKeyData(
            publicKey = newKeyPair.publicKey,
            privateKey = newKeyPair.privateKey,
            createdAtMillis = System.currentTimeMillis()
        )

        dataStore.updateData { storedData }
        return newKeyPair
    }

    /**
     * Проверяет наличие сохранённой ключевой пары.
     */
    suspend fun hasKeyPair(): Boolean {
        return dataStore.data.first() != null
    }

    /**
     * Возвращает публичный ключ или null если пара не создана.
     */
    suspend fun getPublicKey(): ByteArray? {
        return dataStore.data.first()?.publicKey
    }

    /**
     * Возвращает приватный ключ или null если пара не создана.
     */
    suspend fun getPrivateKey(): ByteArray? {
        return dataStore.data.first()?.privateKey
    }

    /**
     * Наблюдение за наличием ключевой пары (для UI).
     */
    fun observeHasKeyPair(): Flow<Boolean> {
        return dataStore.data.map { it != null }
    }

    /**
     * Удаляет сохранённую ключевую пару.
     * ВНИМАНИЕ: необратимая операция — все зашифрованные данные станут нечитаемыми.
     */
    suspend fun deleteKeyPair() {
        dataStore.updateData { null }
    }

    companion object {
        /**
         * Создаёт экземпляр SecureKeyStorage.
         * Вызывать после EncryptedDataStoreFactory.initTink().
         */
        fun create(context: Context, keyPairGenerator: KeyPairGenerator): SecureKeyStorage {
            val dataStore = EncryptedDataStoreFactory.create(context)
            return SecureKeyStorage(dataStore, keyPairGenerator)
        }
    }
}
```
</task>

<task id="5" name="Инициализация Tink в Application" type="feat">
Обновить `app/src/main/java/ru/cheburmail/app/CheburMailApp.kt`:

```kotlin
package ru.cheburmail.app

import android.app.Application
import ru.cheburmail.app.storage.EncryptedDataStoreFactory

class CheburMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Инициализация Tink AEAD — должна быть ДО любых обращений к DataStore
        EncryptedDataStoreFactory.initTink()
    }
}
```

ВАЖНО:
- `AeadConfig.register()` должен вызываться ровно один раз
- Вызов в `Application.onCreate()` гарантирует инициализацию до любого Activity/Service
- Не бросает исключение при повторном вызове (idempotent)
</task>

<task id="6" name="Интеграционный тест: сохранение и чтение ключа" type="test">
Создать `app/src/androidTest/java/ru/cheburmail/app/storage/SecureKeyStorageTest.kt`:

Этот тест запускается на реальном устройстве/эмуляторе (androidTest) так как требует Android Keystore.

```kotlin
package ru.cheburmail.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator

@RunWith(AndroidJUnit4::class)
class SecureKeyStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SecureKeyStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EncryptedDataStoreFactory.initTink()
        val keyGen = KeyPairGenerator(CryptoProvider.lazySodium)
        storage = SecureKeyStorage.create(context, keyGen)
    }

    @After
    fun tearDown() = runTest {
        storage.deleteKeyPair()
    }

    @Test
    fun hasKeyPair_returnsFalseInitially() = runTest {
        assertFalse(storage.hasKeyPair())
    }

    @Test
    fun getOrCreateKeyPair_generatesAndStores() = runTest {
        val keyPair = storage.getOrCreateKeyPair()

        assertEquals(32, keyPair.publicKey.size)
        assertEquals(32, keyPair.privateKey.size)
        assertTrue(storage.hasKeyPair())
    }

    @Test
    fun getOrCreateKeyPair_returnsSameKeyOnSecondCall() = runTest {
        val first = storage.getOrCreateKeyPair()
        val second = storage.getOrCreateKeyPair()

        assertArrayEquals(first.publicKey, second.publicKey)
        assertArrayEquals(first.privateKey, second.privateKey)
    }

    @Test
    fun getPublicKey_returnsStoredKey() = runTest {
        val keyPair = storage.getOrCreateKeyPair()
        val publicKey = storage.getPublicKey()

        assertNotNull(publicKey)
        assertArrayEquals(keyPair.publicKey, publicKey)
    }

    @Test
    fun getPrivateKey_returnsStoredKey() = runTest {
        val keyPair = storage.getOrCreateKeyPair()
        val privateKey = storage.getPrivateKey()

        assertNotNull(privateKey)
        assertArrayEquals(keyPair.privateKey, privateKey)
    }

    @Test
    fun deleteKeyPair_removesStoredKey() = runTest {
        storage.getOrCreateKeyPair()
        assertTrue(storage.hasKeyPair())

        storage.deleteKeyPair()
        assertFalse(storage.hasKeyPair())
    }

    @Test
    fun observeHasKeyPair_emitsCorrectValues() = runTest {
        assertFalse(storage.observeHasKeyPair().first())

        storage.getOrCreateKeyPair()
        assertTrue(storage.observeHasKeyPair().first())
    }
}
```

Дополнительная тестовая зависимость:
```kotlin
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
```
</task>

<task id="7" name="Интеграционный тест: ключ переживает перезапуск и расшифровывает данные" type="test">
Создать `app/src/androidTest/java/ru/cheburmail/app/storage/KeyPersistenceTest.kt`:

Этот тест проверяет критерий успеха #3 фазы 1: приватный ключ, сохранённый через DataStore + Tink, после "перезапуска" (пересоздания storage) корректно читается и используется для дешифрования.

```kotlin
package ru.cheburmail.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator

/**
 * Интеграционный тест: ключ переживает "перезапуск" приложения.
 *
 * Сценарий:
 * 1. Создать ключевую пару и сохранить через SecureKeyStorage
 * 2. Зашифровать сообщение с этим ключом
 * 3. Создать НОВЫЙ экземпляр SecureKeyStorage (имитация перезапуска)
 * 4. Прочитать ключ из нового экземпляра
 * 5. Расшифровать сообщение — должно вернуть оригинал
 */
@RunWith(AndroidJUnit4::class)
class KeyPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EncryptedDataStoreFactory.initTink()
    }

    @After
    fun tearDown() = runTest {
        val keyGen = KeyPairGenerator(CryptoProvider.lazySodium)
        val storage = SecureKeyStorage.create(context, keyGen)
        storage.deleteKeyPair()
    }

    @Test
    fun keyPair_survivesRestart_and_decryptsMessage() = runTest {
        val ls = CryptoProvider.lazySodium
        val keyGen = KeyPairGenerator(ls)
        val encryptor = MessageEncryptor(ls, NonceGenerator(ls))
        val decryptor = MessageDecryptor(ls)

        // === "Первый запуск" ===
        val storage1 = SecureKeyStorage.create(context, keyGen)
        val originalKeyPair = storage1.getOrCreateKeyPair()

        // Создаём контакта (Bob) и шифруем сообщение ему
        val bob = keyGen.generate()
        val originalMessage = "Секретное сообщение, которое должно пережить перезапуск"
        val envelope = encryptor.encrypt(
            originalMessage,
            bob.publicKey,
            originalKeyPair.privateKey
        )

        // === "Перезапуск приложения" — новый экземпляр storage ===
        val storage2 = SecureKeyStorage.create(context, keyGen)

        // Читаем ключ из хранилища
        val restoredKeyPair = storage2.getOrCreateKeyPair()

        // Ключи совпадают
        assertArrayEquals(
            "Public key must survive restart",
            originalKeyPair.publicKey,
            restoredKeyPair.publicKey
        )
        assertArrayEquals(
            "Private key must survive restart",
            originalKeyPair.privateKey,
            restoredKeyPair.privateKey
        )

        // Bob расшифровывает сообщение, используя восстановленный публичный ключ Alice
        val decrypted = decryptor.decryptToString(
            envelope,
            restoredKeyPair.publicKey,  // Alice's restored public key
            bob.privateKey               // Bob's private key
        )

        assertEquals(originalMessage, decrypted)
    }

    @Test
    fun storedKey_notAllZeros() = runTest {
        val keyGen = KeyPairGenerator(CryptoProvider.lazySodium)
        val storage = SecureKeyStorage.create(context, keyGen)

        val keyPair = storage.getOrCreateKeyPair()

        assertFalse(
            "Stored public key must not be all zeros",
            keyPair.publicKey.all { it == 0.toByte() }
        )
        assertFalse(
            "Stored private key must not be all zeros",
            keyPair.privateKey.all { it == 0.toByte() }
        )
    }

    @Test
    fun secondInstance_doesNotGenerateNewKey() = runTest {
        val keyGen = KeyPairGenerator(CryptoProvider.lazySodium)

        val storage1 = SecureKeyStorage.create(context, keyGen)
        val kp1 = storage1.getOrCreateKeyPair()

        val storage2 = SecureKeyStorage.create(context, keyGen)
        val kp2 = storage2.getOrCreateKeyPair()

        // Должен вернуть тот же ключ, а не генерировать новый
        assertArrayEquals(kp1.publicKey, kp2.publicKey)
        assertArrayEquals(kp1.privateKey, kp2.privateKey)
    }
}
```
</task>

## must_haves

- [ ] Приватный ключ шифруется Tink AEAD (AES-256-GCM) с мастер-ключом из Android Keystore
- [ ] `android:allowBackup="false"` — файл DataStore не включается в бэкап устройства
- [ ] `SecureKeyStorage.getOrCreateKeyPair()` при первом вызове генерирует пару, при повторных — возвращает ранее сохранённую
- [ ] Интеграционный тест: после пересоздания SecureKeyStorage (имитация перезапуска) ключ читается и расшифровывает ранее зашифрованные данные
- [ ] `deleteKeyPair()` корректно удаляет данные; `hasKeyPair()` возвращает false после удаления
- [ ] Tink инициализируется в `Application.onCreate()` до любых обращений к DataStore

## Верификация

1. `./gradlew connectedAndroidTest --tests "ru.cheburmail.app.storage.*"` — все инструментальные тесты проходят на эмуляторе API 26+
2. Проверить что `KeyPersistenceTest.keyPair_survivesRestart_and_decryptsMessage` проходит — ключевой критерий успеха фазы 1
3. Проверить через Android Studio / `adb shell` что файл `cheburmail_keys.pb` существует в приватной директории приложения и не читается как plaintext
4. Убедиться что `cheburmail_master_key` присутствует в Android Keystore: `adb shell "su -c 'ls /data/misc/keystore/user_0/'"` (только rooted) или через Keystore API в тесте
