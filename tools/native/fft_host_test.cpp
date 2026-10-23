#include "avz_fft.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
int main() {
    avz::Fft fft; std::mt19937 rng(1); std::uniform_real_distribution<double> d(-1, 1);
    double worst = 0;
    for (size_t n : {1u, 2u, 8u, 64u, 1024u}) {
        std::vector<double> re(n), im(n), r0, i0;
        for (size_t k = 0; k < n; ++k) { re[k] = d(rng); im[k] = d(rng); }
        r0 = re; i0 = im;
        fft.transform(re.data(), im.data(), n, false);
        for (size_t k = 0; k < n; ++k) {  // naive DFT, e^{-i}
            double sr = 0, si = 0;
            for (size_t t = 0; t < n; ++t) { double a = -2 * M_PI * k * t / n; sr += r0[t]*cos(a) - i0[t]*sin(a); si += r0[t]*sin(a) + i0[t]*cos(a); }
            worst = std::fmax(worst, std::fmax(fabs(sr - re[k]), fabs(si - im[k])));
        }
        fft.transform(re.data(), im.data(), n, true);
        for (size_t k = 0; k < n; ++k) worst = std::fmax(worst, std::fmax(fabs(re[k]-r0[k]), fabs(im[k]-i0[k])));
    }
    std::vector<double> a(3), b(3);
    bool rejected = !fft.transform(a.data(), b.data(), 3, false);
    printf("max error %.3e, non-pow2 rejected: %s\n", worst, rejected ? "yes" : "no");
    return (worst < 1e-9 && rejected) ? 0 : 1;
}
