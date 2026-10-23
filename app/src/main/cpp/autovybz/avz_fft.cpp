#include "avz_fft.h"

#include <cmath>
#include <utility>

namespace avz {

void Fft::ensureTwiddles(size_t n) {
    if (n == size_) return;
    const size_t half = n / 2;
    cos_.resize(half);
    sin_.resize(half);
    for (size_t k = 0; k < half; ++k) {
        const double a = 2.0 * M_PI * static_cast<double>(k) / static_cast<double>(n);
        cos_[k] = std::cos(a);
        sin_[k] = std::sin(a);
    }
    size_ = n;
}

bool Fft::transform(double* re, double* im, size_t n, bool inverse) {
    if (!isPowerOfTwo(n)) return false;
    if (n == 1) return true;

    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }

    ensureTwiddles(n);
    const double sign = inverse ? 1.0 : -1.0;
    for (size_t len = 2; len <= n; len <<= 1) {
        const size_t half = len >> 1;
        const size_t step = n / len;
        for (size_t start = 0; start < n; start += len) {
            for (size_t k = 0; k < half; ++k) {
                const double wr = cos_[k * step];
                const double wi = sign * sin_[k * step];
                const size_t a = start + k;
                const size_t b = a + half;
                const double xr = re[b] * wr - im[b] * wi;
                const double xi = re[b] * wi + im[b] * wr;
                re[b] = re[a] - xr;
                im[b] = im[a] - xi;
                re[a] += xr;
                im[a] += xi;
            }
        }
    }
    if (inverse) {
        const double s = 1.0 / static_cast<double>(n);
        for (size_t i = 0; i < n; ++i) {
            re[i] *= s;
            im[i] *= s;
        }
    }
    return true;
}

}  // namespace avz
