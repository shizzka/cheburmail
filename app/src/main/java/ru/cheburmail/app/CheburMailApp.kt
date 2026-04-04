package ru.cheburmail.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.notification.NotificationHelper
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
            // Автогенерация ключей при первом запуске
            val ls = CryptoProvider.lazySodium
            val kpg = KeyPairGenerator(ls)
            val keyStorage = SecureKeyStorage.create(this@CheburMailApp, kpg)
            keyStorage.getOrCreateKeyPair()

            // Запуск периодической синхронизации
            syncManager.initialize()
        }
    }
}
