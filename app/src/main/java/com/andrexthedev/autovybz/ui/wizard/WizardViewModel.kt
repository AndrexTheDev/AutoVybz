package com.andrexthedev.autovybz.ui.wizard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.measurement.EnvironmentTuning
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.MeasurementController
import com.andrexthedev.autovybz.measurement.MeasurementOutcome
import com.andrexthedev.autovybz.measurement.MeasurementProgress
import com.andrexthedev.autovybz.measurement.MeasurementRequest
import com.andrexthedev.autovybz.measurement.audio.CaptureException
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.measurement.audio.RouteInspector
import com.andrexthedev.autovybz.measurement.audio.VolumeGuard
import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.MeasurementException
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WizardError { PERMISSION_DENIED, NO_SIGNAL, CAPTURE_FAILED, UNKNOWN }

sealed interface WizardStep {
    /** Start screen with the big "Calibrate speaker" button. */
    object Idle : WizardStep
    /** Headphones or the phone's own speaker are active. */
    data class RouteBlocked(val verdict: RouteClassifier.Verdict, val routeName: String?) : WizardStep
    /** Safety warning + volume slider; measurement starts only after explicit confirmation. */
    data class ConfirmSafety(
        val routeName: String?,
        val needsSpeakerConfirmation: Boolean,
        val volume: Float,
        val volumeFixed: Boolean,
    ) : WizardStep
    object Preparing : WizardStep
    data class Measuring(
        val fraction: Float,
        val inputPeak: Float,
        val phase: MeasurementProgress.Phase,
        /** 1-based position index and total positions of a multi-position calibration. */
        val position: Int = 1,
        val totalPositions: Int = 1,
    ) : WizardStep
    /** Multi-position calibration: [completed] of [total] positions are done, user moves the phone. */
    data class MovePhone(val completed: Int, val total: Int, val lastWarnings: List<MeasurementWarning>) : WizardStep
    object Analyzing : WizardStep
    data class Result(val outcome: MeasurementOutcome, val presetName: String, val nameValid: Boolean = true) : WizardStep
    data class Saved(val presetName: String, val applied: Boolean, val outcome: MeasurementOutcome? = null) : WizardStep
    data class Error(val error: WizardError, val detail: String?) : WizardStep
}

data class WizardUiState(
    val step: WizardStep = WizardStep.Idle,
    val speakerName: String = "",
    val target: TargetType = TargetType.SMART,
    val bandCount: Int = 10,
    /** 1 = quick, 3 = precise (multi-position average). */
    val positions: Int = 1,
    val environment: ListeningEnvironment = ListeningEnvironment.INDOOR,
)

class WizardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AutoVybzSettings(app)
    private val controller = MeasurementController(app)
    private val volume = VolumeGuard(app)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        WizardUiState(
            speakerName = settings.lastSpeakerName,
            target = settings.target,
            bandCount = settings.bandCount,
            positions = settings.positions,
            environment = settings.environment,
        ),
    )
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    private fun setStep(step: WizardStep) = _state.update { it.copy(step = step) }

    fun onSpeakerNameChanged(name: String) = _state.update { it.copy(speakerName = name.take(40)) }

    fun onTargetChanged(target: TargetType) {
        settings.target = target
        _state.update { it.copy(target = target) }
        refitIfShowingResult()
    }

    fun onBandCountChanged(count: Int) {
        settings.bandCount = count
        _state.update { it.copy(bandCount = settings.bandCount) }
        refitIfShowingResult()
    }

    /** Outdoors a single spot can't tell ground-reflection notches from the speaker → suggest 3 positions. */
    fun onEnvironmentChanged(environment: ListeningEnvironment) {
        if (environment == _state.value.environment) return
        settings.environment = environment
        val recommended = EnvironmentTuning.of(environment).recommendedPositions
        if (recommended > settings.positions) settings.positions = recommended
        _state.update { it.copy(environment = environment, positions = settings.positions) }
    }

    fun onPositionsChanged(count: Int) {
        settings.positions = count
        _state.update { it.copy(positions = settings.positions) }
    }

    /** Step 1: big button pressed (permission already checked by the screen). */
    fun onStartClicked(hasRecordPermission: Boolean) {
        if (!hasRecordPermission) return setStep(WizardStep.Error(WizardError.PERMISSION_DENIED, null))
        val decision = RouteInspector.inspect(getApplication())
        val routeName = decision.route?.name?.takeIf { it.isNotBlank() }
        when (decision.verdict) {
            RouteClassifier.Verdict.BLOCKED_HEADPHONES, RouteClassifier.Verdict.BLOCKED_BUILTIN_SPEAKER ->
                setStep(WizardStep.RouteBlocked(decision.verdict, routeName))
            RouteClassifier.Verdict.OK, RouteClassifier.Verdict.NEEDS_SPEAKER_CONFIRMATION, RouteClassifier.Verdict.UNKNOWN -> {
                // Spec: set system media volume to ~70 % before the measurement.
                volume.setFraction(VolumeMath.MEASUREMENT_FRACTION)
                setStep(
                    WizardStep.ConfirmSafety(
                        routeName = routeName,
                        needsSpeakerConfirmation = decision.verdict != RouteClassifier.Verdict.OK,
                        volume = volume.currentFraction,
                        volumeFixed = volume.isFixed,
                    ),
                )
            }
        }
    }

    fun onVolumeChanged(fraction: Float) {
        val step = _state.value.step as? WizardStep.ConfirmSafety ?: return
        volume.setFraction(fraction)
        setStep(step.copy(volume = volume.currentFraction))
    }

    /** Measurements of the current (multi-position) calibration, in order. */
    private val partial = mutableListOf<MeasurementOutcome>()
    private var activeRequest: MeasurementRequest? = null
    private var activeTotal = 1

    /** Step 2: user confirmed the safety warning -> first 10 s measurement. */
    fun onConfirmStart() {
        if (job?.isActive == true) return
        val s = _state.value
        settings.lastSpeakerName = s.speakerName
        partial.clear()
        activeRequest = settings.buildRequest(s.speakerName, s.target)
        activeTotal = s.positions
        measureNextPosition()
    }

    /** Multi-position: phone was moved, measure the next position. */
    fun onNextPosition() {
        if (job?.isActive == true || activeRequest == null) return
        measureNextPosition()
    }

    /** Multi-position: discard the last (disturbed) measurement and measure that position again. */
    fun onRepeatLastPosition() {
        if (job?.isActive == true || activeRequest == null || partial.isEmpty()) return
        partial.removeAt(partial.lastIndex)
        measureNextPosition()
    }

    /** Multi-position: stop early and use the positions measured so far. */
    fun onFinishWithMeasured() {
        if (job?.isActive == true || partial.isEmpty()) return
        finish()
    }

    private fun measureNextPosition() {
        val request = activeRequest ?: return
        val total = activeTotal
        val position = partial.size + 1
        job = viewModelScope.launch {
            setStep(WizardStep.Preparing)
            try {
                val outcome = controller.run(request) { p ->
                    when (p) {
                        MeasurementProgress.Preparing -> setStep(WizardStep.Preparing)
                        is MeasurementProgress.Measuring ->
                            setStep(WizardStep.Measuring(p.fraction, p.inputPeak, p.phase, position, total))
                        MeasurementProgress.Analyzing -> setStep(WizardStep.Analyzing)
                    }
                }
                partial += outcome
                // A disturbed last position (gust, dropout, …) gets the chance to be repeated before averaging.
                val disturbed = outcome.result.warnings.any { it != MeasurementWarning.HIGH_LATENCY }
                if (partial.size < total || (total > 1 && disturbed)) {
                    setStep(WizardStep.MovePhone(partial.size, total, outcome.result.warnings))
                } else {
                    finish()
                }
            } catch (c: CancellationException) {
                resetSession()
                setStep(WizardStep.Idle)
                throw c
            } catch (e: MeasurementException) {
                resetSession()
                setStep(WizardStep.Error(WizardError.NO_SIGNAL, e.message))
            } catch (e: CaptureException) {
                resetSession()
                setStep(WizardStep.Error(WizardError.CAPTURE_FAILED, e.message))
            } catch (e: Exception) {
                Log.e("AutoVybz", "measurement failed", e)
                resetSession()
                setStep(WizardStep.Error(WizardError.UNKNOWN, e.message))
            }
        }
    }

    /** Averages all measured positions, fits the EQ and shows the result. */
    private fun finish() {
        val request = activeRequest ?: return
        val measured = partial.toList()
        job = viewModelScope.launch {
            setStep(WizardStep.Analyzing)
            val outcome = try {
                withContext(Dispatchers.Default) { controller.combine(measured, request) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e("AutoVybz", "combining positions failed", e)
                resetSession()
                setStep(WizardStep.Error(WizardError.UNKNOWN, e.message))
                return@launch
            }
            if (settings.restoreVolumeAfter) volume.restore()
            partial.clear()
            activeRequest = null
            val name = AutoVybzPresetStore.defaultName(getApplication(), request.speakerName)
            setStep(WizardStep.Result(outcome, name))
        }
    }

    private fun resetSession() {
        partial.clear()
        activeRequest = null
        volume.restore()
    }

    fun onCancel() {
        job?.cancel()
        job = null
        resetSession()
        setStep(WizardStep.Idle)
    }

    fun onPresetNameChanged(name: String) {
        val step = _state.value.step as? WizardStep.Result ?: return
        setStep(step.copy(presetName = name, nameValid = PresetNaming.isValid(name.trim())))
    }

    /** Step 3: save into the Equalizer314 preset list (optionally activate it right away). */
    fun onSave(apply: Boolean) {
        val step = _state.value.step as? WizardStep.Result ?: return
        val name = step.presetName.trim()
        if (!PresetNaming.isValid(name)) return setStep(step.copy(nameValid = false))
        val preset = MeasurementController.buildPreset(name, step.outcome)
        val app = getApplication<Application>()
        AutoVybzPresetStore.save(app, preset)
        // Outdoor / party calibrations always arm the limiter (party volume).
        val limiter = settings.enableLimiterOnApply || step.outcome.request.tuning.forceLimiter
        if (apply) AutoVybzPresetStore.apply(app, preset, limiter)
        setStep(WizardStep.Saved(name, apply, step.outcome))
    }

    /** Preset built from the current result (for exports before saving). */
    fun currentPreset(): AutoVybzPreset? {
        val step = _state.value.step as? WizardStep.Result ?: return null
        return MeasurementController.buildPreset(step.presetName.trim().ifEmpty { "AutoVybz" }, step.outcome)
    }

    fun onReset() {
        job?.cancel()
        resetSession()
        setStep(WizardStep.Idle)
    }

    private fun refitIfShowingResult() {
        val step = _state.value.step as? WizardStep.Result ?: return
        val s = _state.value
        val req = step.outcome.request.copy(target = s.target, bandCount = s.bandCount, customTarget = settings.customTarget())
        viewModelScope.launch {
            val refit = withContext(Dispatchers.Default) { controller.refit(step.outcome, req) }
            val now = _state.value.step as? WizardStep.Result ?: return@launch
            setStep(now.copy(outcome = refit))
        }
    }

    override fun onCleared() {
        job?.cancel()
        volume.restore()
        super.onCleared()
    }
}
