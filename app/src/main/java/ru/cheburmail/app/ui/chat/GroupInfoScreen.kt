package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity

/**
 * Экран информации о групповом чате.
 *
 * Отображает:
 * - Название группы и аватар
 * - Количество участников
 * - (admin-only) Список pending-запросов на добавление с Approve/Reject
 * - Кнопка "Добавить/Предложить участника" (роль-зависимая)
 * - Список участников с возможностью удаления
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupName: String,
    members: List<ContactEntity>,
    isAdmin: Boolean,
    pendingRequests: List<PendingAddRequestEntity>,
    loadAvailableContacts: suspend () -> List<ContactEntity>,
    onAddOrRequestMember: (ContactEntity) -> Unit,
    onApproveRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    onRemoveMember: (ContactEntity) -> Unit,
    onBack: () -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<ContactEntity?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var pickerCandidates by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var pickerLoading by remember { mutableStateOf(false) }

    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Удалить ${target.displayName}?") },
            text = {
                Text(
                    "Участник будет удалён из группы и перестанет получать новые сообщения. " +
                    "Однако доступ к уже полученным сообщениям у него останется — " +
                    "шифрование не отзывает ключи задним числом."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveMember(target)
                    pendingRemoval = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Отмена") }
            }
        )
    }

    if (showAddPicker) {
        LaunchedEffect(Unit) {
            pickerLoading = true
            pickerCandidates = loadAvailableContacts()
            pickerLoading = false
        }
        AddMemberPickerDialog(
            candidates = pickerCandidates,
            loading = pickerLoading,
            isAdmin = isAdmin,
            onPick = { contact ->
                showAddPicker = false
                onAddOrRequestMember(contact)
            },
            onDismiss = { showAddPicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Информация о группе") },
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
        ) {
            // Заголовок группы
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Text(
                        text = "${members.size} участников" + if (isAdmin) " · вы админ" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()
            }

            // Pending-запросы (admin-only; у не-админа DAO пуст по дизайну)
            if (pendingRequests.isNotEmpty()) {
                item {
                    Text(
                        text = "Запросы на добавление",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(pendingRequests, key = { "${it.chatId}|${it.targetEmail}" }) { req ->
                    PendingRequestRow(
                        request = req,
                        onApprove = { onApproveRequest(req.targetEmail) },
                        onReject = { onRejectRequest(req.targetEmail) }
                    )
                    HorizontalDivider()
                }
            }

            // Кнопка добавления (роль-зависимая)
            item {
                TextButton(
                    onClick = { showAddPicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isAdmin) "Добавить участника" else "Предложить участника")
                }

                HorizontalDivider()
            }

            // Заголовок списка
            item {
                Text(
                    text = "Участники",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Список участников — удаление только админом
            items(members, key = { it.id }) { member ->
                MemberRow(
                    contact = member,
                    canRemove = isAdmin,
                    onRemove = { pendingRemoval = member }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PendingRequestRow(
    request: PendingAddRequestEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.targetDisplayName.ifBlank { request.targetEmail },
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "от ${request.requesterEmail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onApprove) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Одобрить",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onReject) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Отклонить",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AddMemberPickerDialog(
    candidates: List<ContactEntity>,
    loading: Boolean,
    isAdmin: Boolean,
    onPick: (ContactEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isAdmin) "Добавить участника" else "Предложить участника") },
        text = {
            when {
                loading -> Text("Загрузка...")
                candidates.isEmpty() -> Text(
                    "Нет подходящих контактов. Можно предлагать только " +
                    "verified-контактов, которых ещё нет в группе."
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = contact.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun MemberRow(
    contact: ContactEntity,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = contact.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = "Удалить участника",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
