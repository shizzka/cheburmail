package ru.cheburmail.app.ui.chat

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.group.GroupManager
import ru.cheburmail.app.ui.contacts.ContactsViewModel

/**
 * Экран создания групповой беседы.
 *
 * Шаги:
 * 1. Ввести название группы
 * 2. Выбрать участников из списка контактов (до 9 + создатель)
 * 3. Нажать "Создать"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    contactsViewModel: ContactsViewModel,
    onGroupCreated: (groupName: String, memberContactIds: List<Long>) -> Unit,
    onBack: () -> Unit
) {
    val contacts by contactsViewModel.contacts.collectAsState()
    var groupName by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val maxSelectable = GroupManager.MAX_GROUP_SIZE - 1 // минус создатель

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая группа") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Название группы") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Участники (${selectedIds.size}/$maxSelectable)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    val isSelected = selectedIds.contains(contact.id)
                    ContactSelectionRow(
                        contact = contact,
                        isSelected = isSelected,
                        onToggle = {
                            if (isSelected) {
                                selectedIds.remove(contact.id)
                            } else if (selectedIds.size < maxSelectable) {
                                selectedIds.add(contact.id)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onGroupCreated(groupName.trim(), selectedIds.toList())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = groupName.isNotBlank() && selectedIds.isNotEmpty()
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Создать группу")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ContactSelectionRow(
    contact: ContactEntity,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 0.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Default.CheckCircle
            else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (isSelected) "Выбран" else "Не выбран",
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
