package ru.cheburmail.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
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
import ru.cheburmail.app.ui.chat.CreateGroupScreen
import ru.cheburmail.app.ui.chat.NewChatScreen
import ru.cheburmail.app.ui.contacts.AddContactScreen
import ru.cheburmail.app.ui.contacts.ContactDetailScreen
import ru.cheburmail.app.ui.contacts.ContactListScreen
import ru.cheburmail.app.ui.contacts.ContactsViewModel
import ru.cheburmail.app.ui.contacts.QrCodeScreen
import ru.cheburmail.app.ui.onboarding.OnboardingScreen
import ru.cheburmail.app.ui.onboarding.OnboardingViewModel
import ru.cheburmail.app.ui.settings.SettingsScreen
import ru.cheburmail.app.ui.settings.SettingsViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.group.GroupManager
import ru.cheburmail.app.group.GroupMessageSender
import ru.cheburmail.app.messaging.ChatIdGenerator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Маршруты навигации приложения.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatList"
    const val CHAT = "chat/{chatId}"
    const val NEW_CHAT = "newChat"
    const val CREATE_GROUP = "createGroup"
    const val GROUP_INFO = "groupInfo/{chatId}"
    const val CONTACTS = "contacts"
    const val CONTACT_DETAIL = "contactDetail"
    const val ADD_CONTACT = "addContact"
    const val QR_CODE = "qrCode"
    const val SETTINGS = "settings"

    fun chat(chatId: String) = "chat/$chatId"
    fun groupInfo(chatId: String) = "groupInfo/$chatId"
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
    initialChatId: String? = null,
    navController: NavHostController = rememberNavController()
) {
    // initial=null → до первой эмиссии DataStore не рендерим NavHost,
    // иначе startDestination=ONBOARDING проскакивает на один кадр при запуске
    // даже у залогиненного юзера (мерцание логина).
    val hasAccountsState by accountRepository.observeHasAccounts()
        .collectAsState(initial = null)

    // Кэшируем email текущего аккаунта для генерации chatId
    var myEmail by remember { mutableStateOf("") }
    LaunchedEffect(hasAccountsState) {
        if (hasAccountsState == true) {
            myEmail = accountRepository.getActive()?.email ?: ""
        }
    }

    if (hasAccountsState == null) {
        // Ждём первую эмиссию. Пустой Box — лучше, чем флеш онбординга.
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        ) {}
        return
    }

    val hasAccounts = hasAccountsState == true
    val startDestination = if (hasAccounts) Routes.CHAT_LIST else Routes.ONBOARDING

    // ViewModels — в реальном приложении будут через DI (Hilt/Koin)
    // remember обязателен: без него каждая рекомпозиция AppNavigation
    // (смена hasAccountsState, myEmail и т.д.) пересоздаёт все VM,
    // их StateFlow стартуют с пустых значений → мерцание списков и заголовков.
    val appContext = navController.context.applicationContext
    val appSettings = ru.cheburmail.app.storage.AppSettings.getInstance(appContext)
    val onboardingViewModel = remember { OnboardingViewModel(accountRepository) }
    val contactsViewModel = remember {
        ContactsViewModel(
            contactDao = database.contactDao(),
            keyStorage = keyStorage,
            accountRepository = accountRepository
        )
    }
    val chatListViewModel = remember {
        ChatListViewModel(database.chatDao(), database.messageDao(), appContext)
    }
    val settingsViewModel = remember {
        SettingsViewModel(accountRepository, appSettings, appContext)
    }

    // Deep link из уведомления — навигация в конкретный чат
    LaunchedEffect(initialChatId) {
        if (initialChatId != null && hasAccounts) {
            navController.navigate(Routes.chat(initialChatId)) {
                popUpTo(Routes.CHAT_LIST)
            }
        }
    }

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
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            // remember обязателен: без него ViewModel пересоздаётся на каждой
            // рекомпозиции (ввод символа, новое сообщение), Flow стартует с
            // emptyList → пол-секунды моргает список и заголовок.
            val chatViewModel = remember(chatId) {
                ChatViewModel(
                    chatId = chatId,
                    messageDao = database.messageDao(),
                    chatDao = database.chatDao(),
                    contactDao = database.contactDao(),
                    sendQueueDao = database.sendQueueDao(),
                    keyStorage = keyStorage,
                    appContext = navController.context.applicationContext,
                    myEmail = myEmail,
                    pendingAddRequestDao = database.pendingAddRequestDao()
                )
            }
            ChatScreen(
                viewModel = chatViewModel,
                onBack = { navController.popBackStack() },
                onOpenGroupInfo = {
                    navController.navigate(Routes.groupInfo(chatId))
                }
            )
        }

        composable(
            route = Routes.GROUP_INFO,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val chatViewModel = remember(chatId) {
                ChatViewModel(
                    chatId = chatId,
                    messageDao = database.messageDao(),
                    chatDao = database.chatDao(),
                    contactDao = database.contactDao(),
                    sendQueueDao = database.sendQueueDao(),
                    keyStorage = keyStorage,
                    appContext = navController.context.applicationContext,
                    myEmail = myEmail,
                    pendingAddRequestDao = database.pendingAddRequestDao()
                )
            }
            val title by chatViewModel.chatTitle.collectAsState()
            val members by chatViewModel.groupMembers.collectAsState()
            val isAdmin by chatViewModel.isAdmin.collectAsState()
            val pending by chatViewModel.pendingAddRequests.collectAsState()
            ru.cheburmail.app.ui.chat.GroupInfoScreen(
                groupName = title ?: "Группа",
                members = members,
                isAdmin = isAdmin,
                pendingRequests = pending,
                loadAvailableContacts = { chatViewModel.availableContactsForAdd() },
                onAddOrRequestMember = { contact ->
                    chatViewModel.addOrRequestMember(contact.id)
                },
                onApproveRequest = { targetEmail ->
                    chatViewModel.approveAddRequest(targetEmail)
                },
                onRejectRequest = { targetEmail ->
                    chatViewModel.rejectAddRequest(targetEmail)
                },
                onRemoveMember = { contact ->
                    chatViewModel.removeGroupMember(contact.id)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                contactsViewModel = contactsViewModel,
                onContactSelected = { contact ->
                    handleNewChat(contact, myEmail, database, navController)
                },
                onCreateGroup = {
                    navController.navigate(Routes.CREATE_GROUP)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CREATE_GROUP) {
            val scope = rememberCoroutineScope()
            CreateGroupScreen(
                contactsViewModel = contactsViewModel,
                onGroupCreated = { groupName, memberIds ->
                    scope.launch {
                        val privateKey = keyStorage.getPrivateKey() ?: return@launch
                        val ls = CryptoProvider.lazySodium
                        val encryptor = MessageEncryptor(ls, NonceGenerator(ls))
                        val sender = GroupMessageSender(
                            chatDao = database.chatDao(),
                            contactDao = database.contactDao(),
                            sendQueueDao = database.sendQueueDao(),
                            encryptor = encryptor,
                            senderPrivateKey = privateKey,
                            senderEmail = myEmail,
                            messageDao = database.messageDao()
                        )
                        val manager = GroupManager(
                            chatDao = database.chatDao(),
                            contactDao = database.contactDao(),
                            groupMessageSender = sender
                        )
                        // Добавляем создателя как участника через его контакт "я" —
                        // но у нас нет self-контакта; создатель определяется по myEmail
                        // на стороне получателя через members list в GROUP_INVITE
                        val chatId = manager.createGroup(groupName, memberIds, creatorEmail = myEmail)
                        val keyPair = keyStorage.getOrCreateKeyPair()
                        manager.sendGroupInvite(
                            chatId = chatId,
                            selfEmail = myEmail,
                            selfPublicKey = keyPair.publicKey,
                            selfDisplayName = myEmail.substringBefore('@')
                        )
                        ru.cheburmail.app.sync.OutboxDrainWorker.enqueue(appContext)
                        privateKey.fill(0)

                        navController.navigate(Routes.chat(chatId)) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    }
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
            var email by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val config = accountRepository.getActive()
                email = config?.email ?: ""
            }
            if (email.isNotEmpty()) {
                QrCodeScreen(
                    keyStorage = keyStorage,
                    email = email,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onAddAccount = {
                    navController.navigate(Routes.ONBOARDING)
                },
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
    myEmail: String,
    database: CheburMailDatabase,
    navController: NavHostController
) {
    // Детерминированный ID из отсортированной пары email'ов —
    // обе стороны вычислят одинаковый chatId
    val chatId = ChatIdGenerator.directChatId(myEmail, contact.email)

    // Навигируем в чат (создание ChatEntity при первом сообщении)
    navController.navigate(Routes.chat(chatId)) {
        popUpTo(Routes.CHAT_LIST)
    }
}
