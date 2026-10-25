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

struct EngineConfig {
    int32_t sampleRate = 48000;
    /** oboe::InputPreset value (Unprocessed = 9, VoiceRecognition = 6, Generic = 1). */
    int32_t inputPreset = 9;
    /** 0 = default route; otherwise AudioDeviceInfo.getId() (e.g. USB measurement mic). */
    int32_t inputDeviceId = 0;
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
    int32_t xruns() const;
    int32_t actualInputPreset() const { return actualPreset_; }
    std::string lastError() const;
    /** Peak absolute input level seen so far (live clip indicator). */
    float inputPeak() const { return inputPeak_.load(); }
    /** Copies up to maxFrames recorded frames; returns count. */
    int32_t copyRecording(float* dst, int32_t maxFrames) const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audio, int32_t frames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    oboe::Result openInput(const EngineConfig& cfg, oboe::InputPreset preset);
    void closeStreams();
    void fail(const std::string& msg);

    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::vector<float> stimulus_;
    std::vector<float> recording_;
    std::atomic<int32_t> playPos_{0};
    std::atomic<int32_t> recPos_{0};
    std::atomic<float> inputPeak_{0.0f};
    std::atomic<EngineState> state_{EngineState::Idle};
    int32_t outChannels_ = 2;
    int32_t inChannels_ = 1;
    int32_t actualPreset_ = 0;
    float gain_ = 1.0f;
    mutable std::mutex errMutex_;
    std::string error_;
};

}  // namespace avz
