package com.andrexthedev.autovybz.preset.model

import com.andrexthedev.autovybz.measurement.Verification
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val checked = preset.copy(
        measurement = preset.measurement!!.withVerification(
            Verification.Report(
                correlation = 0.96, measuredChangeDb = 2.4, predictedChangeDb = 2.5, predictionErrorDb = 0.3,
                residualBeforeDb = 3.1, residualAfterDb = 0.7, residualPredictedDb = 0.6,
                measuredImprovementDb = 2.4, predictedImprovementDb = 2.5,
                judgeLowHz = 71.0, judgeHighHz = 16000.0, verdict = Verification.Verdict.AS_PREDICTED,
            ),
        ),
    )

    /**
     * Exports are text files someone else parses, so the check may only ever appear as a comment: APO needs
     * the `#` prefix, and REW ignores everything before its `* Freq(Hz),SPL(dB)` line. Both files still round
     * trip through the parser, which is what makes "the check travels with the preset" safe.
     */
    @Test
    fun theCheckIsACommentInEveryExport() {
        val apo = checked.toApoText()
        val checkLine = apo.lineSequence().first { "Check:" in it }
        assertTrue("APO comments start with '# '", checkLine.startsWith("# Check: as predicted"))
        assertTrue("the numbers are in the line", "measured +2.4 dB" in checkLine && "predicted +2.5 dB" in checkLine)
        // A comment can only describe the preset, never change it: everything that is not a comment is
        // byte-identical to the export of the unchecked calibration, and the parser reads the same numbers.
        assertEquals(
            preset.toApoText().lines().filterNot { it.startsWith("# ") },
            apo.lines().filterNot { it.startsWith("# ") },
        )
        val (preamp, bands) = com.andrexthedev.autovybz.export.PresetExporters.parseApoText(apo)
        assertEquals(preset.preampDb, preamp, 1e-9)
        assertEquals(preset.bands.size, bands.size)

        val header = checked.rewHeader()
        assertTrue(header.any { it.startsWith("Check: as predicted") })
        assertEquals(listOf("Name: ${preset.name}", "Mic: Generic", "Input: UNPROCESSED", "Normalised to 0 dB (300 Hz - 3 kHz)"),
            preset.rewHeader())
    }

    @Test
    fun anUncheckedPresetExportsWithoutACheckLine() {
        assertFalse(preset.toApoText().contains("Check:"))
        assertFalse(preset.rewHeader().any { it.startsWith("Check:") })
    }
}
