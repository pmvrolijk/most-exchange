package nl.lamia.most.exchange.client

import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import java.nio.ByteOrder

/**
 * A participant's own order id (Design.md §5, "Client order ids"): 1 to 20 characters of
 * `A-Z a-z 0-9 - _ + ; : . /`, carried as `char[20]` and NUL-padded. This is the one implementation of
 * that rule. The gateway refuses an id that fails [isValid], the CLI and every adapter build theirs
 * with [of], and the engine holds the bytes without looking at them.
 *
 * Twenty bytes are three little-endian words: 8, 8 and the last 4. That is how the engine stores an
 * id (`ColdField`), and how two ids compare, so a value here is those three words and nothing else.
 * Immutable and compared by value, so it can key a map. A sender on a hot path can skip the object
 * and use the buffer functions: [isValid], [encode], [encodeDecimal], [copy] and [sameBytes].
 */
class ClOrdId private constructor(val word0: Long, val word1: Long, val word2: Long) {

    /** Writes the 20 bytes at [offset]. */
    fun writeTo(buffer: MutableDirectBuffer, offset: Int) = writeWords(buffer, offset, word0, word1, word2)

    override fun equals(other: Any?): Boolean =
        other is ClOrdId && other.word0 == word0 && other.word1 == word1 && other.word2 == word2

    override fun hashCode(): Int {
        var h = word0 xor (word0 ushr 32)
        h = h * 31 + (word1 xor (word1 ushr 32))
        h = h * 31 + word2
        return h.toInt()
    }

    /** The id as the participant sent it, without the padding. */
    override fun toString(): String {
        val chars = StringBuilder(LENGTH)
        for (i in 0..<LENGTH) {
            val b = byteAt(i)
            if (b == 0) break
            chars.append(b.toChar())
        }
        return chars.toString()
    }

    private fun byteAt(i: Int): Int {
        val word = when {
            i < 8 -> word0
            i < 16 -> word1
            else -> word2
        }
        return ((word ushr ((i and 7) * 8)) and 0xFF).toInt()
    }

