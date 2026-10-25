package com.andrexthedev.autovybz.measurement.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gradle-only (needs org.json on the test classpath). Validates the shipped asset. */
class MicProfileDatabaseTest {

    private fun asset(): String {
        val candidates = listOf("src/main/assets/autovybz/mic_profiles.json", "app/src/main/assets/autovybz/mic_profiles.json")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    @Test
    fun shippedDatabaseParsesAndResolvesDevices() {
        val db = MicProfileDatabase.parse(asset())
        assertTrue(db.profiles.size >= 20)
        assertEquals("samsung-galaxy-s-2021plus", db.forDevice("samsung", "SM-S921B").id)
        assertEquals("google-pixel-6plus", db.forDevice("Google", "Pixel 8 Pro").id)
        assertEquals("fairphone", db.forDevice("Fairphone", "FP5").id)
        assertEquals(db.default.id, db.forDevice("UnknownCorp", "X").id)
        for (p in db.profiles) {
            assertTrue(p.id, p.curves.containsKey(InputSource.UNPROCESSED))
            assertEquals(0.0, p.curves.getValue(InputSource.UNPROCESSED).normalisedAt1k().levelAt(1000.0), 1e-9)
        }
    }
}
