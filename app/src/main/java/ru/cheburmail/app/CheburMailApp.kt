package ru.cheburmail.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.EncryptedDataStoreFactory
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.sync.SyncManager

class CheburMailApp : Application() {

    lateinit var syncManager: SyncManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        EncryptedDataStoreFactory.initTink()

        // Создание каналов уведомлений
        NotificationHelper(this).createNotificationChannels()

        // Инициализация фоновой синхронизации
        syncManager = SyncManager(this)
        appScope.launch {
            syncManager.initialize()
            // Debug: dump account and public key
            try {
                val repo = AccountRepository.create(this@CheburMailApp)
                val config = repo.getActive()
                val myEmail = config?.email ?: "none"
                Log.i("CheburDebug", "ACCOUNT: $myEmail")

                val ls = CryptoProvider.lazySodium
                val kpg = KeyPairGenerator(ls)
                val keyStorage = SecureKeyStorage.create(this@CheburMailApp, kpg)
                val pk = keyStorage.getPublicKey()
                if (pk != null) {
                    Log.i("CheburDebug", "PUBLIC_KEY_HEX: ${pk.toHex()}")
                }

                val db = ru.cheburmail.app.db.CheburMailDatabase.getInstance(this@CheburMailApp)

                // === Auto-setup contacts for testing ===
                val contacts = mapOf(
                    "mr_respect@bk.ru" to "CEC9A97B41F5837A8F5FD1FFDE340D161FC68F190325FD293F4070B797A4764C",
                    "malinochka1987@bk.ru" to "F3916806CC71EF4FB83DC7B40E663D94CA478443A9202B39AA05C25D65DC203F"
                )
                for ((email, pkHex) in contacts) {
                    if (email == myEmail) continue // Don't add self
                    val contactPk = pkHex.hexToBytes()
                    val existing = db.contactDao().getByEmail(email)
                    if (existing == null) {
                        val fingerprint = if (pk != null) {
                            ru.cheburmail.app.crypto.FingerprintGenerator.generateHex(pk, contactPk)
                        } else "unknown"
                        val now = System.currentTimeMillis()
                        db.contactDao().insert(ru.cheburmail.app.db.entity.ContactEntity(
                            email = email,
                            displayName = email.substringBefore('@'),
                            publicKey = contactPk,
                            fingerprint = fingerprint,
                            trustStatus = ru.cheburmail.app.db.TrustStatus.VERIFIED,
                            createdAt = now,
                            updatedAt = now
                        ))
                        Log.i("CheburDebug", "AUTO-ADDED contact: $email")
                    } else if (!existing.publicKey.contentEquals(contactPk)) {
                        // Update stale key
                        val fingerprint = if (pk != null) {
                            ru.cheburmail.app.crypto.FingerprintGenerator.generateHex(pk, contactPk)
                        } else existing.fingerprint
                        db.contactDao().update(existing.copy(
                            publicKey = contactPk,
                            fingerprint = fingerprint,
                            updatedAt = System.currentTimeMillis()
                        ))
                        Log.i("CheburDebug", "UPDATED key for contact: $email")
                    }
                }

                // Fix stuck SENDING messages
                val allMsgs = db.messageDao().getAllOnce()
                allMsgs.filter { it.status == ru.cheburmail.app.db.MessageStatus.SENDING }.forEach { m ->
                    db.messageDao().updateStatus(m.id, ru.cheburmail.app.db.MessageStatus.FAILED)
                    Log.i("CheburDebug", "Fixed stuck message: ${m.id.take(8)}")
                }

                // === Auto-test: send a test message if no messages exist (Eugene only) ===
                // === Force immediate sync on startup ===
                if (config != null) {
                    Log.i("CheburDebug", "=== FORCE SYNC ===")
                    try {
                        val nonceGen = ru.cheburmail.app.crypto.NonceGenerator(ls)
                        val encryptor = ru.cheburmail.app.crypto.MessageEncryptor(ls, nonceGen)
                        val decryptor = ru.cheburmail.app.crypto.MessageDecryptor(ls)
                        val transportService = ru.cheburmail.app.transport.TransportService(
                            smtpClient = ru.cheburmail.app.transport.SmtpClient(),
                            imapClient = ru.cheburmail.app.transport.ImapClient(),
                            emailFormatter = ru.cheburmail.app.transport.EmailFormatter(),
                            emailParser = ru.cheburmail.app.transport.EmailParser(),
                            encryptor = encryptor,
                            decryptor = decryptor
                        )
                        val keyPair = keyStorage.getOrCreateKeyPair()
                        val receiveWorker = ru.cheburmail.app.transport.ReceiveWorker(
                            transportService = transportService,
                            decryptor = decryptor,
                            retryStrategy = ru.cheburmail.app.transport.RetryStrategy(),
                            messageDao = db.messageDao(),
                            contactDao = db.contactDao(),
                            chatDao = db.chatDao(),
                            notificationHelper = NotificationHelper(this@CheburMailApp),
                            recipientPrivateKey = keyPair.getPrivateKey()
                        )
                        val received = receiveWorker.pollAndProcess(config)
                        Log.i("CheburDebug", "Force sync: received $received new messages")
                    } catch (e: Exception) {
                        Log.e("CheburDebug", "Force sync error: ${e.message}", e)
                    }
                }

                // Dump state
                val contactList = db.contactDao().getAllOnce()
                Log.i("CheburDebug", "CONTACTS: ${contactList.size}")
                contactList.forEach { c ->
                    Log.i("CheburDebug", "  ${c.email} pk=${c.publicKey.toHex().take(16)}...")
                }
                val msgs = db.messageDao().getAllOnce()
                Log.i("CheburDebug", "MESSAGES: ${msgs.size}")
                msgs.forEach { m ->
                    Log.i("CheburDebug", "  [${m.id.take(8)}] out=${m.isOutgoing} status=${m.status} '${m.plaintext.take(30)}'")
                }
            } catch (e: Exception) {
                Log.e("CheburDebug", "Debug dump error: ${e.message}", e)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val hex = this.replace(" ", "")
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
