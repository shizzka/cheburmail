package ru.cheburmail.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NonceGeneratorTest {

    private lateinit var nonceGenerator: NonceGenerator

    @Before
    fun setUp() {
        nonceGenerator = NonceGenerator(TestCryptoProvider.lazySodium)
    }

    @Test
    fun `nonce is 24 bytes`() {
        val nonce = nonceGenerator.generate()
        assertEquals(CryptoConstants.NONCE_BYTES, nonce.size)
    }

    @Test
    fun `each call produces different nonce`() {
        val n1 = nonceGenerator.generate()
        val n2 = nonceGenerator.generate()
        assertFalse(n1.contentEquals(n2))
    }

    @Test
    fun `100 nonces are all unique`() {
        val nonces = (1..100).map { nonceGenerator.generate() }
        val unique = nonces.map { it.toList() }.toSet()
        assertEquals(100, unique.size)
    }
}
