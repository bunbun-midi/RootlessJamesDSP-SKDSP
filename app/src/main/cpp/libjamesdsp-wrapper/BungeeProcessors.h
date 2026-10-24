#ifndef BUNGEE_PROCESSORS_H
#define BUNGEE_PROCESSORS_H

/*
 * Real-time processors built on Bungee (https://github.com/bungee-audio-stretch/bungee, MPL-2.0),
 * adapted from the file-based Bungee integration in ywexplorer to streaming use:
 *
 *  - BungeePitchShifter: "Pitch Shifter HQ" effect block in the effect chain (speed 1, variable pitch)
 *  - SpeedShifter: slows captured system audio down (pitch preserved). Audio that has been captured
 *    but not yet played is kept in a backlog that grows by (1 - speed) x real time.
 *
 * Bungee objects allocate when created; they are only created from parameter setters / JNI calls
 * outside the audio thread, except when the speed shifter is reset after being toggled.
 *
 * Note: keep this header free of <cmath> (see ChainProcessor.h); Bungee headers are only
 * included from BungeeProcessors.cpp.
 */

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace chain {

struct BungeeStream; /* opaque; wraps Bungee::Stretcher + Bungee::Stream */

class BungeePitchShifter {
public:
    BungeePitchShifter();
    ~BungeePitchShifter();

    /* (Re)creates the stretcher if the sample rate changed. Called with the wrapper lock held. */
    void configure(float sampleRate);
    void setParams(bool enable, float semitones);
    bool isEnabled() const { return enabled; }

    /* In-place stereo processing; any block size. Passthrough if not configured. */
    void process(float* left, float* right, size_t n);

private:
    void rebuild();

    bool enabled = false;
    double pitch = 1.0;
    float sampleRate = 0.0f;
    std::unique_ptr<BungeeStream> stream;
    std::vector<float> outL, outR;
};

/* Backlog of captured audio, stored as 16-bit PCM in 1-second chunks */
class ChunkedFifo {
public:
    explicit ChunkedFifo(int chunkFrames) : chunkFrames(chunkFrames) {}

    void push(const float* interleaved, int frames);
    /* Pops up to `frames` frames into planar buffers; returns the number of frames popped */
    int pop(float* left, float* right, int frames);
    void drop(int64_t frames);
    void clear();
    int64_t size() const { return count; }

private:
    const int chunkFrames;
    std::vector<std::unique_ptr<int16_t[]>> chunks; /* interleaved stereo */
    std::vector<std::unique_ptr<int16_t[]>> spare;
    int64_t readPos = 0;   /* frame offset into chunks.front() */
    int64_t writePos = 0;  /* frame offset into chunks.back() */
    int64_t count = 0;
};

class SpeedShifter {
public:
    explicit SpeedShifter(int sampleRate);
    ~SpeedShifter();

    /* Thread-safe setters (UI thread) */
    void setSpeed(float speed) { targetSpeed.store(speed); }
    void requestReset() { resetRequested.store(true); }
    int64_t backlogFrames() const { return backlog.load(); }

    /* Audio thread: consumes `frames` captured frames and renders `frames` output frames */
    void process(const float* inInterleaved, float* outInterleaved, int frames);

    static constexpr float kMinSpeed = 0.25f;
    static constexpr float kMaxSpeed = 1.0f;
    static constexpr int kMaxBacklogSeconds = 300; /* 5 min; about 58 MB at 48 kHz */

private:
    void reset();

    const int sampleRate;
    std::atomic<float> targetSpeed{1.0f};
    std::atomic<bool> resetRequested{true};
    std::atomic<int64_t> backlog{0};

    ChunkedFifo fifo;
    std::unique_ptr<BungeeStream> stream;
    double fraction = 0.0;
    std::vector<float> inL, inR, outL, outR;
};

} // namespace chain

#endif // BUNGEE_PROCESSORS_H
