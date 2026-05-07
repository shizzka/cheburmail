package ru.cheburmail.app.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Список изменений по версиям. Новые версии — сверху.
 * Показывается при первом запуске после обновления (см. WhatsNewGate).
 */
data class WhatsNewEntry(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val changes: List<String>
)

object WhatsNew {

    val ENTRIES: List<WhatsNewEntry> = listOf(
        WhatsNewEntry(
            versionCode = 18,
            versionName = "0.4.3",
            title = "Полировка onboarding и обновлений",
            changes = listOf(
                "«Добавить аккаунт» в Настройках корректно открывает onboarding — раньше после первого аккаунта переходил сразу в чаты",
                "Баннер «Доступно обновление» исчезает на актуальной версии (раньше мог зависнуть)",
                "Бот @my_fabrica_bot: чёткое разделение Stable/Debug с пояснениями"
            )
        ),
        WhatsNewEntry(
            versionCode = 17,
            versionName = "0.4.2",
            title = "What's new + полировка",
            changes = listOf(
                "Это окно «Что нового» — теперь будет появляться при каждом обновлении"
            )
        ),
        WhatsNewEntry(
            versionCode = 16,
            versionName = "0.4.1",
            title = "Security hardening",
            changes = listOf(
                "Авторизация на DELETE: и MEMBER_REMOVED — теперь чужой контакт не может стирать ваши сообщения или выкидывать вас из группы",
                "PIN-код теперь PBKDF2 + соль + throttling (5 неудач → блок на 5 минут)",
                "Защита от скриншотов включена по умолчанию",
                "Безопасное сохранение файлов (защита от path-injection)",
                "OutboxDrainWorker больше не теряет сообщения молча — помечает FAILED",
                "Tags релизов immutable, в release notes добавлен SHA-256 артефакта"
            )
        ),
        WhatsNewEntry(
            versionCode = 15,
            versionName = "0.4.0",
            title = "Multi-email identity и авто-fallback",
            changes = listOf(
                "Добавили поддержку нескольких email-аккаунтов одной identity. Получатель распознаёт вас по pub_key, даже если вы пишете с алиаса",
                "Авто-fallback при блокировке SMTP: если smtp.mail.ru заблокирован TSPU, отправка автоматически идёт через ваш yandex/rambler",
                "Добавлен Rambler как третий провайдер (независимая инфра)",
                "Новый экран «Диагностика сети» в Настройках — проверяет SMTP/IMAP/HTTPS ко всем провайдерам, говорит конкретный диагноз вроде «TSPU режет SMTP mail.ru»",
                "Health-индикатор у каждого аккаунта (зелёная/красная точка) + текст карантина",
                "Snackbar при fallback: «Отправлено через yandex (mail.ru заблокирован)»",
                "ContactDetail показывает все алиасы контакта",
                "QR-код несёт список ваших алиасов — собеседник узнаёт о них автоматически"
            )
        )
    )

    /**
     * Записи строго после givenVersionCode. Например, если юзер был на 14 и
     * обновился до 16 — вернёт записи для 15 и 16 (даже если он 15-ю не видел).
     */
    fun entriesAfter(versionCode: Int): List<WhatsNewEntry> =
        ENTRIES.filter { it.versionCode > versionCode }
}

@Composable
fun WhatsNewDialog(
    entries: List<WhatsNewEntry>,
    onDismiss: () -> Unit
) {
    if (entries.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что нового") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                for ((idx, entry) in entries.withIndex()) {
                    if (idx > 0) Spacer(Modifier.height(16.dp))
                    Row {
                        Text(
                            text = entry.versionName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = "  ·  ${entry.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (change in entry.changes) {
                            Text(
                                text = "•  $change",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понял") }
        }
    )
}
