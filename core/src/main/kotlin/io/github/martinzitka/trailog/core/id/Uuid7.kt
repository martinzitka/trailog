package io.github.martinzitka.trailog.core.id

import java.util.UUID
import kotlin.random.Random

/**
 * Client-generated UUIDv7 identifiers. Activities and points are created entirely offline
 * (CLAUDE.md: local-first), so the phone mints IDs and the server never does. UUIDv7 is
 * time-ordered, which keeps database indexes on `id` well-clustered.
 *
 * Layout (RFC 9562): 48-bit big-endian Unix millisecond timestamp, 4-bit version (7),
 * 12 bits of randomness, 2-bit variant (0b10), then 62 bits of randomness.
 *
 * The time source and randomness are injected so the generator is deterministic under test.
 */
object Uuid7 {

    fun generate(unixTimeMillis: Long, random: Random = Random.Default): UUID {
        require(unixTimeMillis >= 0) { "timestamp must be non-negative" }

        val ts = unixTimeMillis and 0xFFFF_FFFF_FFFFL // low 48 bits

        // most significant 64 bits: 48-bit timestamp | version | 12 random bits
        var msb = ts shl 16
        msb = msb or (0x7L shl 12)                    // version 7
        msb = msb or (random.nextInt(0x1000).toLong()) // rand_a (12 bits)

        // least significant 64 bits: variant (0b10) | 62 random bits
        var lsb = random.nextLong()
        lsb = lsb and 0x3FFF_FFFF_FFFF_FFFFL          // clear top 2 bits
        lsb = lsb or (0x2L shl 62)                    // variant 0b10

        return UUID(msb, lsb)
    }
}
