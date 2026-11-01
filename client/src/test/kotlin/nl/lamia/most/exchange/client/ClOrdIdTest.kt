package nl.lamia.most.exchange.client

import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every expectation is Design.md §5, "Client order ids": 1-20 of A-Z a-z 0-9 - _ + ; : . /, NUL-padded. */
class ClOrdIdTest {

    private fun bytes(vararg values: Int): UnsafeBuffer =
        UnsafeBuffer(ByteArray(ClOrdId.LENGTH)).also { b -> values.forEachIndexed { i, v -> b.putByte(i, v.toByte()) } }

    @Test
    fun `every character of the charset is allowed, and nothing else`() {
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + "-_+;:./".toList()
        for (c in 0..255) assertEquals(c.toChar() in allowed, ClOrdId.isAllowed(c), "character $c")
    }

    @Test
    fun `an id is 1 to 20 characters`() {
        assertFalse(ClOrdId.isValid(""))
        assertTrue(ClOrdId.isValid("A"))
        assertTrue(ClOrdId.isValid("a".repeat(20)))
        assertFalse(ClOrdId.isValid("a".repeat(21)))
        assertFalse(ClOrdId.isValid("has space"))
        assertFailsWith<IllegalArgumentException> { ClOrdId.of("x=y") }
    }

    @Test
    fun `on the wire every byte after the first NUL is NUL, and the first byte is not`() {
        assertTrue(ClOrdId.isValid(bytes('A'.code, 'b'.code), 0))
        assertFalse(ClOrdId.isValid(bytes(), 0), "empty")
        assertFalse(ClOrdId.isValid(bytes('A'.code, 0, 'b'.code), 0), "a byte after the padding began")
        assertFalse(ClOrdId.isValid(bytes('A'.code, ' '.code), 0), "a character outside the charset")
        assertTrue(ClOrdId.isValid(UnsafeBuffer("ABCDEFGHIJKLMNOPQRST".toByteArray()), 0), "all 20, no padding")
    }

    @Test
    fun `an id round-trips through its 20 bytes and compares by value`() {
        val buffer = UnsafeBuffer(ByteArray(24))
        val id = ClOrdId.of("ORD-2026/11.01:7+a_b")
        id.writeTo(buffer, 2)
        assertEquals(id, ClOrdId.read(buffer, 2))
        assertEquals("ORD-2026/11.01:7+a_b", ClOrdId.read(buffer, 2).toString())
        assertEquals(ClOrdId.of("ORD-2026/11.01:7+a_b").hashCode(), id.hashCode())
        assertNotEquals(ClOrdId.of("ORD-2026/11.01:7+a_c"), id)

        val short = UnsafeBuffer(ByteArray(ClOrdId.LENGTH)).apply { setMemory(0, capacity(), 'Z'.code.toByte()) }
        ClOrdId.encode("ab", short, 0)
        assertEquals(bytes('a'.code, 'b'.code).byteArray().toList(), short.byteArray().toList(), "NUL-padded")
    }

    @Test
    fun `an integer id of version 7 becomes its decimal string, and every int64 fits`() {
        val buffer = UnsafeBuffer(ByteArray(ClOrdId.LENGTH))
        for (value in longArrayOf(0L, 7L, 1_234_567_890_123L, Long.MAX_VALUE, -1L, Long.MIN_VALUE)) {
            ClOrdId.encodeDecimal(value, buffer, 0)
            assertEquals(value.toString(), ClOrdId.toString(buffer, 0))
            assertEquals(ClOrdId.of(value), ClOrdId.read(buffer, 0))
            assertTrue(ClOrdId.isValid(buffer, 0))
        }
        assertEquals(20, Long.MIN_VALUE.toString().length, "exactly the 20 characters an id holds")
    }

    @Test
    fun `a decimal id parses back, and anything else is not a number`() {
        val buffer = UnsafeBuffer(ByteArray(ClOrdId.LENGTH))
        ClOrdId.encodeDecimal(42L, buffer, 0)
        assertEquals(42L, ClOrdId.parseDecimal(buffer, 0))
        ClOrdId.encode("42a", buffer, 0)
        assertEquals(-1L, ClOrdId.parseDecimal(buffer, 0))
    }

    @Test
    fun `two ids are the same only when all 20 bytes are`() {
        val a = UnsafeBuffer(ByteArray(ClOrdId.LENGTH))
        val b = UnsafeBuffer(ByteArray(ClOrdId.LENGTH))
        ClOrdId.encode("ABCDEFGHIJKLMNOPQRS", a, 0)
        ClOrdId.encode("ABCDEFGHIJKLMNOPQRS", b, 0)
        assertTrue(ClOrdId.sameBytes(a, 0, b, 0))
        b.putByte(19, 'T'.code.toByte())
        assertFalse(ClOrdId.sameBytes(a, 0, b, 0), "the last byte counts")
    }
}
