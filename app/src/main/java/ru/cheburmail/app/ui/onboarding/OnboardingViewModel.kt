package ru.cheburmail.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailProvider
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.EmailMessage

/**
 * Шаг мастера онбординга.
 */
enum class OnboardingStep {
    PROVIDER_SELECT,
    APP_PASSWORD_GUIDE,
    CREDENTIALS,
    CONNECTION_TEST
}

/**
 * Состояние тестирования соединения.
 */
sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data object TestingImap : ConnectionTestState()
    data object TestingSmtp : ConnectionTestState()
    data object Success : ConnectionTestState()
    data class Error(val message: String) : ConnectionTestState()
}

/**
 * ViewModel мастера онбординга.
 * Управляет шагами, валидацией и тестированием IMAP/SMTP.
 */
class OnboardingViewModel(
    private val accountRepository: AccountRepository,
    private val imapClient: ImapClient = ImapClient(),
    private val smtpClient: SmtpClient = SmtpClient()
) : ViewModel() {

    private val _currentStep = MutableStateFlow(OnboardingStep.PROVIDER_SELECT)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private val _selectedProvider = MutableStateFlow<EmailProvider?>(null)
    val selectedProvider: StateFlow<EmailProvider?> = _selectedProvider.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    fun selectProvider(provider: EmailProvider) {
        _selectedProvider.value = provider
        _currentStep.value = OnboardingStep.APP_PASSWORD_GUIDE
    }

    fun onGuideRead() {
        _currentStep.value = OnboardingStep.CREDENTIALS
    }

    fun updateEmail(value: String) {
        _email.value = value.trim()
    }

    fun updatePassword(value: String) {
        _password.value = value
    }

    fun goBack() {
        _currentStep.value = when (_currentStep.value) {
            OnboardingStep.PROVIDER_SELECT -> OnboardingStep.PROVIDER_SELECT
            OnboardingStep.APP_PASSWORD_GUIDE -> OnboardingStep.PROVIDER_SELECT
            OnboardingStep.CREDENTIALS -> OnboardingStep.APP_PASSWORD_GUIDE
            OnboardingStep.CONNECTION_TEST -> OnboardingStep.CREDENTIALS
        }
    }

    fun startConnectionTest() {
        val provider = _selectedProvider.value ?: return
        val email = _email.value
        val password = _password.value

        if (email.isBlank() || password.isBlank()) {
            _connectionTestState.value = ConnectionTestState.Error("Введите email и пароль")
            return
        }

        val config = EmailConfig(
            email = email,
            password = password,
            provider = provider
        )

        _currentStep.value = OnboardingStep.CONNECTION_TEST
        _connectionTestState.value = ConnectionTestState.TestingImap

        viewModelScope.launch {
            try {
                // Тест IMAP
                withContext(Dispatchers.IO) {
                    imapClient.ensureCheburMailFolder(config)
                }

                _connectionTestState.value = ConnectionTestState.TestingSmtp

                // Тест SMTP — отправляем тестовое письмо самому себе
                withContext(Dispatchers.IO) {
                    smtpClient.send(
                        config,
                        EmailMessage(
                            from = email,
                            to = email,
                            subject = "CM/1/test",
                            body = "CheburMail connection test".toByteArray(),
                            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
                        )
                    )
                }

                // Сохраняем аккаунт
                accountRepository.save(config)

                _connectionTestState.value = ConnectionTestState.Success
                _isComplete.value = true
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("auth", ignoreCase = true) == true ->
                        "Ошибка авторизации. Проверьте пароль приложения."
                    e.message?.contains("connect", ignoreCase = true) == true ->
                        "Не удалось подключиться к серверу. Проверьте интернет."
                    else -> "Ошибка: ${e.message ?: "неизвестная ошибка"}"
                }
                _connectionTestState.value = ConnectionTestState.Error(errorMsg)
            }
        }
    }

    fun retryTest() {
        _connectionTestState.value = ConnectionTestState.Idle
        _currentStep.value = OnboardingStep.CREDENTIALS
    }

    class Factory(
        private val accountRepository: AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnboardingViewModel(accountRepository) as T
        }
    }
}
