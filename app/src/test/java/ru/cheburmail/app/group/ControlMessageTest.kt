package ru.cheburmail.app.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты ControlMessage: сериализация/десериализация round-trip,
 * проверка isControlSubject.
 */
class ControlMessageTest {

    @Test
    fun serialize_deserialize_groupInvite_roundTrip() {
        val members = listOf(
            GroupMemberInfo("alice@yandex.ru", "cHVibGljS2V5QWxpY2U=", "Alice"),
            GroupMemberInfo("bob@mail.ru", "cHVibGljS2V5Qm9i", "Bob")
        )

        val original = ControlMessage(
            type = ControlMessageType.GROUP_INVITE,
            chatId = "chat-123",
            groupName = "Тестовая группа",
            members = members
        )

        val json = original.toJson()
        val restored = ControlMessage.fromJson(json)

        assertEquals(original.type, restored.type)
        assertEquals(original.chatId, restored.chatId)
        assertEquals(original.groupName, restored.groupName)
        assertEquals(original.members.size, restored.members.size)
        assertEquals(original.members[0].email, restored.members[0].email)
        assertEquals(original.members[0].publicKey, restored.members[0].publicKey)
        assertEquals(original.members[0].displayName, restored.members[0].displayName)
        assertEquals(original.members[1].email, restored.members[1].email)
        assertNull(restored.targetEmail)
    }

    @Test
    fun serialize_deserialize_memberAdded_withTargetEmail() {
        val original = ControlMessage(
            type = ControlMessageType.MEMBER_ADDED,
            chatId = "chat-456",
            groupName = "Группа",
            members = listOf(
                GroupMemberInfo("carol@gmail.com", "a2V5", "Carol")
            ),
            targetEmail = "carol@gmail.com"
        )

        val json = original.toJson()
        val restored = ControlMessage.fromJson(json)

        assertEquals(ControlMessageType.MEMBER_ADDED, restored.type)
        assertEquals("carol@gmail.com", restored.targetEmail)
    }

    @Test
    fun serialize_deserialize_memberRemoved() {
        val original = ControlMessage(
            type = ControlMessageType.MEMBER_REMOVED,
            chatId = "chat-789",
            groupName = "Группа",
            members = emptyList(),
            targetEmail = "bob@mail.ru"
        )

        val restored = ControlMessage.fromJson(original.toJson())
        assertEquals(ControlMessageType.MEMBER_REMOVED, restored.type)
        assertEquals("bob@mail.ru", restored.targetEmail)
        assertTrue(restored.members.isEmpty())
    }

    @Test
    fun toBytes_fromBytes_roundTrip() {
        val original = ControlMessage(
            type = ControlMessageType.GROUP_INVITE,
            chatId = "chat-abc",
            groupName = "Bytes test",
            members = listOf(
                GroupMemberInfo("test@test.com", "key123", "Test")
            )
        )

        val bytes = original.toBytes()
        val restored = ControlMessage.fromBytes(bytes)

        assertEquals(original.type, restored.type)
        assertEquals(original.chatId, restored.chatId)
        assertEquals(original.groupName, restored.groupName)
    }

    @Test
    fun isControlSubject_validControlSubject_returnsTrue() {
        assertTrue(ControlMessage.isControlSubject("CM/1/chat-123/ctrl-550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun isControlSubject_regularMessage_returnsFalse() {
        assertFalse(ControlMessage.isControlSubject("CM/1/chat-123/550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun isControlSubject_invalidFormat_returnsFalse() {
        assertFalse(ControlMessage.isControlSubject("CM/1/chat-123"))
        assertFalse(ControlMessage.isControlSubject("Invalid subject"))
    }

    @Test
    fun serialize_deserialize_memberAddRequest_withRequesterAndTarget() {
        val original = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REQUEST,
            chatId = "chat-req",
            groupName = "Группа",
            members = listOf(
                GroupMemberInfo("dave@mail.ru", "cHVia2V5RGF2ZQ==", "Dave")
            ),
            targetEmail = "dave@mail.ru",
            requesterEmail = "alice@yandex.ru"
        )

        val restored = ControlMessage.fromJson(original.toJson())

        assertEquals(ControlMessageType.MEMBER_ADD_REQUEST, restored.type)
        assertEquals("dave@mail.ru", restored.targetEmail)
        assertEquals("alice@yandex.ru", restored.requesterEmail)
        assertEquals(1, restored.members.size)
        assertEquals("dave@mail.ru", restored.members[0].email)
    }

    @Test
    fun serialize_deserialize_memberAddApproved() {
        val original = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_APPROVED,
            chatId = "chat-app",
            groupName = "Группа",
            members = emptyList(),
            targetEmail = "dave@mail.ru",
            requesterEmail = "alice@yandex.ru"
        )

        val restored = ControlMessage.fromJson(original.toJson())

        assertEquals(ControlMessageType.MEMBER_ADD_APPROVED, restored.type)
        assertEquals("dave@mail.ru", restored.targetEmail)
        assertEquals("alice@yandex.ru", restored.requesterEmail)
    }

    @Test
    fun serialize_deserialize_memberAddRejected() {
        val original = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REJECTED,
            chatId = "chat-rej",
            groupName = "Группа",
            members = emptyList(),
            targetEmail = "dave@mail.ru",
            requesterEmail = "alice@yandex.ru"
        )

        val restored = ControlMessage.fromJson(original.toJson())

        assertEquals(ControlMessageType.MEMBER_ADD_REJECTED, restored.type)
        assertEquals("dave@mail.ru", restored.targetEmail)
        assertEquals("alice@yandex.ru", restored.requesterEmail)
    }

    @Test
    fun serialize_groupInvite_omits_requesterEmail_when_null() {
        val msg = ControlMessage(
            type = ControlMessageType.GROUP_INVITE,
            chatId = "chat-x",
            groupName = "Группа",
            members = emptyList()
        )

        val json = msg.toJson()
        assertFalse("Не должно быть requesterEmail в GROUP_INVITE", json.contains("requesterEmail"))
        assertNull(ControlMessage.fromJson(json).requesterEmail)
    }

    @Test
    fun groupMemberInfo_toJson_fromJson_roundTrip() {
        val original = GroupMemberInfo(
            email = "user@example.com",
            publicKey = "base64encodedkey==",
            displayName = "Пользователь"
        )

        val json = original.toJson()
        val restored = GroupMemberInfo.fromJson(json)

        assertEquals(original.email, restored.email)
        assertEquals(original.publicKey, restored.publicKey)
        assertEquals(original.displayName, restored.displayName)
    }
}
