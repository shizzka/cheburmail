package ru.cheburmail.app.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ContactEntity

@RunWith(AndroidJUnit4::class)
class ContactDaoTest {

    private lateinit var db: CheburMailDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CheburMailDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeContact(
        email: String = "alice@example.com",
        name: String = "Alice",
        trust: TrustStatus = TrustStatus.UNVERIFIED
    ) = ContactEntity(
        email = email,
        displayName = name,
        publicKey = ByteArray(32) { it.toByte() },
        fingerprint = "FP-$email",
        trustStatus = trust,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun insertAndGetByEmail() = runTest {
        val contact = makeContact()
        val id = dao.insert(contact)
        assertTrue(id > 0)

        val fetched = dao.getByEmail("alice@example.com")
        assertNotNull(fetched)
        assertEquals("alice@example.com", fetched!!.email)
        assertEquals("Alice", fetched.displayName)
        assertArrayEquals(ByteArray(32) { it.toByte() }, fetched.publicKey)
        assertEquals("FP-alice@example.com", fetched.fingerprint)
        assertEquals(TrustStatus.UNVERIFIED, fetched.trustStatus)
    }

    @Test
    fun getById() = runTest {
        val id = dao.insert(makeContact())
        val fetched = dao.getById(id)
        assertNotNull(fetched)
        assertEquals(id, fetched!!.id)
    }

    @Test
    fun getByEmail_notFound_returnsNull() = runTest {
        val fetched = dao.getByEmail("nonexistent@example.com")
        assertNull(fetched)
    }

    @Test
    fun getAll_returnsSortedByName() = runTest {
        dao.insert(makeContact(email = "zara@test.com", name = "Zara"))
        dao.insert(makeContact(email = "alice@test.com", name = "Alice"))
        dao.insert(makeContact(email = "mike@test.com", name = "Mike"))

        val all = dao.getAll().first()
        assertEquals(3, all.size)
        assertEquals("Alice", all[0].displayName)
        assertEquals("Mike", all[1].displayName)
        assertEquals("Zara", all[2].displayName)
    }

    @Test
    fun update_changesTrustStatus() = runTest {
        val id = dao.insert(makeContact())
        val original = dao.getById(id)!!

        dao.update(original.copy(trustStatus = TrustStatus.VERIFIED, updatedAt = System.currentTimeMillis()))

        val updated = dao.getById(id)!!
        assertEquals(TrustStatus.VERIFIED, updated.trustStatus)
    }

    @Test
    fun delete_removesContact() = runTest {
        val id = dao.insert(makeContact())
        assertNotNull(dao.getById(id))

        dao.deleteById(id)
        assertNull(dao.getById(id))
    }

    @Test
    fun insert_duplicateEmail_throws() = runTest {
        dao.insert(makeContact(email = "dup@test.com"))
        try {
            dao.insert(makeContact(email = "dup@test.com", name = "Other"))
            fail("Ожидалось исключение при дублировании email")
        } catch (_: Exception) {
            // Ожидаемое поведение — unique constraint
        }
    }
}
