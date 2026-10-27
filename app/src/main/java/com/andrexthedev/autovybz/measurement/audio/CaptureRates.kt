package com.andrexthedev.autovybz.measurement.audio

/**
 * Sample-rate negotiation for the measurement path (pure Kotlin, unit-tested).
 *
 * Why this exists: the stimulus is generated for **one** sample rate and the full-duplex capture has to
 * play *and* record at exactly that rate - the sweep reference, the pink-noise period and the analysis
 * grid are all sample-exact. A fixed 48 kHz therefore fails hard on devices whose microphone only runs
 * at 44.1 kHz (common on older/cheap phones and on some Bluetooth/USB capture paths), and it makes the
 * Oboe engine resample both directions, which adds filter group delay and a slight HF response error
 * right in the band that is being measured.
 *
 * Policy:
 *  1. the device's own output rate ([nativeRate]), if it is one of [SUPPORTED] - measuring natively
 *     avoids any sample-rate conversion in playback and capture,
 *  2. the plan's default rate ([planRate]) if the capture path supports it,
 *  3. the remaining supported rate (48 kHz down to 44.1 kHz).
 *
 * If nothing is supported the plan rate is returned unchanged: the engine then reports a clear
 * [SampleRateMismatchException] and the controller retries with the rate the HAL actually offered.
 */
object CaptureRates {

    /** 48 kHz: covers the 20 Hz - 20 kHz sweep with headroom and is what Android uses for media by default. */
    const val PRIMARY = 48000

    /** 44.1 kHz: the lowest rate that still holds the full 20 Hz - 20 kHz band. */
    const val FALLBACK = 44100

    /** Rates the analysis pipeline is defined for (see [com.andrexthedev.autovybz.measurement.dsp.StimulusGenerator]). */
    val SUPPORTED: List<Int> = listOf(PRIMARY, FALLBACK)

    fun isUsable(rate: Int): Boolean = rate in SUPPORTED

    /**
     * @param planRate requested/default rate of the stimulus
     * @param nativeRate the platform's preferred output rate (`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`),
     *   null when the property is unavailable
     * @param supports predicate that reports whether the capture HAL accepts a rate
     */
    fun choose(planRate: Int, nativeRate: Int?, supports: (Int) -> Boolean): Int {
        val candidates = LinkedHashSet<Int>()
        nativeRate?.takeIf { isUsable(it) }?.let { candidates += it }
        if (isUsable(planRate)) candidates += planRate
        candidates += SUPPORTED
        return candidates.firstOrNull { supports(it) } ?: planRate
    }

    /**
     * Rate for the second attempt after [failedRate] turned out to be unsupported, or null when no other
     * supported rate is left (the caller then surfaces the original error).
     *
     * A [failedRate] outside [SUPPORTED] (e.g. a rate invented by a broken HAL report) yields [PRIMARY]:
     * there is nothing to exclude, and retrying at the plan rate is the meaningful second attempt.
     */
    fun alternativeTo(failedRate: Int): Int? = SUPPORTED.firstOrNull { it != failedRate }
}
