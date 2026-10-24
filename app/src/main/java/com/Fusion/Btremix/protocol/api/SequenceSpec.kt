package com.Fusion.Btremix.protocol.api

import kotlin.random.Random

/** Generic request-sequence behavior selected by a byte-stream Definition. */
enum class SequenceFunction {
    TOGGLE,
    INCREMENT,
}

/** Initial value for an incrementing sequence. */
sealed interface SequenceStart {
    data class Fixed(val value: Int) : SequenceStart {
        init {
            require(value in 0..0xff) { "Sequence start must fit UInt8" }
        }
    }

    /** Chooses a fresh UInt8 start when each session-scoped generator is created. */
    data object Random : SequenceStart
}

/**
 * Data-only request sequence policy.
 *
 * The default remains toggling so existing stream packages keep their wire behavior. Incrementing
 * sequences wrap at 8 bits, matching the generic sequence field width.
 */
data class SequenceSpec(
    val function: SequenceFunction = SequenceFunction.TOGGLE,
    val start: SequenceStart = SequenceStart.Fixed(0),
) {
    init {
        if (function == SequenceFunction.TOGGLE) {
            require(start is SequenceStart.Fixed && start.value in 0..1) {
                "Toggling sequence start must be the fixed value 0 or 1"
            }
        }
    }

    fun createGenerator(random: Random = Random.Default): SequenceGenerator = when (function) {
        SequenceFunction.TOGGLE -> TogglingSequenceGenerator(
            (start as SequenceStart.Fixed).value,
        )
        SequenceFunction.INCREMENT -> IncrementingSequenceGenerator(
            when (val initial = start) {
                is SequenceStart.Fixed -> initial.value
                SequenceStart.Random -> random.nextInt(0x100)
            },
        )
    }
}
