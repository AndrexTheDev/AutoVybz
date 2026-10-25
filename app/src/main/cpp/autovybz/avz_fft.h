// AutoVybz - native FFT used by the measurement analysis (JNI: NativeEngine.fft).
// Must stay numerically equivalent to KotlinFft (measurement/dsp/Fft.kt):
// forward transform unscaled with e^{-i}, inverse scaled by 1/n.
#pragma once

#include <cstddef>
#include <vector>

namespace avz {

class Fft {
public:
    /** In-place radix-2 complex FFT. n must be a power of two. Returns false otherwise. */
    bool transform(double* re, double* im, size_t n, bool inverse);

private:
    void ensureTwiddles(size_t n);
    size_t size_ = 0;
    std::vector<double> cos_;
    std::vector<double> sin_;
};

inline bool isPowerOfTwo(size_t n) { return n != 0 && (n & (n - 1)) == 0; }

}  // namespace avz
