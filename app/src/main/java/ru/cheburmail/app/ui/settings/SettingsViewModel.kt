package ru.cheburmail.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.cheburmail.app.messaging.DisappearingMessageManager
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.transport.EmailConfig

/**
 * ViewModel экрана настроек.
 * Управляет списком аккаунтов, параметрами уведомлений
 * и таймером исчезающих сообщений по умолчанию.
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
}
