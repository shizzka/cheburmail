package ru.cheburmail.app.ui.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Экран экспорта/импорта ключей.
 *
 * Экспорт: пользователь вводит пароль, подтверждает, выбирает место сохранения.
 * Импорт: пользователь выбирает файл, вводит пароль, ключи восстанавливаются.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBackupScreen(
    onExport: (password: String, uri: Uri) -> Unit,
    onImport: (password: String, uri: Uri) -> Unit,
    exportResult: String?,
    importResult: String?,
    onClearResult: () -> Unit,
    onBack: () -> Unit
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var selectedExportUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImportUri by remember { mutableStateOf<Uri?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // Диалоги результата
    val resultMessage = exportResult ?: importResult
    if (resultMessage != null) {
        AlertDialog(
            onDismissRequest = onClearResult,
            title = { Text("Результат") },
            text = { Text(resultMessage) },
            confirmButton = {
                TextButton(onClick = onClearResult) {
                    Text("OK")
                }
            }
        )
    }

    // Launcher для выбора места сохранения
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            selectedExportUri = uri
            showExportDialog = true
        }
    }

    // Launcher для выбора файла импорта
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedImportUri = uri
            showImportDialog = true
        }
    }

    // Диалог ввода пароля для экспорта
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                exportPassword = ""
                exportPasswordConfirm = ""
                passwordError = null
            },
            title = { Text("Экспорт ключей") },
            text = {
                Column {
                    Text(
                        text = "Введите пароль для шифрования бэкапа. " +
                            "Этот пароль потребуется для восстановления.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = {
                            exportPassword = it
                            passwordError = null
                        },
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = {
                            exportPasswordConfirm = it
                            passwordError = null
                        },
                        label = { Text("Подтвердите пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    passwordError?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        exportPassword.length < 8 -> {
                            passwordError = "Минимум 8 символов"
                        }
                        exportPassword != exportPasswordConfirm -> {
                            passwordError = "Пароли не совпадают"
                        }
                        else -> {
                            selectedExportUri?.let { uri ->
                                onExport(exportPassword, uri)
                            }
                            showExportDialog = false
                            exportPassword = ""
                            exportPasswordConfirm = ""
                            passwordError = null
                        }
                    }
                }) {
                    Text("Экспорт")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    exportPassword = ""
                    exportPasswordConfirm = ""
                    passwordError = null
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Диалог ввода пароля для импорта
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importPassword = ""
            },
            title = { Text("Импорт ключей") },
            text = {
                Column {
                    Text(
                        text = "Введите пароль, который использовался при экспорте.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedImportUri?.let { uri ->
                        onImport(importPassword, uri)
                    }
                    showImportDialog = false
                    importPassword = ""
                }) {
                    Text("Импорт")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importPassword = ""
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Бэкап ключей") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Криптографические ключи",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Создайте зашифрованный бэкап ваших ключей для восстановления " +
                    "на другом устройстве или после переустановки приложения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Экспорт
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Экспорт",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Сохранить ключи в зашифрованный файл",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            exportLauncher.launch("cheburmail-keys.backup")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Экспортировать ключи")
                    }
                }
            }

            // Импорт
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Импорт",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Восстановить ключи из зашифрованного файла",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Импортировать ключи")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Шифрование: AES-256-GCM + PBKDF2 (100 000 итераций)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
