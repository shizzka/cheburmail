package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryStrategyTest {

    private val strategy = RetryStrategy()

    @Test
    fun nextDelay_retry0_returns60000ms() {
        assertEquals(60_000L, strategy.nextDelay(0))
    }

    @Test
    fun nextDelay_retry1_returns120000ms() {
        assertEquals(120_000L, strategy.nextDelay(1))
    }

    @Test
    fun nextDelay_retry2_returns240000ms() {
        assertEquals(240_000L, strategy.nextDelay(2))
    }

    @Test
    fun nextDelay_retry3_returns480000ms() {
        assertEquals(480_000L, strategy.nextDelay(3))
    }

    @Test
    fun nextDelay_retry4_returns960000ms() {
        assertEquals(960_000L, strategy.nextDelay(4))
    }

    @Test
    fun nextDelay_retry5_returnsNull() {
        assertNull(strategy.nextDelay(5))
    }

    @Test
    fun nextDelay_retry6_returnsNull() {
        assertNull(strategy.nextDelay(6))
    }

    @Test
    fun canRetry_under5_returnsTrue() {
        for (i in 0..4) {
            assertTrue("canRetry($i) should be true", strategy.canRetry(i))
        }
    }

    @Test
    fun canRetry_5orMore_returnsFalse() {
        assertFalse(strategy.canRetry(5))
        assertFalse(strategy.canRetry(6))
        assertFalse(strategy.canRetry(100))
    }

    @Test
    fun exponentialProgression_allDelays() {
        val expectedSeconds = listOf(60L, 120L, 240L, 480L, 960L)
        val actualSeconds = (0..4).map { strategy.nextDelay(it)!! / 1000 }
        assertEquals(expectedSeconds, actualSeconds)
    }
}
