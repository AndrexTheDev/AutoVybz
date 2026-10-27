// AutoVybz - full-duplex measurement engine on Oboe (AAudio / OpenSL ES).
//
// Plays a pre-rendered stimulus (log sweep + pink noise, ~10 s) on the speaker
// route while capturing the microphone. Input and output run in independent
// callbacks; the input stream is started first so the recording always contains
// the beginning of the stimulus. The exact round-trip latency is recovered later
// from the impulse-response peak in MeasurementAnalyzer, so no clock alignment
// is needed here.
#pragma once

#include <cstring>  // before Oboe.h: FullDuplexStream.h uses memset without including it

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace avz {

/// Prefix of the start() error when a stream cannot run at the requested sample rate; the rest of the
/// string is the rate the device offered. Mirrored by NativeEngine.RATE_MISMATCH_PREFIX on the Kotlin side.
constexpr const char* kRateMismatchPrefix = "rate-mismatch:";

struct EngineConfig {
    int32_t sampleRate = 48000;
    /** oboe::InputPreset value (Unprocessed = 9, VoiceRecognition = 6, Generic = 1). */
    int32_t inputPreset = 9;
    /** 0 = default route; otherwise AudioDeviceInfo.getId() (e.g. USB measurement mic). */
    int32_t inputDeviceId = 0;
    /** 0 = default route; otherwise the active media device (pinned on API 33+). */
    int32_t outputDeviceId = 0;
    /** Extra seconds recorded after the stimulus (latency + room decay). */
    double extraRecordSeconds = 1.0;
    float outputGain = 1.0f;
};

enum class EngineState : int32_t { Idle = 0, Running = 1, Finished = 2, Error = 3 };

class MeasurementEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    MeasurementEngine() = default;
    ~MeasurementEngine() override;

    /** Opens both streams and starts capture + playback. Returns an empty string on success. */
    std::string start(const float* stimulus, int32_t frames, const EngineConfig& cfg);
    void stop();

    EngineState state() const { return state_.load(); }
    /** 0..1 progress of the recording. */
    float progress() const;
    int32_t recordedFrames() const { return recPos_.load(); }
    /** Buffer over-/underruns; valid until the streams are closed (the last value is kept). */
    int32_t xruns() const;
    int32_t actualInputPreset() const { return actualPreset_; }
    int32_t actualInputDeviceId() const { return actualInputDeviceId_; }
    std::string lastError() const;
    /** Peak absolute input level seen so far (live clip indicator). */
    float inputPeak() const { return inputPeak_.load(); }
    /** Copies up to maxFrames recorded frames; returns count. */
    int32_t copyRecording(float* dst, int32_t maxFrames) const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audio, int32_t frames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    oboe::Result openInput(const EngineConfig& cfg, oboe::InputPreset preset, int32_t deviceId);
    oboe::Result openOutput(const EngineConfig& cfg, int32_t deviceId);
    void closeStreams();
    void fail(const std::string& msg);
    /** Xruns of the currently open streams; the caller holds streamMutex_. */
    int32_t liveXrunsLocked() const;

    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::vector<float> stimulus_;
    std::vector<float> recording_;
    std::atomic<int32_t> playPos_{0};
    std::atomic<int32_t> recPos_{0};
    std::atomic<float> inputPeak_{0.0f};
    std::atomic<EngineState> state_{EngineState::Idle};
    /** Guards the stream pointers: the Kotlin side polls from another thread while streams close. */
    mutable std::mutex streamMutex_;
    /** Xruns of the last closed stream pair (a closed stream no longer reports them). */
    int32_t xruns_ = 0;
    int32_t outChannels_ = 2;
    int32_t inChannels_ = 1;
    int32_t actualPreset_ = 0;
    int32_t actualInputDeviceId_ = 0;
    float gain_ = 1.0f;
    mutable std::mutex errMutex_;
    std::string error_;
};

}  // namespace avz
