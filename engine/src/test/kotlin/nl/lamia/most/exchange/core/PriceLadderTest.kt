package nl.lamia.most.exchange.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The occupancy scans are the book's price-discovery primitive and are pure bit
 * manipulation, so the cases that matter are the word boundaries: masks at bit 0 and bit 63,
 * and scans that must cross into an adjacent word.
 */
class PriceLadderTest {

    private val levels = 256

    @Test
    fun `empty ladder reports no occupancy`() {
        val ladder = PriceLadder(levels)
        assertEquals(NULL_LEVEL, ladder.highestOccupiedAtOrBelow(levels - 1))
        assertEquals(NULL_LEVEL, ladder.lowestOccupiedAtOrAbove(0))
    }

    @Test
    fun `finds a level that is exactly the search origin`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(100)
        assertEquals(100, ladder.highestOccupiedAtOrBelow(100))
        assertEquals(100, ladder.lowestOccupiedAtOrAbove(100))
    }

    @Test
    fun `scans across word boundaries in both directions`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(5)
        ladder.markOccupied(200)

        // Descending from a word above the target must walk back into word 0.
        assertEquals(5, ladder.highestOccupiedAtOrBelow(199))
        // Ascending from a word below the target must walk forward into word 3.
        assertEquals(200, ladder.lowestOccupiedAtOrAbove(6))
    }

    @Test
    fun `handles the first and last bit of a word`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(0)
        ladder.markOccupied(63)
        ladder.markOccupied(64)

        assertEquals(0, ladder.highestOccupiedAtOrBelow(0))
        assertEquals(63, ladder.highestOccupiedAtOrBelow(63))
        assertEquals(64, ladder.highestOccupiedAtOrBelow(64))

        assertEquals(63, ladder.lowestOccupiedAtOrAbove(1))
        assertEquals(64, ladder.lowestOccupiedAtOrAbove(64))
    }

    @Test
    fun `search past the last occupied level returns NULL_LEVEL`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(10)
        assertEquals(NULL_LEVEL, ladder.highestOccupiedAtOrBelow(9))
        assertEquals(NULL_LEVEL, ladder.lowestOccupiedAtOrAbove(11))
    }

    @Test
    fun `out of range origins are clamped rather than throwing`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(10)
        assertEquals(NULL_LEVEL, ladder.highestOccupiedAtOrBelow(-1))
        assertEquals(NULL_LEVEL, ladder.lowestOccupiedAtOrAbove(levels))
        assertEquals(10, ladder.highestOccupiedAtOrBelow(levels + 50))
        assertEquals(10, ladder.lowestOccupiedAtOrAbove(-5))
    }

    @Test
    fun `marking empty clears the level`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(70)
        assertTrue(ladder.isOccupied(70))
        ladder.markEmpty(70)
        assertFalse(ladder.isOccupied(70))
        assertEquals(NULL_LEVEL, ladder.highestOccupiedAtOrBelow(levels - 1))
    }

    @Test
    fun `marking one level does not disturb its neighbours in the same word`() {
        val ladder = PriceLadder(levels)
        ladder.markOccupied(31)
        ladder.markOccupied(32)
        ladder.markOccupied(33)
        ladder.markEmpty(32)

        assertTrue(ladder.isOccupied(31))
        assertFalse(ladder.isOccupied(32))
        assertTrue(ladder.isOccupied(33))
        assertEquals(31, ladder.highestOccupiedAtOrBelow(32))
        assertEquals(33, ladder.lowestOccupiedAtOrAbove(32))
    }
}
