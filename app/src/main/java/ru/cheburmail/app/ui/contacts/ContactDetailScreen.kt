package ru.cheburmail.app.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity

/**
 * Экран деталей контакта.
 * Показывает информацию, safety number и позволяет удалить контакт.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    viewModel: ContactsViewModel,
    onBack: () -> Unit
) {
    val contact by viewModel.selectedContact.collectAsState()
    val safetyNumber by viewModel.safetyNumber.collectAsState()
    val keyRefreshState by viewModel.keyRefreshState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showUnverifyDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentContact = contact ?: return

    // Показываем snackbar при успехе/ошибке обновления ключа
    LaunchedEffect(keyRefreshState) {
        when (val state = keyRefreshState) {
            is ContactsViewModel.KeyRefreshState.Success -> {
                snackbarHostState.showSnackbar("Ключ отправлен. Ожидайте ответ.")
                viewModel.resetKeyRefreshState()
            }
            is ContactsViewModel.KeyRefreshState.Error -> {
                snackbarHostState.showSnackbar("Ошибка: ${state.message}")
                viewModel.resetKeyRefreshState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(currentContact.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить контакт"
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Email
            InfoCard(label = "Email", value = currentContact.email)

            Spacer(modifier = Modifier.height(12.dp))

            // Статус доверия
            InfoCard(
                label = "Статус доверия",
                value = when (currentContact.trustStatus) {
                    TrustStatus.VERIFIED -> "Верифицирован"
                    TrustStatus.UNVERIFIED -> "Не верифицирован"
                    TrustStatus.BLOCKED -> "Заблокирован"
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Safety Number
            safetyNumber?.let { number ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Код безопасности",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = number,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Сравните этот код с кодом на устройстве собеседника " +
                                "для подтверждения подлинности ключей.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Ручная верификация по fingerprint (без QR)
            when (currentContact.trustStatus) {
                TrustStatus.UNVERIFIED -> {
                    Button(
                        onClick = { showVerifyDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Подтвердить вручную")
                    }
                    Text(
                        text = "Сначала сверьте код безопасности с собеседником через надёжный " +
                            "канал (видеозвонок, личная встреча). Без сверки контакт могут " +
                            "подменить — атакующий прочитает все сообщения, включая групповые.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
                TrustStatus.VERIFIED -> {
                    Button(
                        onClick = { showUnverifyDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сбросить верификацию")
                    }
                }
                TrustStatus.BLOCKED -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопка обновления ключа
            Button(
                onClick = { viewModel.refreshKey(currentContact) },
                enabled = keyRefreshState !is ContactsViewModel.KeyRefreshState.Sending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                if (keyRefreshState is ContactsViewModel.KeyRefreshState.Sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Отправка...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Обновить ключ")
                }
            }

            Text(
                text = "Переотправит ваш ключ собеседнику. Используйте после переустановки приложения у вас или у собеседника.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showVerifyDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Подтвердить контакт?") },
            text = {
                Text(
                    "Вы сравнили код безопасности с собеседником через надёжный канал " +
                        "(видеозвонок, личная встреча) и он совпал?\n\n" +
                        "После подтверждения контакт можно будет добавлять в группы. " +
                        "Если коды НЕ совпадают — не подтверждайте: канал перехвачен."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateTrustStatus(currentContact, TrustStatus.VERIFIED)
                    showVerifyDialog = false
                }) { Text("Коды совпадают, подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showUnverifyDialog) {
        AlertDialog(
            onDismissRequest = { showUnverifyDialog = false },
            title = { Text("Сбросить верификацию?") },
            text = {
                Text(
                    "Контакт станет неверифицированным. Его нельзя будет добавлять в группы " +
                        "до повторной сверки кода безопасности."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateTrustStatus(currentContact, TrustStatus.UNVERIFIED)
                    showUnverifyDialog = false
                }) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnverifyDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить контакт?") },
            text = {
                Text("Контакт ${currentContact.displayName} будет удалён. " +
                    "Вы не сможете получать от него зашифрованные сообщения.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(currentContact)
                    showDeleteDialog = false
                    onBack()
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
