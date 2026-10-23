package com.andrexthedev.autovybz.preset.model

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoVybzPresetTest {

    private val preset = AutoVybzPreset(
        name = "AutoVybz_JBL_2026-10-23",
        createdAtMillis = 1_792_000_000_000,
        deviceManufacturer = "samsung",
        deviceModel = "SM-S921B",
        speakerName = "JBL \"Flip\"",
        target = TargetType.SMART,
        bandCount = 10,
        bands = listOf(PeqBand(PeqType.LOW_SHELF, 105.0, 3.0, 0.7), PeqBand(PeqType.PEAK, 214.4, -3.5, 1.19)),
        preampDb = -4.0,
        lowLimitHz = 55.0,
        highLimitHz = 16000.0,
        measurement = MeasurementSnapshot(
            doubleArrayOf(20.0, 1000.0), doubleArrayOf(-10.0, 0.0), doubleArrayOf(-9.5, 0.0),
            doubleArrayOf(3.0, 0.0), doubleArrayOf(-6.0, 0.0), "mems-bottom-port", "Generic", "UNPROCESSED",
            "Oboe (C++)", 182.5, 31.2, listOf("LOW_SNR"),
        ),
    )

    @Test
    fun poolJsonMatchesEqualizer314Shape() {
        val json = preset.toPoolJson()
        assertTrue(json.startsWith("{\"preamp\":-4,\"channelSideEqEnabled\":false,\"bands\":["))
        assertTrue(json.contains("{\"frequency\":105,\"gain\":3,\"q\":0.7,\"filterType\":\"LOW_SHELF\",\"enabled\":true}"))
        assertTrue(json.contains("\"filterType\":\"BELL\""))
        assertTrue(json.contains("\"speaker\":\"JBL \\\"Flip\\\"\""))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun internalJsonContainsMeasurement() {
        val json = preset.toInternalJson()
        assertTrue(json.contains("\"format\": \"autovybz-preset\""))
        assertTrue(json.contains("\"frequencies\": [20,1000]"))
        assertTrue(json.contains("\"latencyMs\": 182.5"))
        assertTrue(json.contains("{\"type\": \"PK\", \"frequency\": 214.4, \"gain\": -3.5, \"q\": 1.19}"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertTrue(preset.toApoText().contains("Filter 1: ON LSC Fc 105 Hz Gain 3.0 dB Q 0.70"))
    }

    @Test
    fun filterTypeMappingRoundTrips() {
        for (t in PeqType.values()) {
            assertEquals(t, AutoVybzPreset.peqTypeFromFilterTypeName(AutoVybzPreset.filterTypeName(t)))
        }
    }
}
