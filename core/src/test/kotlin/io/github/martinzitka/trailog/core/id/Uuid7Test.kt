package io.github.martinzitka.trailog.core.id

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Uuid7Test {

    @Test
    fun `has version 7 and RFC 9562 variant`() {
        val u = Uuid7.generate(unixTimeMillis = 1_700_000_000_000L, random = Random(42))
        assertEquals(7, u.version())
        assertEquals(2, u.variant()) // 0b10 -> java.util.UUID reports variant 2
    }

    @Test
    fun `encodes the timestamp in the high 48 bits`() {
        val millis = 1_700_000_000_000L
        val u = Uuid7.generate(millis, Random(1))
        val recovered = u.mostSignificantBits ushr 16
        assertEquals(millis, recovered)
    }

    @Test
    fun `later timestamps sort after earlier ones lexicographically`() {
        val earlier = Uuid7.generate(1_700_000_000_000L, Random(1)).toString()
        val later = Uuid7.generate(1_700_000_001_000L, Random(1)).toString()
        assertTrue(earlier < later, "$earlier should sort before $later")
    }

    @Test
    fun `two calls at the same millisecond differ`() {
        val a = Uuid7.generate(1_700_000_000_000L, Random(1))
        val b = Uuid7.generate(1_700_000_000_000L, Random(2))
        assertTrue(a != b)
    }
}
