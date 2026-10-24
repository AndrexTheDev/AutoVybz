package com.andrexthedev.autovybz.measurement

import android.content.Context
import android.os.Build
import com.andrexthedev.autovybz.measurement.audio.CaptureConfig
import com.andrexthedev.autovybz.measurement.audio.CaptureResult
import com.andrexthedev.autovybz.measurement.audio.EqBypass
import com.andrexthedev.autovybz.measurement.audio.FallbackMeasurementEngine
import com.andrexthedev.autovybz.measurement.audio.InputSourceResolver
import com.andrexthedev.autovybz.measurement.audio.MeasurementAudioEngine
import com.andrexthedev.autovybz.measurement.audio.NativeEngine
import com.andrexthedev.autovybz.measurement.dsp.AnalyzerConfig
import com.andrexthedev.autovybz.measurement.dsp.MeasurementAnalyzer
import com.andrexthedev.autovybz.measurement.dsp.MeasurementResult
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.dsp.SpatialAverager
import com.andrexthedev.autovybz.measurement.dsp.StimulusGenerator
import com.andrexthedev.autovybz.measurement.eq.AutoEqRenderer
import com.andrexthedev.autovybz.measurement.eq.AutoEqResult
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.CustomTarget
import com.andrexthedev.autovybz.measurement.eq.TargetCurve
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.measurement.mic.InputSource
import com.andrexthedev.autovybz.measurement.mic.MicCompensation
import com.andrexthedev.autovybz.measurement.mic.MicProfile
import com.andrexthedev.autovybz.measurement.mic.MicProfileDatabase
import com.andrexthedev.autovybz.measurement.mic.ResponseCurve
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the user (or the Pro settings) chose before pressing "Start". */
data class MeasurementRequest(
    val speakerName: String? = null,
    val target: TargetType = TargetType.SMART,
    val customTarget: CustomTarget = CustomTarget(),
    val bandCount: Int = 10,
    val maxBoostDb: Double = 6.0,
    val smoothing: SmoothingProfile = SmoothingProfile.DYNAMIC,
    /** Pro: use a connected USB measurement microphone. */
    val useUsbMic: Boolean = false,
    val usbCalibration: ResponseCurve? = null,
    /** Pro: manual offset curve added to the phone mic profile. */
    val userMicOffset: ResponseCurve? = null,
    /** Pro: override the automatically matched phone profile. */
    val micProfileOverrideId: String? = null,
    /** Indoor (default) or outdoor / party - see [EnvironmentTuning]. */
    val environment: ListeningEnvironment = ListeningEnvironment.INDOOR,
) {
    /** Effective analysis / fitting parameters for this request. */
    val tuning: EnvironmentTuning get() = EnvironmentTuning.of(environment, smoothing, maxBoostDb)
}

sealed class MeasurementProgress {
    object Preparing : MeasurementProgress()
    data class Measuring(val fraction: Float, val inputPeak: Float, val phase: Phase) : MeasurementProgress()
    object Analyzing : MeasurementProgress()

    enum class Phase { SILENCE, PINK_NOISE, SWEEP, DECAY }
}

class MeasurementOutcome(
    val request: MeasurementRequest,
    val result: MeasurementResult,
    val autoEq: AutoEqResult,
    val capture: CaptureResult,
    val micProfile: MicProfile?,
    val micDescription: String,
    val fftEngine: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Number of microphone positions that were averaged (1 = single measurement). */
    val positions: Int = 1,
    /** RMS disagreement between the positions in dB (null for a single position). */
    val positionSpreadDb: Double? = null,
)

/**
 * Orchestrates one calibration run. Volume handling + safety confirmation live
 * in the UI layer (they need user interaction); this class does:
 * EQ pause -> 10 s stimulus playback + capture -> EQ resume -> analysis -> AutoEQ.
 */
