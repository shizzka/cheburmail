package ru.cheburmail.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.cheburmail.app.storage.AccountStorage
import ru.cheburmail.app.storage.StoredAccount
import ru.cheburmail.app.storage.StoredAccountList
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailProvider

/**
 * Репозиторий email-аккаунтов.
 * Хранит учётные данные в зашифрованном DataStore (Tink AEAD).
 */
class AccountRepository(
    private val dataStore: DataStore<StoredAccountList>
) {

    /**
     * Получить все сохранённые аккаунты.
     */
    fun getAll(): Flow<List<EmailConfig>> = dataStore.data.map { stored ->
        stored.accounts.mapNotNull { it.toEmailConfig() }
    }

    /**
     * Получить первый (активный) аккаунт, или null.
     */
    suspend fun getActive(): EmailConfig? {
        val stored = dataStore.data.first()
        return stored.accounts.firstOrNull()?.toEmailConfig()
    }

    /**
     * Проверить, есть ли сохранённые аккаунты.
     */
    suspend fun hasAccounts(): Boolean {
        return dataStore.data.first().accounts.isNotEmpty()
    }

    /**
     * Observe наличия аккаунтов (для навигации онбординг/чаты).
     */
    fun observeHasAccounts(): Flow<Boolean> = dataStore.data.map { it.accounts.isNotEmpty() }

    /**
     * Сохранить новый аккаунт.
     */
    suspend fun save(config: EmailConfig) {
        dataStore.updateData { current ->
            val filtered = current.accounts.filter { it.email != config.email }
            StoredAccountList(
                accounts = filtered + StoredAccount(
                    email = config.email,
                    password = config.password,
                    provider = config.provider.name
                )
            )
        }
    }

    /**
     * Удалить аккаунт по email.
     */
    suspend fun delete(email: String) {
        dataStore.updateData { current ->
            StoredAccountList(
                accounts = current.accounts.filter { it.email != email }
            )
        }
    }

    private fun StoredAccount.toEmailConfig(): EmailConfig? {
        val provider = try {
            EmailProvider.valueOf(provider)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return EmailConfig(
            email = email,
            password = password,
            provider = provider
        )
    }

    companion object {
        fun create(context: Context): AccountRepository {
            return AccountRepository(AccountStorage.create(context))
        }
    }
}
