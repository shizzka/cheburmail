package ru.cheburmail.app.ui.settings

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
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.ImapClient

/**
 * ViewModel экрана настроек.
 * Управляет списком аккаунтов, параметрами уведомлений,
 * таймером исчезающих сообщений и очисткой IMAP папки.
 */
class SettingsViewModel(
    private val accountRepository: AccountRepository
) : ViewModel() {

    /** Список сохранённых аккаунтов */
    val accounts: StateFlow<List<EmailConfig>> = accountRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Уведомления включены */
    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    /** Звук уведомлений включён */
    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    /** Таймер исчезающих сообщений по умолчанию (null = выкл) */
    private val _defaultDisappearTimer = MutableStateFlow<Long?>(null)
    val defaultDisappearTimer: StateFlow<Long?> = _defaultDisappearTimer.asStateFlow()

    /** Сообщение об ошибке */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Статус очистки IMAP */
    private val _clearingImap = MutableStateFlow(false)
    val clearingImap: StateFlow<Boolean> = _clearingImap.asStateFlow()

    private val _imapClearResult = MutableStateFlow<String?>(null)
    val imapClearResult: StateFlow<String?> = _imapClearResult.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
    }

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
    }

    fun setDefaultDisappearTimer(durationMs: Long?) {
        _defaultDisappearTimer.value = durationMs
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
     * Удаляет все письма из папки CheburMail.
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
        private val accountRepository: AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(accountRepository) as T
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
