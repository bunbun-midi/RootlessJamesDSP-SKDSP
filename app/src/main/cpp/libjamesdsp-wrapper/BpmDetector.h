#ifndef BPM_DETECTOR_H
#define BPM_DETECTOR_H

/*
 * Real-time tempo (BPM) estimation for the floating BPM display.
 *
 * Audio thread: process() downmixes and decimates the captured audio (4:1), computes a
 * spectral-flux onset envelope (FFT 256, hop 64 -> ~190 frames/s) and, once per second,
 * estimates the tempo from the autocorrelation of the last ~8 s of onsets
 * (60-200 BPM, harmonic enhancement + log-normal tempo prior around 120 BPM).
 * The beat period is then refined over many beats (autocorrelation peaks at k beat periods, up
 * to ~20 s of audio while the tempo is steady), which makes the estimate precise to about
 * 0.01 % (needed to match a target tempo).
 * No allocations after construction; about a millisecond per estimate.
 *
 * Any thread: bpm()/confidence() read the latest (smoothed) result.
 */

#include <atomic>
#include <cstddef>
#include <vector>

namespace chain {

class BpmDetector {
public:
    explicit BpmDetector(int sampleRate);

    void process(const float* interleaved, int frames);
    void reset();

    /* 0 if unknown (silence / not enough data yet) */
    float bpm() const { return bpmOut.load(std::memory_order_relaxed); }
    /* 0..1 */
    float confidence() const { return confidenceOut.load(std::memory_order_relaxed); }

    static constexpr int kDecimation = 4;
    static constexpr int kFftSize = 256;
    static constexpr int kHop = 64;
    static constexpr int kOnsetFrames = 4096; /* onset history (power of two), ~22 s */
    static constexpr float kMinBpm = 60.0f;
    static constexpr float kMaxBpm = 200.0f;

private:
    void analyzeFrame();
    void estimateTempo();
    void fft(float* re, float* im) const;
    /* Detrended onsets of the last n frames into `work`; returns their energy */
    double detrend(int n);
    float refineLag(float lag, int minLag, int maxLag);

    const float frameRate; /* onset frames per second */

    /* decimation & framing */
    float decimAcc = 0.0f;
    int decimCount = 0;
    std::vector<float> frame;     /* circular, kFftSize */
    int frameWrite = 0;
    int hopCounter = 0;

    /* FFT */
    std::vector<float> window, re, im, prevLogMag;
    std::vector<int> bitRev;
    std::vector<float> twRe, twIm;

    /* onset envelope */
    std::vector<float> onset;     /* circular, kOnsetFrames */
    int onsetWrite = 0;
    int onsetCount = 0;
    int framesSinceEstimate = 0;
    int stableFrames = 0;         /* onset frames since the tempo last changed (jump/silence/reset) */
    std::vector<float> work, acf;

    /* result smoothing */
    float current = 0.0f;
    float candidate = 0.0f;
    int candidateVotes = 0;

    std::atomic<float> bpmOut{0.0f};
    std::atomic<float> confidenceOut{0.0f};
};

} // namespace chain

#endif // BPM_DETECTOR_H
