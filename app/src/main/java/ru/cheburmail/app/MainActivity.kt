package ru.cheburmail.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.ui.navigation.AppNavigation
import ru.cheburmail.app.ui.theme.CheburMailTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = CheburMailDatabase.getInstance(applicationContext)
        val accountRepository = AccountRepository.create(applicationContext)
        val keyStorage = SecureKeyStorage.create(
            applicationContext,
            KeyPairGenerator(CryptoProvider.lazySodium)
        )

        setContent {
            CheburMailTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    AppNavigation(
                        accountRepository = accountRepository,
                        database = database,
                        keyStorage = keyStorage
                    )
                }
            }
        }
    }
}
