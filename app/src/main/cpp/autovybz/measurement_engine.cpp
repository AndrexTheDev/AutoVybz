#include "measurement_engine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "AutoVybzEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace avz {

MeasurementEngine::~MeasurementEngine() { closeStreams(); }

oboe::Result MeasurementEngine::openInput(const EngineConfig& cfg, oboe::InputPreset preset,
                                          int32_t deviceId) {
    oboe::AudioStreamBuilder b;
    // No PerformanceMode::LowLatency: the stimulus/response alignment is
    // recovered from the impulse-response peak, so latency does not matter -
    // but the small low-latency buffers make an 11 s full-duplex capture
    // needlessly xrun-prone (glitches = gaps in stimulus or recording).
    b.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setChannelConversionAllowed(true)
        ->setSampleRate(cfg.sampleRate)
        // Conversion stays enabled as a last resort, but the Kotlin side negotiates the device's native
        // rate up front (CaptureRates), so in the normal case neither direction is resampled.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
        ->setInputPreset(preset)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (deviceId > 0) b.setDeviceId(deviceId);
    const oboe::Result r = b.openStream(input_);
    if (r == oboe::Result::OK) {
        // Use the whole ring buffer - with the large default frames-per-burst of
        // the non-low-latency path this absorbs seconds of scheduling jitter.
        input_->setBufferSizeInFrames(input_->getBufferCapacityInFrames());
    } else {
        input_.reset();
    }
    return r;
}

