package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeyPairGeneratorTest {

    private lateinit var generator: KeyPairGenerator

    @Before
    fun setUp() {
        generator = KeyPairGenerator(TestCryptoProvider.lazySodium)
    }

    @Test
    fun `public key is 32 bytes`() {
        val kp = generator.generate()
        assertEquals(CryptoConstants.PUBLIC_KEY_BYTES, kp.publicKey.size)
    }

    @Test
    fun `private key is 32 bytes`() {
        val kp = generator.generate()
        assertEquals(CryptoConstants.PRIVATE_KEY_BYTES, kp.getPrivateKey().size)
    }

    @Test
    fun `each call produces different keys`() {
        val kp1 = generator.generate()
        val kp2 = generator.generate()
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
        assertFalse(kp1.getPrivateKey().contentEquals(kp2.getPrivateKey()))
    }

    @Test
    fun `keys are not all zeros`() {
        val kp = generator.generate()
        val allZeros = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES)
        assertFalse(kp.publicKey.contentEquals(allZeros))
        assertFalse(kp.getPrivateKey().contentEquals(allZeros))
    }

    @Test
    fun `wipePrivateKey zeros out private key`() {
        val kp = generator.generate()
        kp.wipePrivateKey()
        val allZeros = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES)
        assertTrue(kp.getPrivateKey().contentEquals(allZeros))
    }
}
