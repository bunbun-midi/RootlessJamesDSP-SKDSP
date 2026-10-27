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
 * The estimate (about 1.5 ms on a desktop CPU, several ms on a phone) runs on a worker thread:
 * once per second the audio thread hands over a copy of the onset history and continues, so
 * the audio thread only does the cheap per-block analysis (tens of microseconds).
 *
 * Any thread: bpm()/confidence() read the latest (smoothed) result.
 */

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <mutex>
#include <thread>
#include <vector>

namespace chain {

class BpmDetector {
public:
    /* asyncEstimate = false runs the estimate inline (deterministic, for tests) */
    explicit BpmDetector(int sampleRate, bool asyncEstimate = true);
    ~BpmDetector();
    BpmDetector(const BpmDetector&) = delete;
    BpmDetector& operator=(const BpmDetector&) = delete;

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
    void requestEstimate();
    void workerLoop();
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

    /* onset envelope (audio thread) */
    std::vector<float> onset;     /* circular, kOnsetFrames */
    int onsetWrite = 0;
    int onsetCount = 0;
    int framesSinceEstimate = 0;
    int stableFrames = 0;         /* onset frames since the tempo last changed (jump/silence/reset) */

    /* estimate input: copy of the onset state, written by the audio thread while !busy */
    std::vector<float> eOnset;
    int eWrite = 0, eCount = 0, eStable = 0;
    unsigned eGeneration = 0;

    /* estimate state (worker thread, or the audio thread when not async) */
    std::vector<float> work, acf;
    unsigned workerGeneration = 0;
    float current = 0.0f;
    float candidate = 0.0f;
    int candidateVotes = 0;

    /* hand-over */
    const bool async;
    std::atomic<bool> busy{false};
    std::atomic<unsigned> generation{0};    /* incremented by reset() */
    std::atomic<int> stableLimit{-1};       /* estimate -> audio thread: cap stableFrames */
    std::mutex mutex;
    std::condition_variable wake;
    bool pending = false, quit = false;
    std::thread worker;

    std::atomic<float> bpmOut{0.0f};
    std::atomic<float> confidenceOut{0.0f};
};

} // namespace chain

#endif // BPM_DETECTOR_H
