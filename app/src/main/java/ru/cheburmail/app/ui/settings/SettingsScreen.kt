package ru.cheburmail.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.cheburmail.app.messaging.DisappearingMessageManager
import ru.cheburmail.app.storage.AppSettings
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.update.UpdateChecker

/**
 * Экран настроек приложения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddAccount: () -> Unit,
    onBack: () -> Unit
) {
    val accounts by viewModel.accounts.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val defaultTimer by viewModel.defaultDisappearTimer.collectAsState()
    val chatSyncSec by viewModel.chatSyncIntervalSec.collectAsState()
    val bgSyncMin by viewModel.backgroundSyncIntervalMin.collectAsState()
    val screenshotsBlocked by viewModel.screenshotsBlocked.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val clearingImap by viewModel.clearingImap.collectAsState()
    val imapClearResult by viewModel.imapClearResult.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showClearImapDialog by remember { mutableStateOf(false) }

    // Диалог ошибки
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Ошибка") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            }
        )
    }

    // Диалог удаления аккаунта
    showDeleteDialog?.let { email ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Аккаунт $email будет удалён из приложения. Почтовый ящик останется.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(email)
                    showDeleteDialog = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Диалог очистки IMAP папки
    if (showClearImapDialog) {
        AlertDialog(
            onDismissRequest = { showClearImapDialog = false },
            title = { Text("Очистить почту?") },
            text = { Text("Все письма CheburMail будут удалены с почтового сервера. Локальные сообщения останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearImapFolder()
                    showClearImapDialog = false
                }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearImapDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Результат очистки
    imapClearResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImapResult() },
            title = { Text("Готово") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImapResult() }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // --- Секция: Аккаунты ---
            item {
                SectionHeader("Аккаунты")
            }

            items(accounts, key = { it.email }) { account ->
                AccountRow(
                    account = account,
                    onDelete = { showDeleteDialog = account.email }
                )
            }

            item {
                TextButton(
                    onClick = onAddAccount,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Добавить аккаунт")
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: Синхронизация ---
            item {
                SectionHeader("Синхронизация")
            }

            item {
                DropdownRow(
                    title = "В открытом чате",
                    subtitle = "Как часто проверять новые сообщения",
                    currentValue = chatSyncSec,
                    options = AppSettings.CHAT_SYNC_OPTIONS,
                    formatOption = { "${it} сек" },
                    onSelected = { viewModel.setChatSyncIntervalSec(it) }
                )
            }

            item {
                DropdownRow(
                    title = "Фоновая синхронизация",
                    subtitle = "Интервал при свёрнутом приложении",
                    currentValue = bgSyncMin,
                    options = AppSettings.BACKGROUND_SYNC_OPTIONS,
                    formatOption = { "${it} мин" },
                    onSelected = { viewModel.setBackgroundSyncIntervalMin(it) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: Приватность ---
            item {
                SectionHeader("Приватность")
            }

            item {
                SwitchRow(
                    title = "Запрет скриншотов",
                    subtitle = "Блокировать снимки экрана и запись",
                    checked = screenshotsBlocked,
                    onCheckedChange = { viewModel.setScreenshotsBlocked(it) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: Уведомления ---
            item {
                SectionHeader("Уведомления")
            }

            item {
                SwitchRow(
                    title = "Уведомления",
                    subtitle = "Показывать уведомления о новых сообщениях",
                    checked = notificationsEnabled,
                    onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                )
            }

            item {
                SwitchRow(
                    title = "Звук",
                    subtitle = "Воспроизводить звук при новом сообщении",
                    checked = soundEnabled && notificationsEnabled,
                    enabled = notificationsEnabled,
                    onCheckedChange = { viewModel.setSoundEnabled(it) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: Исчезающие сообщения ---
            item {
                SectionHeader("Исчезающие сообщения")
            }

            item {
                DisappearTimerSelector(
                    currentTimer = defaultTimer,
                    displayName = viewModel.getTimerDisplayName(defaultTimer),
                    onTimerSelected = { viewModel.setDefaultDisappearTimer(it) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: Данные ---
            item {
                SectionHeader("Данные")
            }

            item {
                Button(
                    onClick = { showClearImapDialog = true },
                    enabled = !clearingImap,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (clearingImap) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Очистка...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Очистить папку CheburMail на сервере")
                    }
                }
            }

            item {
                Text(
                    text = "Удаляет все письма CheburMail с почтового сервера",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // --- Секция: О приложении ---
            item {
                SectionHeader("О приложении")
            }

            item {
                val ctx = LocalContext.current
                val pkgInfo = remember {
                    try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    } catch (_: Exception) { null }
                }
                val versionName = pkgInfo?.versionName ?: "?"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pkgInfo?.longVersionCode?.toString() ?: "?"
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo?.versionCode?.toString() ?: "?"
                }
                InfoRow(title = "Версия", value = "$versionName ($versionCode)")
            }

            item {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var checking by remember { mutableStateOf(false) }
                var result by remember { mutableStateOf<String?>(null) }

                Column {
                    Button(
                        onClick = {
                            checking = true
                            result = null
                            scope.launch {
                                val update = UpdateChecker.check(ctx)
                                checking = false
                                result = if (update != null) {
                                    "Доступно обновление ${update.latestVersionName}"
                                } else {
                                    "У вас последняя версия"
                                }
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Проверка...")
                        } else {
                            Text("Проверить обновления")
                        }
                    }

                    result?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (text.contains("Доступно"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item {
                InfoRow(title = "Шифрование", value = "X25519 + XSalsa20-Poly1305 (NaCl)")
            }

            item {
                InfoRow(title = "Обмен ключами", value = "QR-код / email (crypto_box)")
            }

            item {
                InfoRow(title = "Транспорт", value = "Email (IMAP/SMTP)")
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun AccountRow(
    account: EmailConfig,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = account.provider.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить аккаунт",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    subtitle: String,
    currentValue: T,
    options: List<T>,
    formatOption: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = formatOption(currentValue),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatOption(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DisappearTimerSelector(
    currentTimer: Long?,
    displayName: String,
    onTimerSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Таймер по умолчанию",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Для новых чатов",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DisappearingMessageManager.PRESET_TIMERS.forEach { (name, duration) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onTimerSelected(duration)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
