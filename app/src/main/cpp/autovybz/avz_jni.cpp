// JNI bridge for com.andrexthedev.autovybz.measurement.audio.NativeEngine
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "avz_fft.h"
#include "measurement_engine.h"

namespace {
avz::Fft gFft;  // FFT calls are serialised by the Kotlin side (synchronized).

avz::MeasurementEngine* engine(jlong h) { return reinterpret_cast<avz::MeasurementEngine*>(h); }
}  // namespace

#define AVZ_FN(name) Java_com_andrexthedev_autovybz_measurement_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL AVZ_FN(nativeFft)(JNIEnv* env, jclass, jdoubleArray re, jdoubleArray im,
                                              jboolean inverse) {
    const jsize n = env->GetArrayLength(re);
    if (n != env->GetArrayLength(im)) return JNI_FALSE;
    // Copy out instead of GetPrimitiveArrayCritical: transforms of 2^20 points take tens of ms.
    std::vector<double> r(static_cast<size_t>(n)), i(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(re, 0, n, r.data());
    env->GetDoubleArrayRegion(im, 0, n, i.data());
    if (!gFft.transform(r.data(), i.data(), static_cast<size_t>(n), inverse == JNI_TRUE)) return JNI_FALSE;
    env->SetDoubleArrayRegion(re, 0, n, r.data());
    env->SetDoubleArrayRegion(im, 0, n, i.data());
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL AVZ_FN(nativeCreate)(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new avz::MeasurementEngine());
}

JNIEXPORT void JNICALL AVZ_FN(nativeDestroy)(JNIEnv*, jclass, jlong h) { delete engine(h); }

JNIEXPORT jstring JNICALL AVZ_FN(nativeStart)(JNIEnv* env, jclass, jlong h, jfloatArray stimulus, jint sampleRate,
                                               jint inputPreset, jint inputDeviceId, jint outputDeviceId,
                                               jdouble extraSeconds, jfloat gain) {
    const jsize n = env->GetArrayLength(stimulus);
    std::vector<float> s(static_cast<size_t>(n));
    env->GetFloatArrayRegion(stimulus, 0, n, s.data());
    avz::EngineConfig cfg;
    cfg.sampleRate = sampleRate;
    cfg.inputPreset = inputPreset;
    cfg.inputDeviceId = inputDeviceId;
    cfg.outputDeviceId = outputDeviceId;
    cfg.extraRecordSeconds = extraSeconds;
    cfg.outputGain = gain;
    const std::string err = engine(h)->start(s.data(), n, cfg);
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL AVZ_FN(nativeStop)(JNIEnv*, jclass, jlong h) { engine(h)->stop(); }

JNIEXPORT jint JNICALL AVZ_FN(nativeState)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(engine(h)->state());
}

JNIEXPORT jfloat JNICALL AVZ_FN(nativeProgress)(JNIEnv*, jclass, jlong h) { return engine(h)->progress(); }

JNIEXPORT jfloat JNICALL AVZ_FN(nativeInputPeak)(JNIEnv*, jclass, jlong h) { return engine(h)->inputPeak(); }

JNIEXPORT jint JNICALL AVZ_FN(nativeXruns)(JNIEnv*, jclass, jlong h) { return engine(h)->xruns(); }

JNIEXPORT jint JNICALL AVZ_FN(nativeActualPreset)(JNIEnv*, jclass, jlong h) {
    return engine(h)->actualInputPreset();
}

JNIEXPORT jstring JNICALL AVZ_FN(nativeLastError)(JNIEnv* env, jclass, jlong h) {
    const std::string e = engine(h)->lastError();
    return e.empty() ? nullptr : env->NewStringUTF(e.c_str());
}

JNIEXPORT jfloatArray JNICALL AVZ_FN(nativeRecording)(JNIEnv* env, jclass, jlong h) {
    const jint n = engine(h)->recordedFrames();
    std::vector<float> buf(static_cast<size_t>(n));
    const jint got = engine(h)->copyRecording(buf.data(), n);
    jfloatArray out = env->NewFloatArray(got);
    if (out != nullptr && got > 0) env->SetFloatArrayRegion(out, 0, got, buf.data());
    return out;
}

}  // extern "C"
