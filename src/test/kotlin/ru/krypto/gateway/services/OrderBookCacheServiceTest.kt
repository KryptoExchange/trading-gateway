package ru.krypto.gateway.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderBookCacheServiceTest {
    @Test
    fun `RPC snapshot levels are parsed as raw fixed point values`() {
        val level = parseRawOrderbookSnapshotLevel(listOf("230755717296", "111635791"))

        assertEquals(230_755_717_296L to 111_635_791L, level)
        assertEquals("2307.55717296", PrecisionService.longToDecimalString(level!!.first))
        assertEquals("1.11635791", PrecisionService.longToDecimalString(level.second))
    }

    @Test
    fun `RPC snapshot levels reject human decimal values`() {
        assertNull(parseRawOrderbookSnapshotLevel(listOf("2307.55717296", "1.11635791")))
    }
}
