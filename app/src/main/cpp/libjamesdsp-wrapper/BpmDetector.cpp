#include <algorithm>
#include <cmath>
#include <jni.h>
#include <new>

#include "BpmDetector.h"

namespace chain {

BpmDetector::BpmDetector(int sampleRate) :
    frameRate((float)sampleRate / kDecimation / kHop),
    frame(kFftSize, 0.0f),
    window(kFftSize), re(kFftSize), im(kFftSize), prevLogMag(kFftSize / 2 + 1, 0.0f),
    bitRev(kFftSize), twRe(kFftSize / 2), twIm(kFftSize / 2),
    onset(kOnsetFrames, 0.0f),
    work(kOnsetFrames, 0.0f), acf(kOnsetFrames, 0.0f) {
    for (int i = 0; i < kFftSize; i++)
        window[i] = 0.5f - 0.5f * std::cos(2.0f * (float)M_PI * i / kFftSize);
    int bits = 0;
    while ((1 << bits) < kFftSize) bits++;
    for (int i = 0; i < kFftSize; i++) {
        int r = 0;
        for (int b = 0; b < bits; b++)
            if (i & (1 << b)) r |= 1 << (bits - 1 - b);
        bitRev[i] = r;
    }
    for (int i = 0; i < kFftSize / 2; i++) {
        twRe[i] = std::cos(-2.0f * (float)M_PI * i / kFftSize);
        twIm[i] = std::sin(-2.0f * (float)M_PI * i / kFftSize);
    }
}

void BpmDetector::reset() {
    std::fill(frame.begin(), frame.end(), 0.0f);
    std::fill(prevLogMag.begin(), prevLogMag.end(), 0.0f);
    std::fill(onset.begin(), onset.end(), 0.0f);
    decimAcc = 0.0f; decimCount = 0; frameWrite = 0; hopCounter = 0;
    onsetWrite = 0; onsetCount = 0; framesSinceEstimate = 0;
    current = 0.0f; candidate = 0.0f; candidateVotes = 0;
    bpmOut.store(0.0f);
    confidenceOut.store(0.0f);
}

void BpmDetector::process(const float* in, int frames) {
    for (int i = 0; i < frames; i++) {
        /* Mono downmix + 4:1 decimation (box filter is enough for onset detection) */
        decimAcc += in[2 * i] + in[2 * i + 1];
        if (++decimCount < kDecimation)
            continue;
        frame[frameWrite] = decimAcc * (0.5f / kDecimation);
        frameWrite = (frameWrite + 1) & (kFftSize - 1);
        decimAcc = 0.0f;
        decimCount = 0;
        if (++hopCounter == kHop) {
            hopCounter = 0;
            analyzeFrame();
        }
    }
}

void BpmDetector::fft(float* xr, float* xi) const {
    for (int i = 0; i < kFftSize; i++) {
        const int j = bitRev[i];
        if (j > i) { std::swap(xr[i], xr[j]); std::swap(xi[i], xi[j]); }
    }
    for (int len = 2; len <= kFftSize; len <<= 1) {
        const int half = len >> 1, step = kFftSize / len;
        for (int s = 0; s < kFftSize; s += len) {
            for (int k = 0; k < half; k++) {
                const float wr = twRe[k * step], wi = twIm[k * step];
                const int a = s + k, b = a + half;
                const float tr = xr[b] * wr - xi[b] * wi;
                const float ti = xr[b] * wi + xi[b] * wr;
                xr[b] = xr[a] - tr; xi[b] = xi[a] - ti;
                xr[a] += tr; xi[a] += ti;
            }
        }
    }
}

void BpmDetector::analyzeFrame() {
    /* oldest sample first */
    for (int i = 0; i < kFftSize; i++) {
        re[i] = frame[(frameWrite + i) & (kFftSize - 1)] * window[i];
        im[i] = 0.0f;
    }
    fft(re.data(), im.data());

    /* Spectral flux of log-compressed magnitudes (half-wave rectified) */
    float flux = 0.0f;
    for (int k = 1; k <= kFftSize / 2; k++) {
        const float m = std::log1p(100.0f * std::sqrt(re[k] * re[k] + im[k] * im[k]));
        const float d = m - prevLogMag[k];
        if (d > 0.0f) flux += d;
        prevLogMag[k] = m;
    }
    onset[onsetWrite] = flux;
    onsetWrite = (onsetWrite + 1) & (kOnsetFrames - 1);
    if (onsetCount < kOnsetFrames) onsetCount++;

    if (++framesSinceEstimate >= (int)frameRate) {
        framesSinceEstimate = 0;
        estimateTempo();
    }
}

void BpmDetector::estimateTempo() {
    const int n = std::min(onsetCount, (int)(8.0f * frameRate));
    const int maxLag = (int)std::ceil(60.0f * frameRate / kMinBpm);
    const int minLag = (int)std::floor(60.0f * frameRate / kMaxBpm);
    if (n < (int)(4.0f * frameRate) || 2 * maxLag + 2 >= n)
        return;

    /* Detrend: subtract a moving average (~0.25 s), keep positive part */
    const int start = (onsetWrite - n) & (kOnsetFrames - 1);
    const int avgHalf = std::max(1, (int)(0.125f * frameRate));
    double energy = 0.0;
    for (int i = 0; i < n; i++) {
        float sum = 0.0f;
        int cnt = 0;
        for (int j = std::max(0, i - avgHalf); j <= std::min(n - 1, i + avgHalf); j++) {
            sum += onset[(start + j) & (kOnsetFrames - 1)];
            cnt++;
        }
        const float v = onset[(start + i) & (kOnsetFrames - 1)] - sum / cnt;
        work[i] = v > 0.0f ? v : 0.0f;
        energy += (double)work[i] * work[i];
    }
    if (energy < 1e-6) {
        /* silence */
        current = 0.0f;
        bpmOut.store(0.0f);
        confidenceOut.store(0.0f);
        return;
    }

    /* Autocorrelation (normalized by overlap length) up to 3x the longest beat lag */
    const int lagLimit = std::min(n - 1, 3 * maxLag + 2);
    for (int lag = 0; lag <= lagLimit; lag++) {
        double s = 0.0;
        for (int i = lag; i < n; i++)
            s += (double)work[i] * work[i - lag];
        acf[lag] = (float)(s / (n - lag));
    }

    /* Score each beat period: its acf plus its multiples (bar-level periodicity), weighted by a
       log-normal prior that prefers tempos around 120 BPM (resolves half/double tempo choices) */
    int bestLag = -1;
    float bestScore = 0.0f;
    double scoreSum = 0.0;
    int scoreCount = 0;
    /* Weights tuned on synthetic rock/house/sparse/16ths/syncopated/half-time/shuffle/waltz patterns
       (60-180 BPM): beat lag, its double (bar-level), half (subdivision), and a penalty on the triple
       lag (suppresses 2:3 / 3:4 confusions); tempo prior: log-normal around 120 BPM, sigma 0.9 octave */
    const float sigma = 0.9f, w2 = 0.5f, w4 = 0.0f, wHalf = 0.5f, w3 = -0.5f;
    auto acfAt = [&](float l) {
        const int i = (int)l;
        if (i < 1 || i + 1 > lagLimit) return 0.0f;
        const float f = l - i;
        return acf[i] * (1.0f - f) + acf[i + 1] * f;
    };
    for (int lag = minLag; lag <= maxLag; lag++) {
        float score = acf[lag];
        if (2 * lag <= lagLimit) score += w2 * acf[2 * lag];
        if (4 * lag <= lagLimit) score += w4 * acf[4 * lag];
        if (3 * lag <= lagLimit) score += w3 * acf[3 * lag];
        score += wHalf * acfAt(lag * 0.5f);
        const float bpm = 60.0f * frameRate / lag;
        const float octaves = std::log2(bpm / 120.0f);
        score *= std::exp(-0.5f * octaves * octaves / (sigma * sigma));
        if (score > bestScore) { bestScore = score; bestLag = lag; }
        scoreSum += score;
        scoreCount++;
    }
    if (bestLag < 0 || bestScore <= 0.0f)
        return;

    /* Sub-frame precision: parabolic interpolation of the acf around the peak */
    float lag = (float)bestLag;
    if (bestLag > minLag && bestLag < maxLag) {
        const float a = acf[bestLag - 1], b = acf[bestLag], c = acf[bestLag + 1];
        const float denom = a - 2.0f * b + c;
        if (denom < 0.0f)
            lag += std::clamp(0.5f * (a - c) / denom, -0.5f, 0.5f);
    }
    float estimate = 60.0f * frameRate / lag;

    const float conf = std::clamp((float)(bestScore / (scoreSum / scoreCount) - 1.0f) / 3.0f, 0.0f, 1.0f);

    /* Smoothing: follow small changes, switch to a different tempo after 3 agreeing estimates */
    if (current > 0.0f && std::fabs(estimate - current) / current < 0.04f) {
        current += 0.3f * (estimate - current);
        candidateVotes = 0;
    } else if (current <= 0.0f) {
        current = estimate;
    } else if (candidate > 0.0f && std::fabs(estimate - candidate) / candidate < 0.04f) {
        if (++candidateVotes >= 2) {
            current = estimate;
            candidateVotes = 0;
        }
        candidate = estimate;
    } else {
        candidate = estimate;
        candidateVotes = 0;
    }

    bpmOut.store(current);
    confidenceOut.store(conf);
}

} // namespace chain

