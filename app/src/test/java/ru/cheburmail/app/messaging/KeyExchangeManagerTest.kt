package ru.cheburmail.app.messaging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailMessage
import ru.cheburmail.app.transport.SmtpClient

/**
 * Регрессионные тесты на keyex-петлю, которая приводила к бомбардировке письмами
 * (malinochka1987 case, апр 2026). Критичные инварианты:
 *
 * 1. handleKeyExchange для UNVERIFIED контакта с ИЗМЕНЁННЫМ ключом НЕ должен отправлять
 *    ответный keyex — иначе два устройства с расходящейся историей keyex в IMAP уходят
 *    в бесконечный ping-pong.
 * 2. Повторная обработка того же UUID — no-op (дедуп).
 * 3. sendKeyExchange на один и тот же email — rate-limited (1 попытка в окно).
 */
class KeyExchangeManagerTest {

    private val localPublicKey = ByteArray(32) { it.toByte() }
    private val remotePublicKeyV1 = ByteArray(32) { (it + 100).toByte() }
    private val remotePublicKeyV2 = ByteArray(32) { (it + 200).toByte() }

    private val config = EmailConfig(
        email = "me@example.com",
        password = "pw",
        imapHost = "imap",
        imapPort = 993,
        smtpHost = "smtp",
        smtpPort = 587,
        useTls = true
    )

