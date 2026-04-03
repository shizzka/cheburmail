package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class RoundTripTest {

    private lateinit var encryptor: MessageEncryptor
    private lateinit var decryptor: MessageDecryptor
    private lateinit var keyPairGen: KeyPairGenerator

    @Before
    fun setUp() {
        val ls = TestCryptoProvider.lazySodium
        val nonceGen = NonceGenerator(ls)
        encryptor = MessageEncryptor(ls, nonceGen)
        decryptor = MessageDecryptor(ls)
        keyPairGen = KeyPairGenerator(ls)
    }

    @Test
    fun `basic round trip`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val original = "Hello, CheburMail!"

        val envelope = encryptor.encrypt(
            original.toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        val decrypted = decryptor.decrypt(
            envelope,
            sender.publicKey,
            recipient.getPrivateKey()
        )

        assertEquals(original, String(decrypted))
    }

    @Test
    fun `empty string round trip`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val original = ""

        val envelope = encryptor.encrypt(
            original.toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        val decrypted = decryptor.decrypt(
            envelope,
            sender.publicKey,
            recipient.getPrivateKey()
        )

        assertEquals(original, String(decrypted))
    }

    @Test
    fun `large message round trip`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val original = "A".repeat(100_000)

        val envelope = encryptor.encrypt(
            original.toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        val decrypted = decryptor.decrypt(
            envelope,
            sender.publicKey,
            recipient.getPrivateKey()
        )

        assertEquals(original, String(decrypted))
    }

    @Test
    fun `serialized envelope round trip`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val original = "Serialize me!"

        val envelope = encryptor.encrypt(
            original.toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        // Serialize and deserialize
        val bytes = envelope.toBytes()
        val restored = EncryptedEnvelope.fromBytes(bytes)

        val decrypted = decryptor.decrypt(
            restored,
            sender.publicKey,
            recipient.getPrivateKey()
        )

        assertEquals(original, String(decrypted))
    }

    @Test
    fun `bidirectional communication`() {
        val alice = keyPairGen.generate()
        val bob = keyPairGen.generate()

        // Alice -> Bob
        val msgToBob = "Hello Bob!"
        val envToBob = encryptor.encrypt(
            msgToBob.toByteArray(),
            bob.publicKey,
            alice.getPrivateKey()
        )
        val decryptedByBob = decryptor.decrypt(
            envToBob,
            alice.publicKey,
            bob.getPrivateKey()
        )
        assertEquals(msgToBob, String(decryptedByBob))

        // Bob -> Alice
        val msgToAlice = "Hello Alice!"
        val envToAlice = encryptor.encrypt(
            msgToAlice.toByteArray(),
            alice.publicKey,
            bob.getPrivateKey()
        )
        val decryptedByAlice = decryptor.decrypt(
            envToAlice,
            bob.publicKey,
            alice.getPrivateKey()
        )
        assertEquals(msgToAlice, String(decryptedByAlice))
    }

    @Test
    fun `multiple encryptions produce unique nonces`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val msg = "same message".toByteArray()

        val nonces = (1..50).map {
            encryptor.encrypt(msg, recipient.publicKey, sender.getPrivateKey()).nonce.toList()
        }.toSet()

        assertEquals(50, nonces.size)
    }

    @Test
    fun `unicode and emoji round trip`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val original = "Привет мир! \uD83D\uDE80\uD83C\uDF1F 日本語テスト 🔐"

        val envelope = encryptor.encrypt(
            original.toByteArray(Charsets.UTF_8),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        val decrypted = decryptor.decrypt(
            envelope,
            sender.publicKey,
            recipient.getPrivateKey()
        )

        assertEquals(original, String(decrypted, Charsets.UTF_8))
    }
}
