#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "PitchShifter.h"

/* libjamesdsp FHT (fast Hartley transform). Input must be in bit-reversed order; output is in
 * natural order. DHT: H[k] = sum x[n] * (cos(2*pi*n*k/N) + sin(2*pi*n*k/N)) */
extern "C" {
void LLdiscreteHartleyFloat(float *A, const int nPoints, const float *sinTab);
void fhtsinHalfTblFloat(float *dst, unsigned int n);
void LLbitReversalTbl(unsigned *dst, uint32_t n);
}

namespace chain {

static constexpr float kPi = 3.14159265358979f;
static constexpr float kTwoPi = 6.28318530717959f;
static constexpr double kTwoPiD = 6.283185307179586;

static inline float wrapPhase(float x) {
    // Wrap to [-pi, pi]; inputs are at most a few turns away
    x = std::fmod(x + kPi, kTwoPi);
    if (x < 0.0f)
        x += kTwoPi;
    return x - kPi;
}

PitchShifter::PitchShifter() {
    const int N = kFrameSize;

    bitRev.resize(N);
    sinTab.resize(N); // fhtsinHalfTblFloat() writes N entries despite its name
    LLbitReversalTbl(bitRev.data(), N);
    fhtsinHalfTblFloat(sinTab.data(), N);

    window.resize(N);
    for (int n = 0; n < N; n++)
        window[n] = 0.5f - 0.5f * std::cos(kTwoPi * (float)n / (float)N); // periodic Hann

    binAdvance.resize(kBins);
    for (int k = 0; k < kBins; k++)
        binAdvance[k] = (float)std::remainder(kTwoPiD * (double)k * kHop / N, kTwoPiD);

    for (int c = 0; c < 2; c++) {
        inFifo[c].assign(N, 0.0f);
        outFifo[c].assign(kHop, 0.0f);
        outAccum[c].assign(N, 0.0f);
        re[c].assign(kBins, 0.0f);
        im[c].assign(kBins, 0.0f);
        prevRe[c].assign(kBins, 0.0f);
        prevIm[c].assign(kBins, 0.0f);
        outRe[c].assign(kBins, 0.0f);
        outIm[c].assign(kBins, 0.0f);
    }
    fht.assign(N, 0.0f);
    mag.assign(kBins, 0.0f);

    // Reserve worst case so the audio thread never allocates
    peaks.reserve(kBins);
    prevPeaks.reserve(kBins);
    regionStart.reserve(kBins + 1);
}

void PitchShifter::setParams(bool enable, float semitones) {
    if (enable && !enabled)
        needsReset = true; // performed on the audio thread
    enabled = enable;
    semitones = std::clamp(semitones, -24.0f, 24.0f);
    ratio = std::exp2(semitones / 12.0f);
}

void PitchShifter::reset() {
    for (int c = 0; c < 2; c++) {
        std::fill(inFifo[c].begin(), inFifo[c].end(), 0.0f);
        std::fill(outFifo[c].begin(), outFifo[c].end(), 0.0f);
        std::fill(outAccum[c].begin(), outAccum[c].end(), 0.0f);
        std::fill(prevRe[c].begin(), prevRe[c].end(), 0.0f);
        std::fill(prevIm[c].begin(), prevIm[c].end(), 0.0f);
    }
    peaks.clear();
    prevPeaks.clear();
    rover = kRoverStart;
    needsReset = false;
}

void PitchShifter::process(float* left, float* right, size_t n) {
    if (needsReset)
        reset();

    float* inL = inFifo[0].data();
    float* inR = inFifo[1].data();
    for (size_t i = 0; i < n; i++) {
        inL[rover] = left[i];
        inR[rover] = right[i];
        left[i] = outFifo[0][rover - kRoverStart];
        right[i] = outFifo[1][rover - kRoverStart];

        if (++rover >= kFrameSize) {
            rover = kRoverStart;
            processFrame();
        }
    }
}

void PitchShifter::processFrame() {
    const int N = kFrameSize;
    const int H = kHop;
    const int B = kBins;
    float* buf = fht.data();

    // ---- Analysis: windowed FHT -> complex spectrum (X[k] = E[k] - jO[k]) ----
    float maxMag = 0.0f;
    for (int c = 0; c < 2; c++) {
        const float* x = inFifo[c].data();
        for (int i = 0; i < N; i++)
            buf[bitRev[i]] = x[i] * window[i];
        LLdiscreteHartleyFloat(buf, N, sinTab.data());

        float* r = re[c].data();
        float* q = im[c].data();
        r[0] = buf[0];
        q[0] = 0.0f;
        for (int k = 1; k < N / 2; k++) {
            r[k] = 0.5f * (buf[k] + buf[N - k]);
            q[k] = -0.5f * (buf[k] - buf[N - k]);
        }
        r[N / 2] = buf[N / 2];
        q[N / 2] = 0.0f;
    }
    for (int k = 0; k < B; k++) {
        const float m = re[0][k] * re[0][k] + im[0][k] * im[0][k] + re[1][k] * re[1][k] + im[1][k] * im[1][k];
        mag[k] = m;
        maxMag = std::max(maxMag, m);
    }

    for (int c = 0; c < 2; c++) {
        std::fill(outRe[c].begin(), outRe[c].end(), 0.0f);
        std::fill(outIm[c].begin(), outIm[c].end(), 0.0f);
    }

    // ---- Peak picking on the combined (L+R) power spectrum ----
    std::swap(peaks, prevPeaks);
    peaks.clear();
    if (maxMag > 1e-16f) {
        const float floor = maxMag * 1e-10f; // -100 dB relative to the strongest peak
        for (int k = 2; k < B - 2; k++) {
            const float m = mag[k];
            if (m > floor && m > mag[k - 1] && m >= mag[k + 1] && m > mag[k - 2] && m >= mag[k + 2])
                peaks.push_back({k, 0.0f});
        }
    }

    // Regions of influence: boundaries at the spectral minimum between adjacent peaks
    regionStart.clear();
    const int numPeaks = (int)peaks.size();
    for (int p = 0; p < numPeaks; p++) {
        if (p == 0) {
            regionStart.push_back(0);
            continue;
        }
        int lo = peaks[p - 1].bin, hi = peaks[p].bin, minBin = lo + 1;
        for (int k = lo + 1; k < hi; k++)
            if (mag[k] < mag[minBin])
                minBin = k;
        regionStart.push_back(minBin);
    }
    regionStart.push_back(B);

    // ---- Shift each region; phase-locked to its peak ----
    size_t prevIdx = 0;
    for (int p = 0; p < numPeaks; p++) {
        const int kp = peaks[p].bin;

        // Reference spectrum for frequency estimation: L+R, unless it cancels out (anti-phase)
        float cRe = re[0][kp] + re[1][kp], cIm = im[0][kp] + im[1][kp];
        float pRe = prevRe[0][kp] + prevRe[1][kp], pIm = prevIm[0][kp] + prevIm[1][kp];
        if (cRe * cRe + cIm * cIm < 0.01f * mag[kp]) {
            const int ch = (re[0][kp] * re[0][kp] + im[0][kp] * im[0][kp] >=
                            re[1][kp] * re[1][kp] + im[1][kp] * im[1][kp]) ? 0 : 1;
            cRe = re[ch][kp]; cIm = im[ch][kp];
            pRe = prevRe[ch][kp]; pIm = prevIm[ch][kp];
        }

        // Instantaneous frequency from the phase advance since the previous frame
        const float dPhi = std::atan2(cIm * pRe - cRe * pIm, cRe * pRe + cIm * pIm);
        const float dev = wrapPhase(dPhi - binAdvance[kp]);
        const float omega = kTwoPi * (float)kp / (float)N + dev / (float)H; // rad/sample
        const float dOmega = omega * (ratio - 1.0f);
        const int shift = (int)std::lrint(dOmega * (float)N / kTwoPi);

        // Phase rotation accumulates per hop; continue from the nearest peak of the last frame
        while (prevIdx + 1 < prevPeaks.size() &&
               std::abs(prevPeaks[prevIdx + 1].bin - kp) <= std::abs(prevPeaks[prevIdx].bin - kp))
            prevIdx++;
        const float prevAcc = prevPeaks.empty() ? 0.0f : prevPeaks[prevIdx].phaseAcc;
        const float acc = wrapPhase(prevAcc + dOmega * (float)H);
        peaks[p].phaseAcc = acc;

        const float zr = std::cos(acc), zi = std::sin(acc);
        const int start = std::max(regionStart[p], std::max(0, -shift));
        const int end = std::min(regionStart[p + 1], B - std::max(0, shift));
        for (int c = 0; c < 2; c++) {
            const float* r = re[c].data();
            const float* q = im[c].data();
            float* yr = outRe[c].data();
            float* yi = outIm[c].data();
            for (int k = start; k < end; k++) {
                yr[k + shift] += r[k] * zr - q[k] * zi;
                yi[k + shift] += r[k] * zi + q[k] * zr;
            }
        }
    }

    for (int c = 0; c < 2; c++) {
        std::swap(prevRe[c], re[c]);
        std::swap(prevIm[c], im[c]);
    }

    // ---- Synthesis: inverse FHT, window, overlap-add ----
    const float scale = 1.0f / ((float)N * 1.5f); // 1/N for the inverse transform, 1.5 = sum of Hann^2 at 75% overlap
    for (int c = 0; c < 2; c++) {
        const float* yr = outRe[c].data();
        const float* yi = outIm[c].data();
        buf[bitRev[0]] = yr[0];
        buf[bitRev[N / 2]] = yr[N / 2];
        for (int k = 1; k < N / 2; k++) {
            buf[bitRev[k]] = yr[k] - yi[k];
            buf[bitRev[N - k]] = yr[k] + yi[k];
        }
        LLdiscreteHartleyFloat(buf, N, sinTab.data());

        float* acc = outAccum[c].data();
        for (int i = 0; i < N; i++)
            acc[i] += buf[i] * window[i] * scale;

        std::memcpy(outFifo[c].data(), acc, H * sizeof(float));
        std::memmove(acc, acc + H, (N - H) * sizeof(float));
        std::memset(acc + N - H, 0, H * sizeof(float));

        float* in = inFifo[c].data();
        std::memmove(in, in + H, kRoverStart * sizeof(float));
    }
}

} // namespace chain
