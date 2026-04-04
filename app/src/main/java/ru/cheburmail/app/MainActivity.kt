package ru.cheburmail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.repository.AccountRepository
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

        setContent {
            CheburMailTheme {
                AppNavigation(
                    accountRepository = accountRepository,
                    database = database,
                    keyStorage = keyStorage
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
