package ru.cheburmail.app.ui.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

/**
 * Экран переписки.
 * LazyColumn с историей сообщений (автопрокрутка вниз), поле ввода и кнопка отправки.
 * Pull-to-refresh запускает синхронизацию.
 * Поддерживает прикрепление файлов и голосовые сообщения.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val listState = rememberLazyListState()

    // Full-screen image viewer state
    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }

    // Stop voice player when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.voicePlayer.stop() }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFilePicked(it) }
    }

    // Gallery image picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImagePicked(it) }
    }

    // Camera launcher — captures photo to prepared URI
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { viewModel.onImagePicked(it) }
        }
        pendingCameraUri = null
    }

    // RECORD_AUDIO permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    // Автопрокрутка к последнему сообщению
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
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
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                modifier = Modifier.padding(vertical = 4.dp),
                                onImageClick = { path -> fullScreenImagePath = path },
                                onSaveFile = viewModel::saveFileToDownloads,
                                voicePlayer = viewModel.voicePlayer
                            )
                        }

                        // Отступ снизу
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
                // Text entered → Send button
                text.isNotBlank() -> {
                    IconButton(onClick = onSend) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // Recording → Stop button
                isRecordingVoice -> {
                    IconButton(onClick = onStopRecording) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Остановить запись",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Idle → Mic button
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