oboe::Result MeasurementEngine::openOutput(const EngineConfig& cfg, int32_t deviceId) {
    oboe::AudioStreamBuilder ob;
    ob.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setChannelConversionAllowed(true)
        ->setSampleRate(cfg.sampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (deviceId > 0) ob.setDeviceId(deviceId);
    const oboe::Result r = ob.openStream(output_);
    if (r == oboe::Result::OK) {
        output_->setBufferSizeInFrames(output_->getBufferCapacityInFrames());
    } else {
        output_.reset();
    }
    return r;
}

std::string MeasurementEngine::start(const float* stimulus, int32_t frames, const EngineConfig& cfg) {
    closeStreams();
    if (frames <= 0) return "empty stimulus";
    stimulus_.assign(stimulus, stimulus + frames);
    const auto recFrames = static_cast<int32_t>(frames + std::lround(cfg.extraRecordSeconds * cfg.sampleRate));
    recording_.assign(static_cast<size_t>(recFrames), 0.0f);
    playPos_ = 0;
    recPos_ = 0;
    inputPeak_ = 0.0f;
    xruns_ = 0;
    actualPreset_ = 0;
    actualInputDeviceId_ = 0;
    {
        std::lock_guard<std::mutex> lk(errMutex_);
        error_.clear();
    }

    // --- input (preferred preset, then VoiceRecognition, then Generic) ---
    // Each preset is tried on the pinned device first and then on the default route: a device that
    // vanished between the route check and here must not cost the measurement.
    const oboe::InputPreset presets[] = {static_cast<oboe::InputPreset>(cfg.inputPreset),
                                        oboe::InputPreset::VoiceRecognition, oboe::InputPreset::Generic};
    int32_t devices[2] = {cfg.inputDeviceId, 0};
    const int32_t deviceCount = cfg.inputDeviceId > 0 ? 2 : 1;
    oboe::Result r = oboe::Result::ErrorInternal;
    bool inputOpen = false;
    for (auto p : presets) {
        for (int32_t i = 0; i < deviceCount && !inputOpen; ++i) {
            r = openInput(cfg, p, devices[i]);
            if (r == oboe::Result::OK) {
                actualPreset_ = static_cast<int32_t>(p);
                actualInputDeviceId_ = devices[i];
                inputOpen = true;
            } else {
                LOGW("input preset %d / device %d failed: %s", static_cast<int>(p), devices[i],
                     oboe::convertToText(r));
            }
        }
        if (inputOpen) break;
    }
    if (!inputOpen) return std::string("Cannot open microphone: ") + oboe::convertToText(r);
    inChannels_ = input_->getChannelCount();
    if (input_->getSampleRate() != cfg.sampleRate) {
        const int32_t actual = input_->getSampleRate();
        closeStreams();
        return std::string(kRateMismatchPrefix) + std::to_string(actual);
    }

    // --- output ---
    const int32_t outputDevices[2] = {cfg.outputDeviceId, 0};
    const int32_t outputDeviceCount = cfg.outputDeviceId > 0 ? 2 : 1;
    bool outputOpen = false;
    for (int32_t i = 0; i < outputDeviceCount && !outputOpen; ++i) {
        r = openOutput(cfg, outputDevices[i]);
        if (r == oboe::Result::OK) {
            outputOpen = true;
        } else {
            LOGW("output on device %d failed: %s", outputDevices[i], oboe::convertToText(r));
        }
    }
    if (!outputOpen) {
        closeStreams();
        return std::string("Cannot open speaker output: ") + oboe::convertToText(r);
    }
    outChannels_ = output_->getChannelCount();
    if (output_->getSampleRate() != cfg.sampleRate) {
        const int32_t actual = output_->getSampleRate();
        closeStreams();
        return std::string(kRateMismatchPrefix) + std::to_string(actual);
    }

    state_ = EngineState::Running;
    // Capture first so the recording contains the leading silence + whole stimulus.
    r = input_->requestStart();
    if (r == oboe::Result::OK) r = output_->requestStart();
    if (r != oboe::Result::OK) {
        state_ = EngineState::Error;
        closeStreams();
        return std::string("Cannot start audio: ") + oboe::convertToText(r);
    }
    LOGI("started: preset=%d inDevice=%d in=%s out=%s inBurst=%d outBurst=%d", actualPreset_,
         actualInputDeviceId_, oboe::convertToText(input_->getAudioApi()),
         oboe::convertToText(output_->getAudioApi()), input_->getFramesPerBurst(),
         output_->getFramesPerBurst());
    return {};
}

oboe::DataCallbackResult MeasurementEngine::onAudioReady(oboe::AudioStream* stream, void* audio, int32_t frames) {
    if (stream->getDirection() == oboe::Direction::Output) {
        auto* out = static_cast<float*>(audio);
        const int32_t total = static_cast<int32_t>(stimulus_.size());
        int32_t pos = playPos_.load(std::memory_order_relaxed);
        for (int32_t i = 0; i < frames; ++i) {
            const float s = pos < total ? stimulus_[pos] * gain_ : 0.0f;
            for (int32_t c = 0; c < outChannels_; ++c) out[i * outChannels_ + c] = s;
            if (pos < total) ++pos;
        }
        playPos_.store(pos, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    const auto* in = static_cast<const float*>(audio);
    const int32_t cap = static_cast<int32_t>(recording_.size());
    int32_t pos = recPos_.load(std::memory_order_relaxed);
    float peak = inputPeak_.load(std::memory_order_relaxed);
    for (int32_t i = 0; i < frames && pos < cap; ++i) {
        // Channel 0 only, never an average: if a device returns more than the
        // requested mono channel (e.g. bottom + top mics of an UNPROCESSED
        // stereo stream), averaging two spaced microphones would comb-filter
        // the measurement. Channel 0 is the primary (bottom) microphone -
        // the one the compensation profiles describe.
        const float s = in[i * inChannels_];
        recording_[pos++] = s;
        peak = std::max(peak, std::fabs(s));
    }
    inputPeak_.store(peak, std::memory_order_relaxed);
    recPos_.store(pos, std::memory_order_release);
    if (pos >= cap) {
        EngineState expected = EngineState::Running;
        state_.compare_exchange_strong(expected, EngineState::Finished);
        return oboe::DataCallbackResult::Stop;
    }
    return oboe::DataCallbackResult::Continue;
}

void MeasurementEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    if (state_.load() == EngineState::Running) {
        fail(std::string(stream->getDirection() == oboe::Direction::Input ? "Microphone" : "Speaker") +
             " stream closed: " + oboe::convertToText(error));
    }
}

void MeasurementEngine::fail(const std::string& msg) {
    {
        std::lock_guard<std::mutex> lk(errMutex_);
        error_ = msg;
    }
    state_ = EngineState::Error;
    LOGW("%s", msg.c_str());
}

void MeasurementEngine::stop() {
    EngineState expected = EngineState::Running;
    state_.compare_exchange_strong(expected, EngineState::Idle);
    closeStreams();
}

int32_t MeasurementEngine::liveXrunsLocked() const {
    int32_t n = 0;
    if (input_) {
        auto x = input_->getXRunCount();
        if (x) n += x.value();
    }
    if (output_) {
        auto x = output_->getXRunCount();
        if (x) n += x.value();
    }
    return n;
}

int32_t MeasurementEngine::xruns() const {
    std::lock_guard<std::mutex> lk(streamMutex_);
    if (!input_ && !output_) return xruns_;
    return liveXrunsLocked();
}

void MeasurementEngine::closeStreams() {
    std::lock_guard<std::mutex> lk(streamMutex_);
    // Snapshot before closing: a closed stream no longer reports its xruns, and the Kotlin side may read
    // the counter after the run for its glitch warning.
    if (input_ || output_) xruns_ = liveXrunsLocked();
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
}

float MeasurementEngine::progress() const {
    if (recording_.empty()) return 0.0f;
    return static_cast<float>(recPos_.load()) / static_cast<float>(recording_.size());
}

std::string MeasurementEngine::lastError() const {
    std::lock_guard<std::mutex> lk(errMutex_);
    return error_;
}

int32_t MeasurementEngine::copyRecording(float* dst, int32_t maxFrames) const {
    const int32_t n = std::min(maxFrames, recPos_.load(std::memory_order_acquire));
    if (n > 0) std::memcpy(dst, recording_.data(), static_cast<size_t>(n) * sizeof(float));
    return n;
}

}  // namespace avz