class MeasurementController(
    context: Context,
    private val engine: MeasurementAudioEngine = FallbackMeasurementEngine(),
) {
    private val app = context.applicationContext

    suspend fun run(request: MeasurementRequest, onProgress: (MeasurementProgress) -> Unit): MeasurementOutcome {
        onProgress(MeasurementProgress.Preparing)
        val stimulus = withContext(Dispatchers.Default) { StimulusGenerator.generate() }
        val plan = stimulus.plan
        val usbMic = if (request.useUsbMic) InputSourceResolver.findUsbMic(app) else null
        val source = InputSourceResolver.preferred(app, usbMic)

        val eqWasRunning = EqBypass.pauseIfRunning(app)
        val capture = try {
            if (eqWasRunning) kotlinx.coroutines.delay(400) // let DynamicsProcessing detach
            engine.capture(
                stimulus.signal,
                CaptureConfig(plan.sampleRate, source, usbMic, extraRecordSeconds = 1.0),
            ) { fraction, peak ->
                val sample = (fraction * (plan.totalLength + plan.sampleRate)).toInt()
                val phase = when {
                    sample < plan.pinkStart -> MeasurementProgress.Phase.SILENCE
                    sample < plan.sweepStart -> MeasurementProgress.Phase.PINK_NOISE
                    sample < plan.sweepStart + plan.sweepLength -> MeasurementProgress.Phase.SWEEP
                    else -> MeasurementProgress.Phase.DECAY
                }
                onProgress(MeasurementProgress.Measuring(fraction, peak, phase))
            }
        } finally {
            if (eqWasRunning) EqBypass.resume(app)
        }

        onProgress(MeasurementProgress.Analyzing)
        return withContext(Dispatchers.Default) {
            val (profile, comp) = compensation(request, capture.sourceUsed)
            val analyzer = MeasurementAnalyzer(NativeEngine)
            val tuning = request.tuning
            val config = AnalyzerConfig(smoothing = tuning.smoothing, sweepWeight = tuning.sweepWeight)
            val result = analyzer.analyze(capture.recording, stimulus, comp, config)
            val autoEq = render(result, request)
            MeasurementOutcome(request, result, autoEq, capture, profile, comp.description, NativeEngine.name)
        }
    }

    /** Re-runs only the target inversion (target / band count changed on the result screen). */
    fun refit(outcome: MeasurementOutcome, request: MeasurementRequest): MeasurementOutcome =
        MeasurementOutcome(
            request, outcome.result, render(outcome.result, request), outcome.capture,
            outcome.micProfile, outcome.micDescription, outcome.fftEngine, outcome.createdAtMillis,
            outcome.positions, outcome.positionSpreadDb,
        )

    /**
     * Multi-position calibration: power-averages the individual measurements (see [SpatialAverager])
     * and fits the EQ to the average. A single outcome is returned unchanged.
     */
    fun combine(outcomes: List<MeasurementOutcome>, request: MeasurementRequest): MeasurementOutcome {
        require(outcomes.isNotEmpty()) { "no measurements" }
        if (outcomes.size == 1) return outcomes[0]
        val results = outcomes.map { it.result }
        val averaged = SpatialAverager.average(results)
        val last = outcomes.last()
        return MeasurementOutcome(
            request, averaged, render(averaged, request), last.capture, last.micProfile, last.micDescription,
            last.fftEngine, last.createdAtMillis, positions = outcomes.size,
            positionSpreadDb = SpatialAverager.spreadDb(results, averaged),
        )
    }

    private fun render(result: MeasurementResult, request: MeasurementRequest): AutoEqResult {
        val tuning = request.tuning
        return AutoEqRenderer.render(
            result.frequencies,
            result.smoothedDb,
            TargetCurve(request.target, request.customTarget),
            AutoEqSettings(
                bandCount = request.bandCount,
                maxBoostDb = tuning.maxBoostDb,
                broadBoostOctaves = tuning.broadBoostOctaves,
                excursionGuardOctaves = tuning.excursionGuardOctaves,
            ),
            rollOffDb = result.thirdOctaveDb,
            snrDb = result.snrDb,
        )
    }

    private fun compensation(request: MeasurementRequest, source: InputSource): Pair<MicProfile?, MicCompensation> {
        if (source == InputSource.USB) return null to MicCompensation.forUsbMic(request.usbCalibration)
        val db = MicProfileDatabase.get(app)
        val profile = request.micProfileOverrideId?.let { db.byId(it) } ?: db.forDevice()
        return profile to MicCompensation.forPhone(profile, source, request.userMicOffset)
    }

    companion object {
        fun buildPreset(name: String, outcome: MeasurementOutcome): AutoVybzPreset {
            val r = outcome.result
            val e = outcome.autoEq
            return AutoVybzPreset(
                name = name,
                createdAtMillis = outcome.createdAtMillis,
                deviceManufacturer = Build.MANUFACTURER ?: "",
                deviceModel = Build.MODEL ?: "",
                speakerName = outcome.request.speakerName,
                target = outcome.request.target,
                bandCount = outcome.request.bandCount,
                bands = e.bands,
                preampDb = e.preampDb,
                lowLimitHz = e.lowLimitHz,
                highLimitHz = e.highLimitHz,
                measurement = MeasurementSnapshot(
                    frequencies = r.frequencies,
                    rawDb = r.rawDb,
                    smoothedDb = r.smoothedDb,
                    targetDb = e.targetDb,
                    predictedDb = e.predictedDb,
                    micProfileId = outcome.micProfile?.id ?: "usb",
                    micDescription = outcome.micDescription,
                    inputSource = outcome.capture.sourceUsed.name,
                    engine = "${outcome.capture.engineName}, FFT ${outcome.fftEngine}",
                    latencyMs = r.latencyMs,
                    medianSnrDb = r.medianSnrDb,
                    warnings = r.warnings.map { it.name },
                    environment = outcome.request.environment.name,
                    positions = outcome.positions,
                ),
            )
        }
    }
}
