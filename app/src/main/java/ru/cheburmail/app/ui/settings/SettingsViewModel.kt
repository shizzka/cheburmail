package ru.cheburmail.app.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.cheburmail.app.messaging.DisappearingMessageManager
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.AppSettings
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ScheduledCleanupWorker

/**
 * ViewModel экрана настроек.
 * Управляет списком аккаунтов, параметрами уведомлений,
 * таймером исчезающих сообщений, интервалами синхронизации,
 * запретом скриншотов и очисткой IMAP папки.
 */
class SettingsViewModel(
    private val accountRepository: AccountRepository,
    private val appSettings: AppSettings,
    private val appContext: android.content.Context
) : ViewModel() {

    /** Список сохранённых аккаунтов */
    val accounts: StateFlow<List<EmailConfig>> = accountRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Уведомления включены */
    val notificationsEnabled: StateFlow<Boolean> = appSettings.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Звук уведомлений включён */
    val soundEnabled: StateFlow<Boolean> = appSettings.soundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Таймер исчезающих сообщений по умолчанию (null = выкл) */
    val defaultDisappearTimer: StateFlow<Long?> = appSettings.disappearTimerMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Интервал синхронизации в открытом чате (секунды) */
    val chatSyncIntervalSec: StateFlow<Long> = appSettings.chatSyncIntervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings.DEFAULT_CHAT_SYNC_SEC)

    /** Интервал фоновой синхронизации (минуты) */
    val backgroundSyncIntervalMin: StateFlow<Long> = appSettings.backgroundSyncIntervalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings.DEFAULT_BACKGROUND_SYNC_MIN)

    /** Запрет скриншотов */
    val screenshotsBlocked: StateFlow<Boolean> = appSettings.screenshotsBlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Автоочистка IMAP */
    val imapAutoCleanup: StateFlow<Boolean> = appSettings.imapAutoCleanup
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Сообщение об ошибке */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Статус очистки IMAP */
    private val _clearingImap = MutableStateFlow(false)
    val clearingImap: StateFlow<Boolean> = _clearingImap.asStateFlow()

    private val _imapClearResult = MutableStateFlow<String?>(null)
    val imapClearResult: StateFlow<String?> = _imapClearResult.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setNotificationsEnabled(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSoundEnabled(enabled) }
    }

    fun setDefaultDisappearTimer(durationMs: Long?) {
        viewModelScope.launch { appSettings.setDisappearTimerMs(durationMs) }
    }

    fun setChatSyncIntervalSec(sec: Long) {
        viewModelScope.launch { appSettings.setChatSyncIntervalSec(sec) }
    }

    fun setBackgroundSyncIntervalMin(min: Long) {
        viewModelScope.launch {
            appSettings.setBackgroundSyncIntervalMin(min)
            // Перепланировать WorkManager с новым интервалом
            ru.cheburmail.app.sync.PeriodicSyncWorker.schedule(appContext)
        }
    }

    fun setScreenshotsBlocked(blocked: Boolean) {
        viewModelScope.launch { appSettings.setScreenshotsBlocked(blocked) }
    }

    fun setImapAutoCleanup(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setImapAutoCleanup(enabled)
            if (enabled) {
                ScheduledCleanupWorker.schedule(appContext)
            } else {
                ScheduledCleanupWorker.cancel(appContext)
            }
        }
    }

    /**
     * Удалить аккаунт по email.
     */
    fun deleteAccount(email: String) {
        viewModelScope.launch {
            try {
                accountRepository.delete(email)
            } catch (e: Exception) {
                _errorMessage.value = "Ошибка удаления аккаунта: ${e.message}"
            }
        }
    }

    /**
     * Очистить папку CheburMail на IMAP-сервере.
     */
    fun clearImapFolder() {
        viewModelScope.launch {
            _clearingImap.value = true
            _imapClearResult.value = null
            try {
                val config = accountRepository.getActive()
                if (config == null) {
                    _errorMessage.value = "Нет активного аккаунта"
                    return@launch
                }

                val deleted = withContext(Dispatchers.IO) {
                    clearCheburMailFolder(config)
                }

                _imapClearResult.value = "Удалено $deleted писем"
                Log.i(TAG, "IMAP папка CheburMail очищена: $deleted писем")
            } catch (e: Exception) {
                _errorMessage.value = "Ошибка очистки: ${e.message}"
                Log.e(TAG, "Ошибка очистки IMAP: ${e.message}", e)
            } finally {
                _clearingImap.value = false
            }
        }
    }

    private fun clearCheburMailFolder(config: EmailConfig): Int {
        val props = java.util.Properties().apply {
            put("mail.imap.host", config.imapHost)
            put("mail.imap.port", config.imapPort.toString())
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.checkserveridentity", "true")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "15000")
        }

        val session = javax.mail.Session.getInstance(props)
        val store = session.getStore("imaps")
        try {
            store.connect(config.imapHost, config.email, config.password)

            val folder = store.getFolder(ImapClient.CHEBURMAIL_FOLDER)
            if (!folder.exists()) return 0

            folder.open(javax.mail.Folder.READ_WRITE)
            try {
                val messages = folder.messages
                val count = messages.size
                if (count > 0) {
                    for (msg in messages) {
                        msg.setFlag(javax.mail.Flags.Flag.DELETED, true)
                    }
                    folder.expunge()
                }
                return count
            } finally {
                if (folder.isOpen) folder.close(true)
            }
        } finally {
            store.close()
        }
    }

    fun clearImapResult() {
        _imapClearResult.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Получить отображаемое название таймера.
     */
    fun getTimerDisplayName(durationMs: Long?): String {
        return DisappearingMessageManager.PRESET_TIMERS.entries
            .find { it.value == durationMs }?.key
            ?: "Пользовательский"
    }

    class Factory(
        private val accountRepository: AccountRepository,
        private val appSettings: AppSettings,
        private val appContext: android.content.Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(accountRepository, appSettings, appContext) as T
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
