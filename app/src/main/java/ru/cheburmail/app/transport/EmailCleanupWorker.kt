package ru.cheburmail.app.transport

import android.util.Log
import java.util.Calendar
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Периодическая очистка обработанных email из папки CheburMail.
 *
 * Запускается через WorkManager (ежедневно).
 * Для каждого аккаунта:
 * 1. Подключается к IMAP
 * 2. Ищет email старше RETENTION_DAYS дней
 * 3. Удаляет найденные
 */
class EmailCleanupWorker(
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Выполнить очистку для одного аккаунта.
     *
     * @param config конфигурация email-аккаунта
     * @return количество удалённых email
     */
    fun cleanup(config: EmailConfig): Int {
        var store: Store? = null
        try {
            store = connectStore(config)

            val folder = store.getFolder(ImapClient.CHEBURMAIL_FOLDER)
            if (!folder.exists()) {
                Log.d(TAG, "Папка CheburMail не найдена для ${config.email}")
                return 0
            }

            folder.open(Folder.READ_WRITE)
            try {
                val cutoffDate = getCutoffDate()
                val oldMessages = folder.search(
                    ReceivedDateTerm(ComparisonTerm.LE, cutoffDate.time)
                )

                if (oldMessages.isEmpty()) {
                    Log.d(TAG, "Нет старых email для очистки в ${config.email}")
                    return 0
                }

                for (msg in oldMessages) {
                    msg.setFlag(Flags.Flag.DELETED, true)
                }

                folder.expunge()

                val count = oldMessages.size
                Log.i(TAG, "Очистка ${config.email}: удалено $count email старше $RETENTION_DAYS дней")
                return count

            } finally {
                if (folder.isOpen) {
                    folder.close(true)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки для ${config.email}: ${e.message}")
            return 0
        } finally {
            store?.close()
        }
    }

    /**
     * Выполнить очистку для нескольких аккаунтов.
     *
     * @param configs список конфигураций аккаунтов
     * @return общее количество удалённых email
     */
    fun cleanupAll(configs: List<EmailConfig>): Int {
        var totalDeleted = 0
        for (config in configs) {
            totalDeleted += cleanup(config)
        }
        Log.i(TAG, "Общая очистка: удалено $totalDeleted email из ${configs.size} аккаунтов")
        return totalDeleted
    }

    private fun connectStore(config: EmailConfig): Store {
        val props = Properties().apply {
            put("mail.imap.host", config.imapHost)
            put("mail.imap.port", config.imapPort.toString())
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.checkserveridentity", "true")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "15000")
        }

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(config.imapHost, config.email, config.password)
        return store
    }

    private fun getCutoffDate(): Calendar {
        val cal = Calendar.getInstance()
        cal.timeInMillis = clock()
        cal.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS)
        // Устанавливаем начало дня для корректного сравнения
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    companion object {
        private const val TAG = "EmailCleanupWorker"

        /** Хранить email не дольше 7 дней */
        const val RETENTION_DAYS = 7
    }
}
