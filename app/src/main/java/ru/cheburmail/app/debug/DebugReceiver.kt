package ru.cheburmail.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage

/**
 * Debug-only BroadcastReceiver for ADB commands.
 *
 * Usage:
 *   adb shell am broadcast -a ru.cheburmail.app.DEBUG --es cmd dump_key
 *   adb shell am broadcast -a ru.cheburmail.app.DEBUG --es cmd add_contact --es email user@mail.ru --es pk_hex ABCDEF...
 *   adb shell am broadcast -a ru.cheburmail.app.DEBUG --es cmd dump_contacts
 *   adb shell am broadcast -a ru.cheburmail.app.DEBUG --es cmd dump_messages
 *   adb shell am broadcast -a ru.cheburmail.app.DEBUG --es cmd fix_stuck
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "Debug command: $cmd")

        CoroutineScope(Dispatchers.IO).launch {
            when (cmd) {
                "dump_key" -> dumpKey(context)
                "dump_account" -> dumpAccount(context)
                "add_contact" -> {
                    val email = intent.getStringExtra("email") ?: return@launch
                    val pkHex = intent.getStringExtra("pk_hex") ?: return@launch
                    addContact(context, email, pkHex)
                }
                "dump_contacts" -> dumpContacts(context)
                "dump_messages" -> dumpMessages(context)
                "fix_stuck" -> fixStuckMessages(context)
                "dump_all" -> {
                    dumpAccount(context)
                    dumpKey(context)
                    dumpContacts(context)
                    dumpMessages(context)
                }
                else -> Log.w(TAG, "Unknown command: $cmd")
            }
        }
    }

    private suspend fun dumpKey(context: Context) {
        val ls = CryptoProvider.lazySodium
        val kpg = KeyPairGenerator(ls)
        val keyStorage = SecureKeyStorage.create(context, kpg)
        val pk = keyStorage.getPublicKey()
        if (pk != null) {
            Log.i(TAG, "PUBLIC_KEY_HEX: ${pk.toHex()}")
            Log.i(TAG, "PUBLIC_KEY_B64: ${android.util.Base64.encodeToString(pk, android.util.Base64.NO_WRAP)}")
        } else {
            Log.w(TAG, "No key pair found")
        }
    }

    private suspend fun dumpAccount(context: Context) {
        val repo = AccountRepository.create(context)
        val config = repo.getActive()
        if (config != null) {
            Log.i(TAG, "ACCOUNT: ${config.email} provider=${config.provider}")
        } else {
            Log.w(TAG, "No active account")
        }
    }

    private suspend fun addContact(context: Context, email: String, pkHex: String) {
        val db = CheburMailDatabase.getInstance(context)
        val existing = db.contactDao().getByEmail(email)
        if (existing != null) {
            Log.w(TAG, "Contact $email already exists (id=${existing.id})")
            return
        }

        val publicKey = pkHex.hexToBytes()
        if (publicKey.size != 32) {
            Log.e(TAG, "Invalid public key size: ${publicKey.size} (expected 32)")
            return
        }

        val ls = CryptoProvider.lazySodium
        val kpg = KeyPairGenerator(ls)
        val keyStorage = SecureKeyStorage.create(context, kpg)
        val localKey = keyStorage.getPublicKey()

        val fingerprint = if (localKey != null) {
            FingerprintGenerator.generateHex(localKey, publicKey)
        } else {
            "unknown"
        }

        val now = System.currentTimeMillis()
        val contact = ContactEntity(
            email = email,
            displayName = email.substringBefore('@'),
            publicKey = publicKey,
            fingerprint = fingerprint,
            trustStatus = TrustStatus.VERIFIED,
            createdAt = now,
            updatedAt = now
        )
        db.contactDao().insert(contact)
        Log.i(TAG, "Contact added: $email (pk=${pkHex.take(16)}...)")
    }

    private suspend fun dumpContacts(context: Context) {
        val db = CheburMailDatabase.getInstance(context)
        val contacts = db.contactDao().getAllOnce()
        Log.i(TAG, "CONTACTS (${contacts.size}):")
        for (c in contacts) {
            Log.i(TAG, "  [${c.id}] ${c.email} (${c.displayName}) pk=${c.publicKey.toHex().take(16)}... trust=${c.trustStatus}")
        }
    }

    private suspend fun dumpMessages(context: Context) {
        val db = CheburMailDatabase.getInstance(context)
        val msgs = db.messageDao().getAllOnce()
        Log.i(TAG, "MESSAGES (${msgs.size}):")
        for (m in msgs) {
            Log.i(TAG, "  [${m.id.take(8)}] chat=${m.chatId.take(8)} out=${m.isOutgoing} status=${m.status} len=${m.plaintext.length}")
        }

        val queue = db.sendQueueDao().getAll()
        Log.i(TAG, "SEND_QUEUE (${queue.size}):")
        for (q in queue) {
            Log.i(TAG, "  [${q.id}] msg=${q.messageId.take(8)} to=*** status=${q.status} retry=${q.retryCount}")
        }
    }

    private suspend fun fixStuckMessages(context: Context) {
        val db = CheburMailDatabase.getInstance(context)
        val msgs = db.messageDao().getAllOnce()
        var fixed = 0
        for (m in msgs) {
            if (m.status == MessageStatus.SENDING) {
                db.messageDao().updateStatus(m.id, MessageStatus.FAILED)
                fixed++
            }
        }
        Log.i(TAG, "Fixed $fixed stuck SENDING messages -> FAILED")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val hex = this.replace(" ", "")
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val TAG = "CheburDebug"
    }
}
