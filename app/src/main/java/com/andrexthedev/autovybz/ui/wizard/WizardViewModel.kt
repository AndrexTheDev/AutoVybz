package com.andrexthedev.autovybz.ui.wizard

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.andrexthedev.autovybz.AutoVybzServices
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.measurement.EnvironmentTuning
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.MeasurementController
import com.andrexthedev.autovybz.measurement.MeasurementOutcome
import com.andrexthedev.autovybz.measurement.MeasurementProgress
import com.andrexthedev.autovybz.measurement.MeasurementRequest
import com.andrexthedev.autovybz.measurement.SpeakerClass
import com.andrexthedev.autovybz.measurement.SpeakerClassTuning
import com.andrexthedev.autovybz.measurement.audio.CaptureException
import com.andrexthedev.autovybz.measurement.audio.CaptureInterruptedException
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.measurement.audio.VolumeGuard
import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.MeasurementException
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.CustomTarget
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.premium.EntitlementPolicy
import com.andrexthedev.autovybz.premium.EntitlementStore
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.bearinmind.equalizer314.R
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WizardStep {
    /** Start screen with the big "Calibrate speaker" button. */
    object Idle : WizardStep
    /** Headphones or the phone's own speaker are active (or, in device mode, an external output is). */
    data class RouteBlocked(
        val verdict: RouteClassifier.Verdict,
        val routeName: String?,
        /** Device-speaker calibration: the situation is inverted - an external output blocks. */
        val deviceMode: Boolean = false,
    ) : WizardStep
    /** Safety warning + volume slider; measurement starts only after explicit confirmation. */
    data class ConfirmSafety(
        val routeName: String?,
        val needsSpeakerConfirmation: Boolean,
        val volume: Float,
        val volumeFixed: Boolean,
        /** Device-speaker calibration (own speakers, own microphone, lower volume). */
        val deviceMode: Boolean = false,
    ) : WizardStep
    /** "Be quiet, don't touch the phone" - gives the user time to step back before each position. */
    data class Countdown(val secondsLeft: Int, val position: Int = 1, val totalPositions: Int = 1) : WizardStep
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
    data class Result(
        val outcome: MeasurementOutcome,
        val presetName: String,
        val nameValid: Boolean = true,
        /** A calibration with this name exists and would be overwritten (warning, not an error). */
        val nameExists: Boolean = false,
        /** Free tier already uses its one saved profile and this name would create another one. */
        val profileLimitReached: Boolean = false,
    ) : WizardStep
    data class Saved(val presetName: String, val applied: Boolean, val outcome: MeasurementOutcome? = null) : WizardStep
    /**
     * [resumePosition] != null: a later position of a multi-position calibration failed; the positions
     * measured before are kept and the user can repeat just this one (or finish with what is there).
     */
    data class Error(
        val error: WizardError,
        val detail: String?,
        val resumePosition: Int? = null,
        val measuredPositions: Int = 0,
    ) : WizardStep
}

data class WizardUiState(
    val step: WizardStep = WizardStep.Idle,
    val speakerName: String = "",
    val target: TargetType = TargetType.SMART,
    val bandCount: Int = 10,
    /** 1 = quick, 3 = precise (multi-position average). */
    val positions: Int = 1,
    val environment: ListeningEnvironment = ListeningEnvironment.INDOOR,
    /** External speaker, weak-speaker rescue, or this device's own speakers. */
    val speakerClass: SpeakerClass = SpeakerClass.STANDARD,
)

class WizardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AutoVybzSettings(app)
    private val entitlementStore = EntitlementStore(app)
    private val controller = MeasurementController(app)
    private val volume = VolumeGuard(app)
    private var job: Job? = null
    private var refitJob: Job? = null

    private fun featureLimits() = EntitlementPolicy.limits(entitlementStore.load())

    private fun limitedPositions(requested: Int = settings.positions): Int =
        EntitlementPolicy.allowedPositions(requested, featureLimits())

    /**
     * Counts *AutoVybz calibrations*, not every preset of the shared Equalizer314 pool: presets saved in the
     * inherited studio screens must not consume the Free tier's single profile (see
     * [AutoVybzPresetStore.calibrationNames]).
     */
    private fun isProfileLimitReached(name: String): Boolean =
        EntitlementPolicy.isProfileLimitReached(
            AutoVybzPresetStore.calibrationNames(getApplication()),
            name,
            featureLimits(),
        )

    private val _state = MutableStateFlow(
        WizardUiState(
            speakerName = settings.lastSpeakerName,
            target = settings.target,
            bandCount = settings.bandCount,
            positions = limitedPositions(),
            environment = settings.environment,
            speakerClass = settings.speakerClass,
        ),
    )
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    private fun setStep(step: WizardStep) = _state.update { it.copy(step = step) }

    /**
     * Progress arrives on the audio worker thread (capture loop). UI state is only written on the main thread:
     * under the Compose test clock a collector resumed from a worker re-laid out views off the main thread
     * (CalledFromWrongThreadException on API 28, campaign 9). A late "measuring 80 %" must never overwrite a step
     * that already moved on (Analyzing, Result, an error), so posted updates only apply while still measuring.
     */
    private fun setStepFromProgress(next: WizardStep) {
        if (Looper.myLooper() == Looper.getMainLooper()) { setStep(next); return }
        viewModelScope.launch {
            val current = _state.value.step
            if (current is WizardStep.Preparing || current is WizardStep.Measuring) setStep(next)
        }
    }

    /** The Pro tab edits the same preferences - pick up changes when the wizard becomes visible again. */
    fun reloadSettings() = _state.update {
        it.copy(
            target = settings.target, bandCount = settings.bandCount, positions = limitedPositions(),
            environment = settings.environment, speakerClass = settings.speakerClass,
        )
    }

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

    /** Outdoors a single spot can't tell ground-reflection notches from the speaker -> suggest 3 positions. */
    fun onEnvironmentChanged(environment: ListeningEnvironment) {
        if (environment == _state.value.environment) return
        settings.environment = environment
        val recommended = EnvironmentTuning.of(environment).recommendedPositions
        if (recommended > settings.positions) settings.positions = recommended
        _state.update { it.copy(environment = environment, positions = limitedPositions()) }
    }

    fun onPositionsChanged(count: Int) {
        settings.positions = count
        _state.update { it.copy(positions = limitedPositions(count)) }
    }

    /**
     * The target the user had for normal external speakers, remembered while a rescue / device
     * class pre-selects its own - switching back must not silently replace their preference.
     */
    private var standardTargetBackup: TargetType? = null

    /**
     * Speaker type picked. Rescue / device speakers pre-select their forgiving target (the chip
     * stays changeable, and the external-speaker target is restored afterwards); device mode
     * pre-fills the profile name.
     */
    fun onSpeakerClassChanged(speakerClass: SpeakerClass) {
        val current = _state.value
        if (speakerClass == current.speakerClass) return
        settings.speakerClass = speakerClass
        if (current.speakerClass == SpeakerClass.STANDARD) standardTargetBackup = current.target
        val restored = if (speakerClass == SpeakerClass.STANDARD) standardTargetBackup else null
        val nextTarget = restored ?: SpeakerClassTuning.of(speakerClass).suggestedTarget ?: current.target
        if (nextTarget != current.target) settings.target = nextTarget
        if (speakerClass == SpeakerClass.STANDARD) standardTargetBackup = null
        _state.update { s ->
            s.copy(
                speakerClass = speakerClass,
                target = nextTarget,
                speakerName = if (speakerClass == SpeakerClass.DEVICE && s.speakerName.isBlank()) {
                    getApplication<Application>().getString(R.string.avz_device_speaker_name)
                } else s.speakerName,
            )
        }
    }

    /** Step 1: big button pressed (permission already checked by the screen). */
    fun onStartClicked(hasRecordPermission: Boolean) {
        if (job?.isActive == true) return
        if (!hasRecordPermission) return setStep(WizardStep.Error(WizardError.PERMISSION_DENIED, null))
        val decision = AutoVybzServices.routeInspector(getApplication())
        val routeName = decision.route?.name?.takeIf { it.isNotBlank() }
        if (_state.value.speakerClass == SpeakerClass.DEVICE) {
            // Inverted route logic: this device itself must play, so any external output blocks.
            if (!decision.builtinSpeakerActive) {
                return setStep(WizardStep.RouteBlocked(decision.verdict, routeName, deviceMode = true))
            }
            volume.setFraction(deviceVolumeFraction)
            return setStep(
                WizardStep.ConfirmSafety(
                    routeName = routeName,
                    needsSpeakerConfirmation = false,
                    volume = volume.currentFraction,
                    volumeFixed = volume.isFixed,
                    deviceMode = true,
                ),
            )
        }
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

    /**
     * Start volume for device-speaker measurements in this wizard session. Steps down
     * automatically when the built-in mic clipped (loud phones can clip it even at 50 %,
     * and re-trying at the same volume would only clip again).
     */
    private var deviceVolumeFraction: Float =
        SpeakerClassTuning.of(SpeakerClass.DEVICE).measurementVolumeFraction ?: VolumeMath.DEVICE_MEASUREMENT_FRACTION

    /** Step 2: user confirmed the safety warning -> first 10 s measurement. */
    fun onConfirmStart() {
        if (job?.isActive == true) return
        val s = _state.value
        settings.lastSpeakerName = s.speakerName
        partial.clear()
        val builtRequest = settings.buildRequest(s.speakerName, s.target)
        val limits = featureLimits()
        activeRequest = if (limits.advancedMeasurementSettings) {
            builtRequest
        } else {
            // Free can try every speaker class, environment and target, but the expert measurement
            // inputs stay at the safe defaults (no USB mic/calibration file/manual mic override).
            builtRequest.copy(
                customTarget = CustomTarget(),
                maxBoostDb = 6.0,
                smoothing = SmoothingProfile.DYNAMIC,
                useUsbMic = false,
                usbCalibration = null,
                userMicOffset = null,
                micProfileOverrideId = null,
            )
        }
        // Fixed geometry (device speakers): extra positions would measure the same thing again.
        activeTotal = if (SpeakerClassTuning.of(s.speakerClass).singlePosition) 1 else EntitlementPolicy.allowedPositions(s.positions, limits)
        measureNextPosition()
    }

    /** Multi-position: phone was moved (or a failed position is retried) -> measure the next position. */
    fun onNextPosition() {
        if (job?.isActive == true || activeRequest == null) return
        if (!routeStillAllowed()) return
        measureNextPosition()
    }

    /** Multi-position: discard the last (disturbed) measurement and measure that position again. */
    fun onRepeatLastPosition() {
        if (job?.isActive == true || activeRequest == null || partial.isEmpty()) return
        if (!routeStillAllowed()) return
        partial.removeAt(partial.lastIndex)
        measureNextPosition()
    }

    /** Multi-position: stop early and use the positions measured so far. */
    fun onFinishWithMeasured() {
        if (job?.isActive == true || partial.isEmpty()) return
        finish()
    }

    /**
     * Before continuing an interrupted session: headphones may have been plugged in meanwhile, so the
     * route is checked again (the stimulus must never go to headphones).
     */
    private fun routeStillAllowed(): Boolean {
        if (_state.value.step !is WizardStep.Error) return true
        val decision = AutoVybzServices.routeInspector(getApplication())
        val deviceMode = activeRequest?.speakerClass == SpeakerClass.DEVICE
        val allowed = if (deviceMode) decision.builtinSpeakerActive
        else decision.canMeasure || decision.verdict == RouteClassifier.Verdict.UNKNOWN
        if (allowed) return true
        resetSession()
        setStep(WizardStep.RouteBlocked(decision.verdict, decision.route?.name?.takeIf { it.isNotBlank() }, deviceMode))
        return false
    }

    private fun measureNextPosition() {
        val request = activeRequest ?: return
        val total = activeTotal
        val position = partial.size + 1
        job = viewModelScope.launch {
            try {
                for (s in AutoVybzServices.countdownSeconds downTo 1) {
                    setStep(WizardStep.Countdown(s, position, total))
                    delay(1000)
                }
                setStep(WizardStep.Preparing)
                val outcome = controller.run(request) { p ->
                    val next = when (p) {
                        MeasurementProgress.Preparing -> WizardStep.Preparing
                        is MeasurementProgress.Measuring -> WizardStep.Measuring(p.fraction, p.inputPeak, p.phase, position, total)
                        MeasurementProgress.Analyzing -> WizardStep.Analyzing
                    }
                    setStepFromProgress(next)
                }
                partial += outcome
                if (request.speakerClass == SpeakerClass.DEVICE &&
                    MeasurementWarning.CLIPPING in outcome.result.warnings
                ) {
                    deviceVolumeFraction = VolumeMath.reduceAfterClipping(deviceVolumeFraction)
                }
                // A disturbed last position (gust, dropout, ...) gets the chance to be repeated before averaging.
                val disturbed = isDisturbed(outcome.result.warnings)
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
                fail(WizardError.NO_SIGNAL, e.message, position)
            } catch (e: CaptureInterruptedException) {
                val error = if (e.reason == CaptureInterruptedException.Reason.ROUTE_CHANGED) WizardError.ROUTE_CHANGED else WizardError.INTERRUPTED
                fail(error, e.message, position)
            } catch (e: CaptureException) {
                fail(WizardError.of(e), e.message, position)
            } catch (e: Exception) {
                Log.e("AutoVybz", "measurement failed", e)
                fail(WizardError.UNKNOWN, e.message, position)
            }
        }
    }

    /** Keeps earlier positions of a multi-position calibration so only the failed one has to be repeated. */
    private fun fail(error: WizardError, detail: String?, position: Int) {
        if (partial.isNotEmpty() && activeRequest != null) {
            setStep(WizardStep.Error(error, detail, resumePosition = position, measuredPositions = partial.size))
        } else {
            resetSession()
            setStep(WizardStep.Error(error, detail))
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
            // Conversion moment: after the first audible result, the one-time 7-day trial unlocks the
            // precise multi-position flow and expert tools without asking for a wallet first.
            entitlementStore.startTrial()
            setStep(
                WizardStep.Result(
                    outcome = outcome,
                    presetName = name,
                    nameExists = name.trim() in AutoVybzPresetStore.existingNames(getApplication()),
                    profileLimitReached = isProfileLimitReached(name),
                ),
            )
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
        refitJob?.cancel()
        resetSession()
        setStep(WizardStep.Idle)
    }

    fun onPresetNameChanged(name: String) {
        val step = _state.value.step as? WizardStep.Result ?: return
        val trimmed = name.trim()
        setStep(
            step.copy(
                presetName = name,
                nameValid = PresetNaming.isValid(trimmed),
                nameExists = trimmed in AutoVybzPresetStore.existingNames(getApplication()),
                profileLimitReached = isProfileLimitReached(trimmed),
            ),
        )
    }

    /** Step 3: save into the Equalizer314 preset list (optionally activate it right away). */
    fun onSave(apply: Boolean) {
        val step = _state.value.step as? WizardStep.Result ?: return
        val name = step.presetName.trim()
        if (!PresetNaming.isValid(name)) return setStep(step.copy(nameValid = false))
        if (isProfileLimitReached(name)) return setStep(step.copy(profileLimitReached = true))
        refitJob?.cancel()
        val preset = MeasurementController.buildPreset(name, step.outcome)
        val app = getApplication<Application>()
        // Two separate steps with two separate failures: "not stored" and "stored but not audible" need
        // different advice, and neither may crash the app or silently look like success.
        val saved = runCatching { AutoVybzPresetStore.save(app, preset) }
            .onFailure {
                Log.e(TAG, "saving the calibration failed", it)
                Toast.makeText(app, R.string.avz_save_failed, Toast.LENGTH_LONG).show()
            }
            .isSuccess
        if (!saved) return
        if (apply) {
            // Outdoor / party, rescue and device-speaker calibrations always arm the limiter.
            val limiter = settings.enableLimiterOnApply || step.outcome.request.forceLimiter
            runCatching { AutoVybzPresetStore.apply(app, preset, limiter) }
                .onFailure {
                    Log.e(TAG, "applying the calibration failed", it)
                    Toast.makeText(app, R.string.avz_apply_failed, Toast.LENGTH_LONG).show()
                }
        }
        setStep(WizardStep.Saved(name, apply, step.outcome))
    }

    /** Preset built from the current result (for exports before saving). */
    fun currentPreset(): AutoVybzPreset? {
        val step = _state.value.step as? WizardStep.Result ?: return null
        return MeasurementController.buildPreset(step.presetName.trim().ifEmpty { "AutoVybz" }, step.outcome)
    }

    fun onReset() = onCancel()

    private fun refitIfShowingResult() {
        val step = _state.value.step as? WizardStep.Result ?: return
        val s = _state.value
        val req = step.outcome.request.copy(target = s.target, bandCount = s.bandCount, customTarget = settings.customTarget())
        // Only the latest choice counts: a slower, older refit must not overwrite a newer one.
        refitJob?.cancel()
        refitJob = viewModelScope.launch {
            val refit = withContext(Dispatchers.Default) { controller.refit(step.outcome, req) }
            val now = _state.value.step as? WizardStep.Result ?: return@launch
            setStep(now.copy(outcome = refit))
        }
    }

    override fun onCleared() {
        job?.cancel()
        refitJob?.cancel()
        volume.restore()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AutoVybzWizard"

        /** Everything except the purely informational Bluetooth latency suggests repeating a position. */
        fun isDisturbed(warnings: List<MeasurementWarning>): Boolean = warnings.any { it != MeasurementWarning.HIGH_LATENCY }
    }
}
