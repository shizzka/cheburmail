package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageEncryptorTest {

    private lateinit var encryptor: MessageEncryptor
    private lateinit var keyPairGen: KeyPairGenerator
    private lateinit var nonceGen: NonceGenerator

    @Before
    fun setUp() {
        val ls = TestCryptoProvider.lazySodium
        nonceGen = NonceGenerator(ls)
        encryptor = MessageEncryptor(ls, nonceGen)
        keyPairGen = KeyPairGenerator(ls)
    }

    @Test
    fun `envelope nonce is 24 bytes`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val envelope = encryptor.encrypt(
            "hello".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )
        assertEquals(CryptoConstants.NONCE_BYTES, envelope.nonce.size)
    }

    @Test
    fun `ciphertext size equals plaintext plus MAC`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val plaintext = "test message".toByteArray()
        val envelope = encryptor.encrypt(
            plaintext,
            recipient.publicKey,
            sender.getPrivateKey()
        )
        assertEquals(plaintext.size + CryptoConstants.MAC_BYTES, envelope.ciphertext.size)
    }

    @Test
    fun `each encryption produces unique nonce`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val msg = "same message".toByteArray()
        val e1 = encryptor.encrypt(msg, recipient.publicKey, sender.getPrivateKey())
        val e2 = encryptor.encrypt(msg, recipient.publicKey, sender.getPrivateKey())
        assertFalse(e1.nonce.contentEquals(e2.nonce))
    }

    @Test
    fun `same message produces different ciphertext`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val msg = "same message".toByteArray()
        val e1 = encryptor.encrypt(msg, recipient.publicKey, sender.getPrivateKey())
        val e2 = encryptor.encrypt(msg, recipient.publicKey, sender.getPrivateKey())
        assertFalse(e1.ciphertext.contentEquals(e2.ciphertext))
    }

    @Test(expected = CryptoException::class)
    fun `wrong recipient key size throws CryptoException`() {
        val sender = keyPairGen.generate()
        encryptor.encrypt(
            "hello".toByteArray(),
            ByteArray(16), // wrong size
            sender.getPrivateKey()
        )
    }

    @Test(expected = CryptoException::class)
    fun `wrong sender key size throws CryptoException`() {
        val recipient = keyPairGen.generate()
        encryptor.encrypt(
            "hello".toByteArray(),
            recipient.publicKey,
            ByteArray(16) // wrong size
        )
    }
}
