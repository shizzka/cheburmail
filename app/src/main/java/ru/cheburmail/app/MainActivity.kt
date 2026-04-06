package ru.cheburmail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.AppSettings
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.ui.navigation.AppNavigation
import ru.cheburmail.app.ui.theme.CheburMailTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — nothing extra to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val database = CheburMailDatabase.getInstance(applicationContext)
        val accountRepository = AccountRepository.create(applicationContext)
        val keyStorage = SecureKeyStorage.create(
            applicationContext,
            KeyPairGenerator(CryptoProvider.lazySodium)
        )

        // Наблюдаем за настройкой запрета скриншотов
        val appSettings = AppSettings.getInstance(applicationContext)
        lifecycleScope.launch {
            appSettings.screenshotsBlocked.collect { blocked ->
                if (blocked) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        // Deep link из уведомления
        val initialChatId = intent?.getStringExtra(
            ru.cheburmail.app.notification.NotificationHelper.EXTRA_CHAT_ID
        )

        setContent {
            CheburMailTheme {
                AppNavigation(
                    accountRepository = accountRepository,
                    database = database,
                    keyStorage = keyStorage,
                    initialChatId = initialChatId
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
