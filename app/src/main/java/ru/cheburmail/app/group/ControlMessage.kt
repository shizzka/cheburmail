package ru.cheburmail.app.group

import org.json.JSONArray
import org.json.JSONObject

/**
 * Типы управляющих сообщений для групповых чатов.
 */
enum class ControlMessageType {
    /** Приглашение в группу с полным списком участников и их ключами */
    GROUP_INVITE,
    /** Уведомление о добавлении нового участника (broadcast от админа) */
    MEMBER_ADDED,
    /** Уведомление об удалении участника (broadcast от админа) */
    MEMBER_REMOVED,
    /**
     * Запрос verified-не-админа на добавление участника. Адресован только
     * админу группы (point-to-point). targetEmail = новый участник,
     * requesterEmail = автор запроса.
     */
    MEMBER_ADD_REQUEST,
    /**
     * Ответ админа на MEMBER_ADD_REQUEST: запрос одобрен. Доставляется только
     * автору запроса (requesterEmail), сам участник попадёт через MEMBER_ADDED.
     */
    MEMBER_ADD_APPROVED,
    /**
     * Ответ админа на MEMBER_ADD_REQUEST: запрос отклонён. Доставляется только
     * автору запроса.
     */
    MEMBER_ADD_REJECTED
}

/**
 * Информация об участнике группы в управляющем сообщении.
 *
 * @param email email участника
 * @param publicKey Base64-encoded публичный ключ
 * @param displayName отображаемое имя
 */
data class GroupMemberInfo(
    val email: String,
    val publicKey: String,
    val displayName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("email", email)
        put("publicKey", publicKey)
        put("displayName", displayName)
    }

    companion object {
        fun fromJson(json: JSONObject): GroupMemberInfo = GroupMemberInfo(
            email = json.getString("email"),
            publicKey = json.getString("publicKey"),
            displayName = json.optString("displayName", "")
        )
    }
}

/**
 * Управляющее сообщение групповых чатов.
 *
 * Сериализуется в JSON для передачи как plaintext зашифрованного сообщения.
 * Идентифицируется по subject: CM/1/<chatId>/ctrl-<uuid>
 *
 * @param type тип управляющего сообщения
 * @param chatId ID группового чата
 * @param groupName название группы
 * @param members список участников с их публичными ключами
 * @param targetEmail email участника, которого касается действие
 *   (MEMBER_ADDED/REMOVED/ADD_REQUEST/ADD_APPROVED/ADD_REJECTED)
 * @param requesterEmail email автора MEMBER_ADD_REQUEST (требуется для
 *   MEMBER_ADD_REQUEST/APPROVED/REJECTED, чтобы админ знал кому ответить
 *   и админ-ответ был привязан к исходному запросу)
 */
data class ControlMessage(
    val type: ControlMessageType,
    val chatId: String,
    val groupName: String,
    val members: List<GroupMemberInfo>,
    val targetEmail: String? = null,
    val requesterEmail: String? = null
) {

    /**
     * Сериализация в JSON-строку для передачи как plaintext.
     */
    fun toJson(): String = JSONObject().apply {
        put("type", type.name)
        put("chatId", chatId)
        put("groupName", groupName)
        put("members", JSONArray().apply {
            members.forEach { put(it.toJson()) }
        })
        targetEmail?.let { put("targetEmail", it) }
        requesterEmail?.let { put("requesterEmail", it) }
    }.toString()

    /**
     * Сериализация в байты для шифрования.
     */
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        /** Префикс UUID управляющего сообщения в subject */
        const val CTRL_PREFIX = "ctrl-"

        /**
         * Десериализация из JSON-строки.
         *
         * @param json JSON-строка
         * @return ControlMessage
         * @throws org.json.JSONException при невалидном JSON
         */
        fun fromJson(json: String): ControlMessage {
            val obj = JSONObject(json)
            val type = ControlMessageType.valueOf(obj.getString("type"))
            val chatId = obj.getString("chatId")
            val groupName = obj.getString("groupName")

            val membersArray = obj.getJSONArray("members")
            val members = (0 until membersArray.length()).map {
                GroupMemberInfo.fromJson(membersArray.getJSONObject(it))
            }

            // optString(key, null) проблематичен: возвращает Java-null,
            // Kotlin nullability теряет вывод. Делаем явно.
            val targetEmail = if (obj.has("targetEmail") && !obj.isNull("targetEmail"))
                obj.getString("targetEmail") else null
            val requesterEmail = if (obj.has("requesterEmail") && !obj.isNull("requesterEmail"))
                obj.getString("requesterEmail") else null

            return ControlMessage(
                type = type,
                chatId = chatId,
                groupName = groupName,
                members = members,
                targetEmail = targetEmail,
                requesterEmail = requesterEmail
            )
        }

        /**
         * Десериализация из байт (после расшифровки).
         */
        fun fromBytes(data: ByteArray): ControlMessage =
            fromJson(String(data, Charsets.UTF_8))

        /**
         * Проверить, является ли subject управляющим сообщением.
         * Формат: CM/1/<chatId>/ctrl-<uuid>
         */
        fun isControlSubject(subject: String): Boolean {
            val parts = subject.removePrefix("CM/1/").split("/")
            return parts.size == 2 && parts[1].startsWith(CTRL_PREFIX)
        }
    }
}
