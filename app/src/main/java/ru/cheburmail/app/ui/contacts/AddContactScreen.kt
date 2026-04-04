package ru.cheburmail.app.ui.contacts

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Экран добавления контакта: по email (key exchange) или через QR-код.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    onScanResult: ((String) -> Unit)? = null
) {
    val error by viewModel.addContactError.collectAsState()
    val success by viewModel.addContactSuccess.collectAsState()
    val keyExchangeSent by viewModel.keyExchangeSent.collectAsState()
    val context = LocalContext.current
    var emailInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить контакт") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearAddContactState()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                success -> {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Контакт добавлен!",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ключ верифицирован при личной встрече.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {
                        viewModel.clearAddContactState()
                        onBack()
                    }) {
                        Text("Готово")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                keyExchangeSent -> {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Запрос отправлен!",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Когда собеседник откроет CheburMail, контакт добавится автоматически.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {
                        viewModel.clearAddContactState()
                        onBack()
                    }) {
                        Text("Готово")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                error != null -> {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {
                        viewModel.clearAddContactState()
                    }) {
                        Text("Попробовать снова")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                else -> {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Email input
                    Text(
                        text = "Введите email собеседника",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it.trim() },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (emailInput.contains("@") && emailInput.contains(".")) {
                                viewModel.addContactByEmail(emailInput)
                            }
                        },
                        enabled = emailInput.contains("@") && emailInput.contains("."),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Отправить запрос")
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Divider
                    Text(
                        text = "или",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // QR scanner
                    OutlinedButton(
                        onClick = {
                            launchScanner(context, viewModel, onScanResult)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Сканировать QR-код")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "QR-код подтверждает ключ при личной встрече",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun launchScanner(
    context: android.content.Context,
    viewModel: ContactsViewModel,
    onScanResult: ((String) -> Unit)?
) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()

    val scanner = GmsBarcodeScanning.getClient(context, options)
    scanner.startScan()
        .addOnSuccessListener { barcode ->
            val rawValue = barcode.rawValue
            if (rawValue != null) {
                Log.d("AddContact", "QR scanned: $rawValue")
                onScanResult?.invoke(rawValue)
                viewModel.addContactFromQr(rawValue)
            }
        }
        .addOnFailureListener { e ->
            Log.e("AddContact", "Scan failed", e)
        }
}
