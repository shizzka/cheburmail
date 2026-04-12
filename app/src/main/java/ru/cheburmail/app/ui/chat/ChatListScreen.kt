package ru.cheburmail.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран списка чатов.
 * LazyColumn с превью последнего сообщения, временем и счётчиком непрочитанных.
 * Свайп влево — удаление чата с подтверждением.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onChatClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onContacts: () -> Unit,
    onQrCode: () -> Unit,
    onSettings: () -> Unit = {}
) {
    val chats by viewModel.chats.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var chatToDelete by remember { mutableStateOf<ChatWithLastMessage?>(null) }

    // Проверка обновлений
    val context = LocalContext.current
    var updateInfo by remember { mutableStateOf(UpdateChecker.getCached(context)) }

    // Проверяем при первом открытии (если кэш пустой или старый)
    LaunchedEffect(Unit) {
        val result = UpdateChecker.check(context)
        if (result != null) {
            updateInfo = result
        }
    }

    // Диалог подтверждения удаления
    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Удалить чат?") },
            text = { Text("Чат \"${chat.title ?: "Без названия"}\" и все сообщения будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(chat.chatId)
                    chatToDelete = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CheburMail") },
                actions = {
                    IconButton(onClick = onQrCode) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Мой QR-код"
                        )
                    }
                    IconButton(onClick = onContacts) {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = "Контакты"
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Default.Create,
                    contentDescription = "Новый чат"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Баннер обновления
            val update = updateInfo
            if (update != null) {
                UpdateBanner(
                    versionName = update.latestVersionName,
                    onGitHub = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.GITHUB_RELEASES_URL))
                        )
                    },
                    onBot = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.BOT_URL))
                        )
                    },
                    onDismiss = {
                        UpdateChecker.dismiss(context, update.latestVersionCode)
                        updateInfo = null
                    }
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Нет чатов",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Добавьте контакт и начните переписку",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(chats, key = { it.chatId }) { chat ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    chatToDelete = chat
                                    false
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            },
                            enableDismissFromStartToEnd = false
                        ) {
                            Column {
                                ChatRow(
                                    chat = chat,
                                    onClick = { onChatClick(chat.chatId) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        } // Column
    }
}

@Composable
private fun UpdateBanner(
    versionName: String,
    onGitHub: () -> Unit,
    onBot: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Доступно обновление $versionName",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Скрыть",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onGitHub) {
                    Text("GitHub")
                }
                OutlinedButton(onClick = onBot) {
                    Text("Telegram-бот")
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: ChatWithLastMessage,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Аватар-заглушка
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = (chat.title?.firstOrNull() ?: 'C').uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.title ?: "Чат",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                chat.lastMessageTime?.let { time ->
                    Text(
                        text = formatChatTime(time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.lastMessageText ?: "Нет сообщений",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (chat.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge {
                        Text(text = chat.unreadCount.toString())
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormat = SimpleDateFormat("dd.MM", Locale.getDefault())

private fun formatChatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneDay = 24 * 60 * 60 * 1000L

    return if (diff < oneDay) {
        timeFormat.format(Date(timestamp))
    } else {
        dateFormat.format(Date(timestamp))
    }
}
