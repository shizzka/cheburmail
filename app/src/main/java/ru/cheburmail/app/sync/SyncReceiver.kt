package ru.cheburmail.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage

/**
 * BroadcastReceiver для обработки событий синхронизации.
 *
 * Запускается при:
 * - IMAP IDLE событии (новое письмо на сервере)
 * - Триггере от пользователя (pull-to-refresh)
 */
class SyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SYNC_NOW) {
            Log.d(TAG, "Получен запрос на синхронизацию")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    syncNow(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка синхронизации: ${e.message}")
                }
            }
        }
    }

    private suspend fun syncNow(context: Context) {
        val accountRepo = AccountRepository.create(context)
        val config = accountRepo.getActive()
        if (config == null) {
            Log.w(TAG, "Нет активного аккаунта")
            return
        }

        val ls = CryptoProvider.lazySodium
        val keyPairGenerator = KeyPairGenerator(ls)
        val keyStorage = SecureKeyStorage.create(context, keyPairGenerator)
        val keyPair = keyStorage.getOrCreateKeyPair()

        val receiveWorker = SyncFactory(context).buildReceiveWorker(
            config = config,
            privateKey = keyPair.getPrivateKey(),
            keyStorage = keyStorage
        )

        val received: Int
        try {
            received = receiveWorker.pollAndProcess(config)
        } finally {
            receiveWorker.wipePrivateKey()
        }
        Log.i(TAG, "Получено $received новых сообщений")
    }

    companion object {
        private const val TAG = "SyncReceiver"
        const val ACTION_SYNC_NOW = "ru.cheburmail.app.SYNC_NOW"

        /**
         * Создать Intent для запуска синхронизации.
         */
        fun createIntent(context: Context): Intent {
            return Intent(ACTION_SYNC_NOW).apply {
                setPackage(context.packageName)
            }
        }
    }
}