    @Test
    fun `UNVERIFIED contact with changed key - no reply keyex sent`() = runBlocking {
        val dao = FakeContactDao()
        dao.insert(
            ContactEntity(
                email = "sender@example.com",
                displayName = "Sender",
                publicKey = remotePublicKeyV1,
                fingerprint = "fp-old",
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        val body = keyExBody("sender@example.com", remotePublicKeyV2)
        manager.handleKeyExchange(body, "sender@example.com", config, kexUuid = "uuid-1")

        assertEquals(
            "UNVERIFIED collision must not trigger reply keyex (was causing petle)",
            0,
            smtp.sendCount
        )
        val updated = dao.getByEmail("sender@example.com")!!
        assertTrue("ключ должен быть обновлён локально",
            updated.publicKey.contentEquals(remotePublicKeyV2))
        assertEquals(TrustStatus.UNVERIFIED, updated.trustStatus)
    }

    @Test
    fun `new contact - sends reply keyex once`() = runBlocking {
        val dao = FakeContactDao()
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        manager.handleKeyExchange(
            keyExBody("new@example.com", remotePublicKeyV1),
            "new@example.com", config, kexUuid = "uuid-new"
        )

        assertEquals("новый контакт → ровно один ответный keyex", 1, smtp.sendCount)
        assertNotNull(dao.getByEmail("new@example.com"))
    }

    @Test
    fun `duplicate UUID - second call is no-op`() = runBlocking {
        val dao = FakeContactDao()
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        val body = keyExBody("sender@example.com", remotePublicKeyV1)
        manager.handleKeyExchange(body, "sender@example.com", config, kexUuid = "dup")
        manager.handleKeyExchange(body, "sender@example.com", config, kexUuid = "dup")

        assertEquals(1, smtp.sendCount)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun `VERIFIED contact with changed key - rejected, no reply`() = runBlocking {
        val dao = FakeContactDao()
        dao.insert(
            ContactEntity(
                email = "verified@example.com",
                displayName = "V",
                publicKey = remotePublicKeyV1,
                fingerprint = "fp",
                trustStatus = TrustStatus.VERIFIED,
                createdAt = 1L, updatedAt = 1L
            )
        )
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        manager.handleKeyExchange(
            keyExBody("verified@example.com", remotePublicKeyV2),
            "verified@example.com", config, kexUuid = "uuid-v"
        )

        assertEquals(0, smtp.sendCount)
        val stored = dao.getByEmail("verified@example.com")!!
        assertTrue("VERIFIED ключ не должен обновляться автоматически",
            stored.publicKey.contentEquals(remotePublicKeyV1))
    }

    @Test
    fun `rate-limit - rapid sendKeyExchange to same email only sends once`() = runBlocking {
        val dao = FakeContactDao()
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        repeat(5) {
            manager.sendKeyExchange(config, "target@example.com")
        }

        assertEquals("rate-limit: в окне 1 минуты — только один keyex на адрес",
            1, smtp.sendCount)
    }

    @Test
    fun `envelope mismatch - json email differs from envelope - rejected`() = runBlocking {
        val dao = FakeContactDao()
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        // envelope From = attacker@evil.com, но в JSON подделано legit@example.com
        manager.handleKeyExchange(
            keyExBody("legit@example.com", remotePublicKeyV1),
            fromEmail = "attacker@evil.com",
            config = config,
            kexUuid = "uuid-mitm"
        )

        assertEquals("MITM: ответный keyex не должен отправляться", 0, smtp.sendCount)
        assertNull("MITM: контакт не должен создаваться", dao.getByEmail("legit@example.com"))
        assertNull("MITM: атакующий тоже не получает контакт", dao.getByEmail("attacker@evil.com"))
    }

    @Test
    fun `stale keyex - older timestamp than contact updatedAt - ignored`() = runBlocking {
        val dao = FakeContactDao()
        val now = 10_000L
        dao.insert(
            ContactEntity(
                email = "sender@example.com",
                displayName = "Sender",
                publicKey = remotePublicKeyV2,  // актуальный ключ V2
                fingerprint = "fp-new",
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = 1L,
                updatedAt = now
            )
        )
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        // Приходит старый keyex с V1 — timestamp раньше чем updatedAt контакта
        manager.handleKeyExchange(
            keyExBody("sender@example.com", remotePublicKeyV1),
            fromEmail = "sender@example.com",
            config = config,
            kexUuid = "uuid-stale",
            messageTimestamp = now - 5_000L
        )

        val stored = dao.getByEmail("sender@example.com")!!
        assertTrue("актуальный ключ V2 не должен быть перезатёрт устаревшим keyex",
            stored.publicKey.contentEquals(remotePublicKeyV2))
        assertEquals(0, smtp.sendCount)
    }

    @Test
    fun `fresh keyex - newer timestamp than contact updatedAt - applied`() = runBlocking {
        val dao = FakeContactDao()
        val old = 1_000L
        dao.insert(
            ContactEntity(
                email = "sender@example.com",
                displayName = "Sender",
                publicKey = remotePublicKeyV1,
                fingerprint = "fp-old",
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = 1L,
                updatedAt = old
            )
        )
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        manager.handleKeyExchange(
            keyExBody("sender@example.com", remotePublicKeyV2),
            fromEmail = "sender@example.com",
            config = config,
            kexUuid = "uuid-fresh",
            messageTimestamp = old + 5_000L
        )

        val stored = dao.getByEmail("sender@example.com")!!
        assertTrue("свежий keyex должен обновить ключ",
            stored.publicKey.contentEquals(remotePublicKeyV2))
    }

    @Test
    fun `existing contact with same key - no-op, no reply`() = runBlocking {
        val dao = FakeContactDao()
        dao.insert(
            ContactEntity(
                email = "same@example.com",
                displayName = "S",
                publicKey = remotePublicKeyV1,
                fingerprint = "fp",
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = 1L, updatedAt = 1L
            )
        )
        val smtp = CountingSmtpClient()
        val manager = KeyExchangeManager(
            smtpClient = smtp,
            contactDao = dao,
            keyStorage = FakeKeyStorage(localPublicKey)
        )

        manager.handleKeyExchange(
            keyExBody("same@example.com", remotePublicKeyV1),
            "same@example.com", config, kexUuid = "uuid-same"
        )

        assertEquals(0, smtp.sendCount)
    }

    // --- helpers ---

    private fun keyExBody(email: String, publicKey: ByteArray): ByteArray {
        val json = JSONObject().apply {
            put("email", email)
            put("publicKey", java.util.Base64.getEncoder().encodeToString(publicKey))
            put("displayName", email.substringBefore('@'))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private class CountingSmtpClient : SmtpClient() {
        var sendCount = 0
        override fun send(config: EmailConfig, message: EmailMessage) {
            sendCount++
        }
    }

    private class FakeKeyStorage(private val key: ByteArray) :
        SecureKeyStorage(FakeDataStore(), FakeKeyPairGenerator()) {
        override suspend fun getPublicKey(): ByteArray = key
    }

    private class FakeDataStore : androidx.datastore.core.DataStore<ru.cheburmail.app.storage.StoredKeyData?> {
        override val data: Flow<ru.cheburmail.app.storage.StoredKeyData?>
            get() = kotlinx.coroutines.flow.flowOf(null)
        override suspend fun updateData(
            transform: suspend (ru.cheburmail.app.storage.StoredKeyData?) -> ru.cheburmail.app.storage.StoredKeyData?
        ): ru.cheburmail.app.storage.StoredKeyData? = null
    }

    private class FakeKeyPairGenerator : ru.cheburmail.app.crypto.KeyPairGenerator(
        com.goterl.lazysodium.LazySodiumAndroid(com.goterl.lazysodium.SodiumAndroid())
    )

    private class FakeContactDao : ContactDao {
        private val byEmail = mutableMapOf<String, ContactEntity>()
        private var idSeq = 1L

        override suspend fun insert(contact: ContactEntity): Long {
            val id = idSeq++
            byEmail[contact.email] = contact.copy(id = id)
            return id
        }
        override suspend fun getById(id: Long): ContactEntity? =
            byEmail.values.firstOrNull { it.id == id }
        override suspend fun getByEmail(email: String): ContactEntity? = byEmail[email]
        override fun getAll(): Flow<List<ContactEntity>> =
            kotlinx.coroutines.flow.flowOf(byEmail.values.toList())
        override suspend fun getAllOnce(): List<ContactEntity> = byEmail.values.toList()
        override suspend fun update(contact: ContactEntity) { byEmail[contact.email] = contact }
        override suspend fun delete(contact: ContactEntity) { byEmail.remove(contact.email) }
        override suspend fun deleteById(id: Long) {
            byEmail.entries.removeAll { it.value.id == id }
        }
        fun all(): List<ContactEntity> = byEmail.values.toList()
    }
}