// ------------------------------------------------------------------------------------------------
// JNI: me.timschneeberger.rootlessjamesdsp.interop.BpmDetector
// ------------------------------------------------------------------------------------------------

static inline chain::BpmDetector* detector(jlong handle) {
    return reinterpret_cast<chain::BpmDetector*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeCreate(JNIEnv*, jclass, jint sampleRate)
{
    if (sampleRate <= 0)
        return 0;
    return reinterpret_cast<jlong>(new (std::nothrow) chain::BpmDetector(sampleRate));
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeDestroy(JNIEnv*, jclass, jlong handle)
{
    delete detector(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeReset(JNIEnv*, jclass, jlong handle)
{
    if (handle != 0)
        detector(handle)->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                                          jfloatArray input, jint frames)
{
    if (handle == 0 || input == nullptr || frames <= 0 || env->GetArrayLength(input) < frames * 2)
        return;
    jfloat* in = (jfloat*)env->GetPrimitiveArrayCritical(input, nullptr);
    if (in == nullptr)
        return;
    detector(handle)->process(in, frames);
    env->ReleasePrimitiveArrayCritical(input, in, JNI_ABORT);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeBpm(JNIEnv*, jclass, jlong handle)
{
    return handle != 0 ? detector(handle)->bpm() : 0.0f;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_BpmDetector_nativeConfidence(JNIEnv*, jclass, jlong handle)
{
    return handle != 0 ? detector(handle)->confidence() : 0.0f;
}
