package ru.cheburmail.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.crypto.QrCodeParser
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.messaging.KeyExchangeManager
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.SmtpClient

/**
 * ViewModel управления контактами.
 * Обеспечивает CRUD контактов, добавление через QR, генерацию safety numbers.
 */
class ContactsViewModel(
    private val contactDao: ContactDao,
    private val keyStorage: SecureKeyStorage,
    private val accountRepository: AccountRepository? = null
) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> = contactDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedContact = MutableStateFlow<ContactEntity?>(null)
    val selectedContact: StateFlow<ContactEntity?> = _selectedContact.asStateFlow()

    private val _safetyNumber = MutableStateFlow<String?>(null)
    val safetyNumber: StateFlow<String?> = _safetyNumber.asStateFlow()

    private val _addContactError = MutableStateFlow<String?>(null)
    val addContactError: StateFlow<String?> = _addContactError.asStateFlow()

    private val _addContactSuccess = MutableStateFlow(false)
    val addContactSuccess: StateFlow<Boolean> = _addContactSuccess.asStateFlow()

    /**
     * Выбрать контакт для просмотра деталей.
     */
    fun selectContact(contact: ContactEntity) {
        _selectedContact.value = contact
        _safetyNumber.value = null

        viewModelScope.launch {
            val localKey = keyStorage.getPublicKey()
            if (localKey != null) {
                _safetyNumber.value = FingerprintGenerator.generate(
                    localKey, contact.publicKey
                )
            }
        }
    }

    fun clearSelection() {
        _selectedContact.value = null
        _safetyNumber.value = null
    }

    /**
     * Добавить контакт из отсканированного QR-кода.
     */
    fun addContactFromQr(qrContent: String) {
        _addContactError.value = null
        _addContactSuccess.value = false

        viewModelScope.launch {
            try {
                val qrData = QrCodeParser.parse(qrContent)

                // Проверяем, не добавлен ли уже
                val existing = contactDao.getByEmail(qrData.email)
                if (existing != null) {
                    _addContactError.value = "Контакт ${qrData.email} уже существует"
                    return@launch
                }

                // Получаем локальный ключ для fingerprint
                val localKey = keyStorage.getPublicKey()
                    ?: throw IllegalStateException("Локальный ключ не найден")

                val fingerprint = FingerprintGenerator.generateHex(
                    localKey, qrData.publicKey
                )

                val now = System.currentTimeMillis()
                val contact = ContactEntity(
                    email = qrData.email,
                    displayName = qrData.email.substringBefore('@'),
                    publicKey = qrData.publicKey,
                    fingerprint = fingerprint,
                    trustStatus = TrustStatus.VERIFIED, // QR = личная встреча
                    createdAt = now,
                    updatedAt = now
                )

                contactDao.insert(contact)
                _addContactSuccess.value = true
            } catch (e: QrCodeParser.QrParseException) {
                _addContactError.value = e.message ?: "Ошибка чтения QR-кода"
            } catch (e: Exception) {
                _addContactError.value = "Ошибка добавления контакта: ${e.message}"
            }
        }
    }

    /**
     * Удалить контакт.
     */
    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            contactDao.delete(contact)
            if (_selectedContact.value?.id == contact.id) {
                clearSelection()
            }
        }
    }

    /**
     * Обновить статус доверия контакта.
     */
    fun updateTrustStatus(contact: ContactEntity, status: TrustStatus) {
        viewModelScope.launch {
            val updated = contact.copy(
                trustStatus = status,
                updatedAt = System.currentTimeMillis()
            )
            contactDao.update(updated)
            if (_selectedContact.value?.id == contact.id) {
                _selectedContact.value = updated
            }
        }
    }

    /**
     * Добавить контакт по email — отправляет key exchange запрос.
     * Контакт появится после ответа второй стороны.
     */
    private val _keyExchangeSent = MutableStateFlow(false)
    val keyExchangeSent: StateFlow<Boolean> = _keyExchangeSent.asStateFlow()

    fun addContactByEmail(targetEmail: String) {
        _addContactError.value = null
        _addContactSuccess.value = false
        _keyExchangeSent.value = false

        viewModelScope.launch {
            try {
                // Проверяем, не добавлен ли уже
                val existing = contactDao.getByEmail(targetEmail)
                if (existing != null) {
                    _addContactError.value = "Контакт $targetEmail уже существует"
                    return@launch
                }

                val config = accountRepository?.getActive()
                    ?: throw IllegalStateException("Нет активного аккаунта")

                if (targetEmail == config.email) {
                    _addContactError.value = "Нельзя добавить самого себя"
                    return@launch
                }

                val keyExchangeManager = KeyExchangeManager(
                    smtpClient = SmtpClient(),
                    contactDao = contactDao,
                    keyStorage = keyStorage
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    keyExchangeManager.sendKeyExchange(config, targetEmail)
                }

                _keyExchangeSent.value = true
            } catch (e: Exception) {
                _addContactError.value = "Ошибка отправки: ${e.message}"
            }
        }
    }

    fun clearAddContactState() {
        _addContactError.value = null
        _addContactSuccess.value = false
        _keyExchangeSent.value = false
    }

    class Factory(
        private val contactDao: ContactDao,
        private val keyStorage: SecureKeyStorage,
        private val accountRepository: AccountRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContactsViewModel(contactDao, keyStorage, accountRepository) as T
        }
    }
}
