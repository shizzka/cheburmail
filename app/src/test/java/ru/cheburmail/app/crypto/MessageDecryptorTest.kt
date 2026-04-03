package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageDecryptorTest {

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

    @Test(expected = CryptoException::class)
    fun `wrong sender public key fails decryption`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val imposter = keyPairGen.generate()

        val envelope = encryptor.encrypt(
            "secret".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        decryptor.decrypt(envelope, imposter.publicKey, recipient.getPrivateKey())
    }

    @Test(expected = CryptoException::class)
    fun `wrong recipient private key fails decryption`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()
        val wrongRecipient = keyPairGen.generate()

        val envelope = encryptor.encrypt(
            "secret".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        decryptor.decrypt(envelope, sender.publicKey, wrongRecipient.getPrivateKey())
    }

    @Test(expected = CryptoException::class)
    fun `tampered ciphertext fails decryption`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()

        val envelope = encryptor.encrypt(
            "secret".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        // Flip a byte in the ciphertext
        val tampered = envelope.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        val tamperedEnvelope = ru.cheburmail.app.crypto.model.EncryptedEnvelope(
            envelope.nonce,
            tampered
        )

        decryptor.decrypt(tamperedEnvelope, sender.publicKey, recipient.getPrivateKey())
    }

    @Test(expected = CryptoException::class)
    fun `wrong sender key size throws CryptoException`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()

        val envelope = encryptor.encrypt(
            "secret".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        decryptor.decrypt(envelope, ByteArray(16), recipient.getPrivateKey())
    }

    @Test(expected = CryptoException::class)
    fun `wrong recipient key size throws CryptoException`() {
        val sender = keyPairGen.generate()
        val recipient = keyPairGen.generate()

        val envelope = encryptor.encrypt(
            "secret".toByteArray(),
            recipient.publicKey,
            sender.getPrivateKey()
        )

        decryptor.decrypt(envelope, sender.publicKey, ByteArray(16))
    }
}