    companion object {
        /** The bytes an id occupies on the wire, padding included. */
        const val LENGTH = 20

        /**
         * The first schema version whose orders and cancels carry this id. Version 8 changed their
         * layout in place, so an earlier one cannot be read as version 8 (Design.md §5).
         */
        const val SINCE_SCHEMA_VERSION = 8

        private const val ALLOWED_PUNCTUATION = "-_+;:./"

        /** The id [text] names. Throws [IllegalArgumentException] when it is not a valid id. */
        fun of(text: CharSequence): ClOrdId {
            require(isValid(text)) { "not a valid clOrdId (1-20 of A-Z a-z 0-9 $ALLOWED_PUNCTUATION): \"$text\"" }
            var w0 = 0L
            var w1 = 0L
            var w2 = 0L
            for (i in text.indices) {
                val b = text[i].code.toLong() shl ((i and 7) * 8)
                when {
                    i < 8 -> w0 = w0 or b
                    i < 16 -> w1 = w1 or b
                    else -> w2 = w2 or b
                }
            }
            return ClOrdId(w0, w1, w2)
        }

        /** [value] as its decimal string: how an integer id of version 7 is carried (Design.md §5). */
        fun of(value: Long): ClOrdId = of(value.toString())

        /** The id at [offset], as it is, valid or not. */
        fun read(buffer: DirectBuffer, offset: Int): ClOrdId =
            ClOrdId(word0(buffer, offset), word1(buffer, offset), word2(buffer, offset))

        fun isAllowed(c: Int): Boolean =
            c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                ALLOWED_PUNCTUATION.indexOf(c.toChar()) >= 0

        fun isValid(text: CharSequence): Boolean {
            if (text.isEmpty() || text.length > LENGTH) return false
            for (i in text.indices) if (!isAllowed(text[i].code)) return false
            return true
        }

        /**
         * Whether the 20 bytes at [offset] are a valid id: allowed characters from the first byte,
         * then nothing but NUL after the first NUL. Allocation-free, for the gateway.
         */
        fun isValid(buffer: DirectBuffer, offset: Int): Boolean {
            var length = 0
            while (length < LENGTH && buffer.getByte(offset + length).toInt() != 0) {
                if (!isAllowed(buffer.getByte(offset + length).toInt() and 0xFF)) return false
                length++
            }
            if (length == 0) return false
            for (i in length..<LENGTH) if (buffer.getByte(offset + i).toInt() != 0) return false
            return true
        }

        /** Writes [text] at [offset], NUL-padded. Allocation-free. Throws when it is not a valid id. */
        fun encode(text: CharSequence, buffer: MutableDirectBuffer, offset: Int) {
            require(isValid(text)) { "not a valid clOrdId (1-20 of A-Z a-z 0-9 $ALLOWED_PUNCTUATION): \"$text\"" }
            for (i in 0..<LENGTH) buffer.putByte(offset + i, if (i < text.length) text[i].code.toByte() else 0)
        }

        /**
         * Writes [value] in decimal at [offset], NUL-padded. Allocation-free: for a sender that numbers
         * its own orders (`most load`), and for a restore turning a version 7 id into its string.
         * Every `int64` fits, `Long.MIN_VALUE` in exactly 20 characters.
         */
        fun encodeDecimal(value: Long, buffer: MutableDirectBuffer, offset: Int) {
            var digits = 0
            var v = value
            do {
                digits++
                v /= 10
            } while (v != 0L)
            val negative = value < 0L
            val length = digits + if (negative) 1 else 0
            if (negative) buffer.putByte(offset, '-'.code.toByte())
            v = value
            for (i in length - 1 downTo (if (negative) 1 else 0)) {
                // Negative remainders for a negative value, so Long.MIN_VALUE never overflows.
                val digit = Math.abs((v % 10).toInt())
                buffer.putByte(offset + i, ('0'.code + digit).toByte())
                v /= 10
            }
            for (i in length..<LENGTH) buffer.putByte(offset + i, 0)
        }

        /**
         * The non-negative decimal number at [offset], or -1 when the id is not one: what
         * [encodeDecimal] wrote for a value of 0 or more. Allocation-free, for `most load`'s receiver.
         */
        fun parseDecimal(buffer: DirectBuffer, offset: Int): Long {
            var value = 0L
            var length = 0
            while (length < LENGTH) {
                val b = buffer.getByte(offset + length).toInt()
                if (b == 0) break
                if (b < '0'.code || b > '9'.code || length >= MAX_DECIMAL_DIGITS) return -1L
                value = value * 10 + (b - '0'.code)
                length++
            }
            return if (length == 0) -1L else value
        }

        /** Copies an id's 20 bytes. */
        fun copy(src: DirectBuffer, srcOffset: Int, dst: MutableDirectBuffer, dstOffset: Int) =
            dst.putBytes(dstOffset, src, srcOffset, LENGTH)

        /** Whether two ids are the same: all 20 bytes. */
        fun sameBytes(a: DirectBuffer, aOffset: Int, b: DirectBuffer, bOffset: Int): Boolean =
            word0(a, aOffset) == word0(b, bOffset) && word1(a, aOffset) == word1(b, bOffset) &&
                word2(a, aOffset) == word2(b, bOffset)

        /** The id at [offset] as text, padding removed. Allocates: for printing. */
        fun toString(buffer: DirectBuffer, offset: Int): String = read(buffer, offset).toString()

        fun word0(buffer: DirectBuffer, offset: Int): Long = buffer.getLong(offset, ByteOrder.LITTLE_ENDIAN)

        fun word1(buffer: DirectBuffer, offset: Int): Long = buffer.getLong(offset + 8, ByteOrder.LITTLE_ENDIAN)

        fun word2(buffer: DirectBuffer, offset: Int): Long =
            buffer.getInt(offset + 16, ByteOrder.LITTLE_ENDIAN).toLong() and 0xFFFF_FFFFL

        fun writeWords(buffer: MutableDirectBuffer, offset: Int, word0: Long, word1: Long, word2: Long) {
            buffer.putLong(offset, word0, ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(offset + 8, word1, ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(offset + 16, word2.toInt(), ByteOrder.LITTLE_ENDIAN)
        }

        /** Long.MAX_VALUE has 19 digits; more would overflow. */
        private const val MAX_DECIMAL_DIGITS = 19
    }
}
