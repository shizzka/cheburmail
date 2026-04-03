package ru.cheburmail.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.ui.chat.ChatListScreen
import ru.cheburmail.app.ui.chat.ChatListViewModel
import ru.cheburmail.app.ui.chat.ChatScreen
import ru.cheburmail.app.ui.chat.ChatViewModel
import ru.cheburmail.app.ui.chat.NewChatScreen
import ru.cheburmail.app.ui.contacts.AddContactScreen
import ru.cheburmail.app.ui.contacts.ContactDetailScreen
import ru.cheburmail.app.ui.contacts.ContactListScreen
import ru.cheburmail.app.ui.contacts.ContactsViewModel
import ru.cheburmail.app.ui.contacts.QrCodeScreen
import ru.cheburmail.app.ui.onboarding.OnboardingScreen
import ru.cheburmail.app.ui.onboarding.OnboardingViewModel
import java.util.UUID

/**
 * Маршруты навигации приложения.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatList"
    const val CHAT = "chat/{chatId}"
    const val NEW_CHAT = "newChat"
    const val CONTACTS = "contacts"
    const val CONTACT_DETAIL = "contactDetail"
    const val ADD_CONTACT = "addContact"
    const val QR_CODE = "qrCode"

    fun chat(chatId: String) = "chat/$chatId"
}

/**
 * Корневой NavHost приложения.
 *
 * Стартовый экран зависит от наличия сохранённого аккаунта:
 * - Нет аккаунта -> онбординг
 * - Есть аккаунт -> список чатов
 */
@Composable
fun AppNavigation(
    accountRepository: AccountRepository,
    database: CheburMailDatabase,
    keyStorage: SecureKeyStorage,
    navController: NavHostController = rememberNavController()
) {
    val hasAccounts by accountRepository.observeHasAccounts()
        .collectAsState(initial = false)

    val startDestination = if (hasAccounts) Routes.CHAT_LIST else Routes.ONBOARDING

    // ViewModels — в реальном приложении будут через DI (Hilt/Koin)
    val onboardingViewModel = OnboardingViewModel(accountRepository)
    val contactsViewModel = ContactsViewModel(
        contactDao = database.contactDao(),
        keyStorage = keyStorage
    )
    val chatListViewModel = ChatListViewModel(database.chatDao())

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onComplete = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                viewModel = chatListViewModel,
                onChatClick = { chatId ->
                    navController.navigate(Routes.chat(chatId))
                },
                onNewChat = {
                    navController.navigate(Routes.NEW_CHAT)
                },
                onContacts = {
                    navController.navigate(Routes.CONTACTS)
                },
                onQrCode = {
                    navController.navigate(Routes.QR_CODE)
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val chatViewModel = ChatViewModel(
                chatId = chatId,
                messageDao = database.messageDao(),
                chatDao = database.chatDao()
            )
            ChatScreen(
                viewModel = chatViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                contactsViewModel = contactsViewModel,
                onContactSelected = { contact ->
                    handleNewChat(contact, database, navController)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CONTACTS) {
            ContactListScreen(
                viewModel = contactsViewModel,
                onContactClick = { contact ->
                    contactsViewModel.selectContact(contact)
                    navController.navigate(Routes.CONTACT_DETAIL)
                },
                onAddContact = {
                    navController.navigate(Routes.ADD_CONTACT)
                }
            )
        }

        composable(Routes.CONTACT_DETAIL) {
            ContactDetailScreen(
                viewModel = contactsViewModel,
                onBack = {
                    contactsViewModel.clearSelection()
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.ADD_CONTACT) {
            AddContactScreen(
                viewModel = contactsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.QR_CODE) {
            // email берём из первого сохранённого аккаунта
            val email = "user@example.com" // TODO: получать из AccountRepository
            QrCodeScreen(
                keyStorage = keyStorage,
                email = email,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Создать Direct-чат с контактом или перейти в существующий.
 */
private fun handleNewChat(
    contact: ContactEntity,
    database: CheburMailDatabase,
    navController: NavHostController
) {
    // Генерируем детерминированный ID чата из email контакта
    val chatId = UUID.nameUUIDFromBytes(
        "direct:${contact.email}".toByteArray()
    ).toString()

    // Навигируем в чат (создание ChatEntity при первом сообщении)
    navController.navigate(Routes.chat(chatId)) {
        popUpTo(Routes.CHAT_LIST)
    }
}
