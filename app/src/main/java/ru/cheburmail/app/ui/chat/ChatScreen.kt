package ru.cheburmail.app.ui.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ru.cheburmail.app.db.entity.MessageEntity

/**
 * Экран переписки.
 * LazyColumn с историей сообщений (автопрокрутка вниз), поле ввода и кнопка отправки.
 * Pull-to-refresh запускает синхронизацию.
 * Поддерживает прикрепление файлов, голосовые сообщения, ответ/цитату, удаление.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val chatTitle by viewModel.chatTitle.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsState()
    val isSendingMedia by viewModel.isSendingMedia.collectAsState()
    val sendingMediaLabel by viewModel.sendingMediaLabel.collectAsState()
    val replyTo by viewModel.replyTo.collectAsState()
    val listState = rememberLazyListState()

    // Full-screen image viewer state
    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }

    // Rename dialog
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // Message context menu
    var contextMenuMessage by remember { mutableStateOf<MessageEntity?>(null) }

    // Stop voice player when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.voicePlayer.stop() }
    }

    // Launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImagePicked(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFilePicked(it) }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { viewModel.onImagePicked(it) }
        }
        pendingCameraUri = null
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    // С reverseLayout=true последнее сообщение (index 0) уже внизу.
    // Прокручиваем к 0 только при получении нового сообщения,
    // если пользователь и так находится внизу.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("Название чата") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameChat(renameText)
                    showRenameDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Отмена") }
            }
        )
    }

    // Message context menu dialog
    contextMenuMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { contextMenuMessage = null },
            title = { Text("Сообщение") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.setReplyTo(msg)
                            contextMenuMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ответить")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteMessageLocally(msg.id)
                            contextMenuMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Удалить у себя", color = MaterialTheme.colorScheme.error) }
                    if (msg.isOutgoing) {
                        TextButton(
                            onClick = {
                                viewModel.deleteMessageForEveryone(msg.id)
                                contextMenuMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Удалить у всех", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { contextMenuMessage = null }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatTitle ?: "Чат") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        renameText = chatTitle ?: ""
                        showRenameDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Переименовать"
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
                .imePadding()
        ) {
            // Список сообщений с pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { contextMenuMessage = message }
                            )
                        ) {
                            MessageBubble(
                                message = message,
                                modifier = Modifier.padding(vertical = 4.dp),
                                onImageClick = { path -> fullScreenImagePath = path },
                                onSaveFile = viewModel::saveFileToDownloads,
                                voicePlayer = viewModel.voicePlayer
                            )
                        }
                    }

                    // Отступ сверху (в reverseLayout это визуально сверху списка)
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Индикатор прогресса отправки медиафайла
            SendProgressIndicator(
                visible = isSendingMedia,
                label = sendingMediaLabel
            )

            // Reply preview
            replyTo?.let { reply ->
                ReplyPreview(
                    message = reply,
                    onCancel = { viewModel.setReplyTo(null) }
                )
            }

            // Поле ввода
            MessageInput(
                text = inputText,
                onTextChange = viewModel::updateInputText,
                onSend = viewModel::sendMessage,
                isRecordingVoice = isRecordingVoice,
                onStartRecording = {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopRecording = viewModel::stopVoiceRecordingAndSend,
                onCancelRecording = viewModel::cancelVoiceRecording,
                onFilePickerOpen = { filePickerLauncher.launch(arrayOf("*/*")) },
                onGalleryOpen = {
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onCameraOpen = {
                    val uri = viewModel.prepareCameraUri()
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }
            )
        }

        // Перехватываем Back при открытом фото — закрываем просмотр, а не уходим из чата
        BackHandler(enabled = fullScreenImagePath != null) {
            fullScreenImagePath = null
        }

        // Полноэкранный просмотр изображения (overlay)
        val imgPath = fullScreenImagePath
        if (imgPath != null) {
            FullScreenImageViewer(
                imagePath = imgPath,
                onClose = { fullScreenImagePath = null },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            )
        }
    }
}

@Composable
private fun ReplyPreview(
    message: MessageEntity,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isOutgoing) "Вы" else "Собеседник",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                val preview = message.plaintext.ifEmpty {
                    when (message.mediaType) {
                        ru.cheburmail.app.db.MediaType.IMAGE -> "[Фото]"
                        ru.cheburmail.app.db.MediaType.FILE -> "[Файл] ${message.fileName ?: ""}"
                        ru.cheburmail.app.db.MediaType.VOICE -> "[Голосовое]"
                        else -> ""
                    }
                }
                Text(
                    text = preview.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Отменить ответ",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isRecordingVoice: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onFilePickerOpen: () -> Unit,
    onGalleryOpen: () -> Unit = {},
    onCameraOpen: () -> Unit = {}
) {
    var showAttachMenu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attach button with dropdown menu
            Box {
                IconButton(onClick = { showAttachMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Прикрепить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Галерея") },
                        leadingIcon = {
                            Icon(Icons.Filled.Image, contentDescription = null)
                        },
                        onClick = {
                            showAttachMenu = false
                            onGalleryOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Камера") },
                        leadingIcon = {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        },
                        onClick = {
                            showAttachMenu = false
                            onCameraOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Файл") },
                        leadingIcon = {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                        },
                        onClick = {
                            showAttachMenu = false
                            onFilePickerOpen()
                        }
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isRecordingVoice) "Запись..." else "Сообщение..."
                    )
                },
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                enabled = !isRecordingVoice
            )

            Spacer(modifier = Modifier.width(8.dp))

            when {
                // Text entered -> Send button
                text.isNotBlank() -> {
                    IconButton(onClick = onSend) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // Recording -> Stop button
                isRecordingVoice -> {
                    IconButton(onClick = onStopRecording) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Остановить запись",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Idle -> Mic button
                else -> {
                    IconButton(onClick = onStartRecording) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Записать голосовое",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
