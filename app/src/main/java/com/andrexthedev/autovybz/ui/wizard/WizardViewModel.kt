package com.andrexthedev.autovybz.ui.wizard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.measurement.MeasurementController
import com.andrexthedev.autovybz.measurement.MeasurementOutcome
import com.andrexthedev.autovybz.measurement.MeasurementProgress
import com.andrexthedev.autovybz.measurement.audio.CaptureException
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.measurement.audio.RouteInspector
import com.andrexthedev.autovybz.measurement.audio.VolumeGuard
import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.MeasurementException
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    data class Measuring(val fraction: Float, val inputPeak: Float, val phase: MeasurementProgress.Phase) : WizardStep
    object Analyzing : WizardStep
    data class Result(val outcome: MeasurementOutcome, val presetName: String, val nameValid: Boolean = true) : WizardStep
    data class Saved(val presetName: String, val applied: Boolean) : WizardStep
    data class Error(val error: WizardError, val detail: String?) : WizardStep
}

data class WizardUiState(
    val step: WizardStep = WizardStep.Idle,
    val speakerName: String = "",
    val target: TargetType = TargetType.SMART,
    val bandCount: Int = 10,
)

class WizardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AutoVybzSettings(app)
    private val controller = MeasurementController(app)
    private val volume = VolumeGuard(app)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        WizardUiState(speakerName = settings.lastSpeakerName, target = settings.target, bandCount = settings.bandCount),
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

    /** Step 2: user confirmed the safety warning -> 10 s measurement. */
    fun onConfirmStart() {
        if (job?.isActive == true) return
        val s = _state.value
        settings.lastSpeakerName = s.speakerName
        val request = settings.buildRequest(s.speakerName, s.target)
        job = viewModelScope.launch {
            setStep(WizardStep.Preparing)
            try {
                val outcome = controller.run(request) { p ->
                    when (p) {
                        MeasurementProgress.Preparing -> setStep(WizardStep.Preparing)
                        is MeasurementProgress.Measuring -> setStep(WizardStep.Measuring(p.fraction, p.inputPeak, p.phase))
                        MeasurementProgress.Analyzing -> setStep(WizardStep.Analyzing)
                    }
                }
                if (settings.restoreVolumeAfter) volume.restore()
                val name = AutoVybzPresetStore.defaultName(getApplication(), request.speakerName)
                setStep(WizardStep.Result(outcome, name))
            } catch (c: CancellationException) {
                volume.restore()
                setStep(WizardStep.Idle)
                throw c
            } catch (e: MeasurementException) {
                volume.restore()
                setStep(WizardStep.Error(WizardError.NO_SIGNAL, e.message))
            } catch (e: CaptureException) {
                volume.restore()
                setStep(WizardStep.Error(WizardError.CAPTURE_FAILED, e.message))
            } catch (e: Exception) {
                Log.e("AutoVybz", "measurement failed", e)
                volume.restore()
                setStep(WizardStep.Error(WizardError.UNKNOWN, e.message))
            }
        }
    }

    fun onCancel() {
        job?.cancel()
        job = null
        volume.restore()
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
        if (apply) AutoVybzPresetStore.apply(app, preset, settings.enableLimiterOnApply)
        setStep(WizardStep.Saved(name, apply))
    }

    /** Preset built from the current result (for exports before saving). */
    fun currentPreset(): AutoVybzPreset? {
        val step = _state.value.step as? WizardStep.Result ?: return null
        return MeasurementController.buildPreset(step.presetName.trim().ifEmpty { "AutoVybz" }, step.outcome)
    }

    fun onReset() {
        volume.restore()
        setStep(WizardStep.Idle)
    }

    private fun refitIfShowingResult() {
        val step = _state.value.step as? WizardStep.Result ?: return
        val s = _state.value
        val req = step.outcome.request.copy(target = s.target, bandCount = s.bandCount, customTarget = settings.customTarget())
        viewModelScope.launch {
            val refit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { controller.refit(step.outcome, req) }
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
