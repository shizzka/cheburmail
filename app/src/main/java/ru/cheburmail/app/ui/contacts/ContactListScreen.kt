package ru.cheburmail.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity

/**
 * Экран списка контактов.
 * Отображает имя, email и статус доверия каждого контакта.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    viewModel: ContactsViewModel,
    onContactClick: (ContactEntity) -> Unit,
    onAddContact: () -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Контакты") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddContact) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Добавить контакт"
                )
            }
        }
    ) { innerPadding ->
        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Нет контактов",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Отсканируйте QR-код друга для добавления",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onClick = { onContactClick(contact) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactEntity,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrustStatusIcon(contact.trustStatus)

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
    }
}

@Composable
private fun TrustStatusIcon(status: TrustStatus) {
    val (icon, tint) = when (status) {
        TrustStatus.VERIFIED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        TrustStatus.UNVERIFIED -> Icons.Default.Help to MaterialTheme.colorScheme.onSurfaceVariant
        TrustStatus.BLOCKED -> Icons.Default.Block to MaterialTheme.colorScheme.error
    }

    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(32.dp),
        tint = tint
    )
}
