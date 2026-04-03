package ru.cheburmail.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.transport.EmailProvider

/**
 * Пошаговая инструкция создания пароля приложения.
 * Отдельный контент для Яндекс и Mail.ru.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPasswordGuideScreen(
    provider: EmailProvider,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Пароль приложения") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Зачем нужен пароль приложения?",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "CheburMail использует IMAP/SMTP для отправки зашифрованных сообщений. " +
                    "Для безопасного доступа к почте нужен специальный пароль приложения, " +
                    "а не ваш основной пароль.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (provider) {
                EmailProvider.YANDEX -> YandexGuide()
                EmailProvider.MAILRU -> MailRuGuide()
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("У меня есть пароль приложения")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun YandexGuide() {
    GuideTitle("Создание пароля в Яндекс Почте")

    GuideStep(1, "Откройте id.yandex.ru и войдите в аккаунт")
    GuideStep(2, "Перейдите в раздел \"Безопасность\"")
    GuideStep(3, "Нажмите \"Пароли приложений\"")
    GuideStep(4, "Нажмите \"Создать пароль приложения\"")
    GuideStep(5, "Выберите тип \"Почта\" и введите имя (например, CheburMail)")
    GuideStep(6, "Скопируйте сгенерированный пароль")

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Важно: Если у вас не включена двухфакторная аутентификация, " +
            "сначала включите её в разделе \"Безопасность\" -> " +
            "\"Подтверждение входа\".",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MailRuGuide() {
    GuideTitle("Создание пароля в Mail.ru")

    GuideStep(1, "Откройте mail.ru и войдите в аккаунт")
    GuideStep(2, "Нажмите на имя аккаунта (правый верхний угол)")
    GuideStep(3, "Выберите \"Безопасность\" -> \"Пароли для внешних приложений\"")
    GuideStep(4, "Нажмите \"Добавить\"")
    GuideStep(5, "Введите имя приложения (например, CheburMail)")
    GuideStep(6, "Скопируйте сгенерированный пароль")

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Важно: Убедитесь, что в настройках почты разрешён доступ " +
            "по протоколам IMAP и SMTP (раздел \"Почтовые программы\").",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GuideTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Text(
        text = "$number. $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
