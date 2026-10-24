package com.Fusion.Btremix.protocol.api

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceSpecTest {
    @Test
    fun default_togglesLikeEveryExistingStreamDefinition() {
        val generator = SequenceSpec().createGenerator()

        assertEquals(listOf(0, 1, 0, 1), List(4) { generator.next() })
    }

    @Test
    fun increment_wrapsAtUInt8() {
        val generator = SequenceSpec(
            function = SequenceFunction.INCREMENT,
            start = SequenceStart.Fixed(0xFE),
        ).createGenerator()

        assertEquals(listOf(0xFE, 0xFF, 0x00, 0x01), List(4) { generator.next() })
    }

    @Test
    fun randomStart_isSessionScopedAndMonotonic() {
        val generator = SequenceSpec(
            function = SequenceFunction.INCREMENT,
            start = SequenceStart.Random,
        ).createGenerator(Random(7))

        val values = List(4) { generator.next() }

        assertTrue(values.first() in 0..0xff)
        assertTrue(values.zipWithNext().all { (previous, next) -> (previous + 1) and 0xff == next })
    }
}
