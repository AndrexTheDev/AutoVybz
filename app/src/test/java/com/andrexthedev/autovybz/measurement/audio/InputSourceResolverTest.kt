package com.andrexthedev.autovybz.measurement.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The built-in mic pinning must be conservative: forcing a "back"/camcorder
 * microphone would be worse than the system default route, so ambiguous
 * device lists must yield no pinning at all.
 */
class InputSourceResolverTest {

    @Test
    fun singleBuiltInMicWins() {
        assertEquals(0, InputSourceResolver.builtInMicIndex(listOf("")))
        assertEquals(0, InputSourceResolver.builtInMicIndex(listOf("bottom")))
    }

    @Test
    fun bottomMicWinsAmongSeveral() {
        assertEquals(1, InputSourceResolver.builtInMicIndex(listOf("back", "bottom")))
        assertEquals(1, InputSourceResolver.builtInMicIndex(listOf("camcorder", "main")))
    }

    @Test
    fun unlabelledWinsWhenNoBottomLabelled() {
        assertEquals(0, InputSourceResolver.builtInMicIndex(listOf("", "back")))
    }

    @Test
    fun ambiguousOrEmptyReturnsNull() {
        assertNull(InputSourceResolver.builtInMicIndex(listOf("back", "front")))
        assertNull(InputSourceResolver.builtInMicIndex(emptyList()))
    }
}
