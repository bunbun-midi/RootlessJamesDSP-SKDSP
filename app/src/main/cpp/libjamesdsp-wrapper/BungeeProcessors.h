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
struct SpeedStream;  /* opaque; wraps Bungee::Stretcher + a low-latency stream */

class BungeePitchShifter {
public:
    BungeePitchShifter();
    ~BungeePitchShifter();

    /* (Re)creates the stretcher if the sample rate changed. Called with the wrapper lock held. */
    void configure(float sampleRate);
    void setParams(bool enable, float semitones);
    bool isEnabled() const { return enabled; }
    /* Incremented whenever the stretcher is rebuilt (its output then starts from silence again) */
    unsigned generation() const { return builds; }

    /* In-place stereo processing; any block size. Passthrough if not configured. Keeps working
     * for a moment after being disabled, so the chain can fade it out. */
    void process(float* left, float* right, size_t n);

    /* Output delay of the stretcher (Bungee::Stream look-ahead), in frames */
    static constexpr int kLatency = 9728;

private:
    void rebuild();

    unsigned builds = 0;
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

    /*
     * Audio thread, when slow-down is switched on (before the first process()): `history` is the
     * audio captured just before (interleaved stereo, oldest first; may be empty).
     *
     * Slow-down then starts seamlessly from the audio that was playing: a WSOLA time stretcher
     * (short overlapping segments, causal, no look-ahead) continues the waveform and gradually
     * falls behind the input. Once it lags by kBungeeDelay (the delay of the Bungee stretcher),
     * Bungee is started at exactly the input position WSOLA is playing and takes over with a short
     * crossfade (after ~0.3 s at 0.75x; at 1.0x WSOLA simply keeps playing in real time).
     */
    void start(const float* history, int historyFrames);

    /* Audio thread: consumes `frames` captured frames and renders `frames` output frames */
    void process(const float* inInterleaved, float* outInterleaved, int frames);

    static constexpr float kMinSpeed = 0.25f;
    static constexpr float kMaxSpeed = 1.0f;
    static constexpr int kMaxBacklogSeconds = 300; /* 5 min; about 58 MB at 48 kHz */
    /* At the limit, playback skips ahead once by this much (crossfaded) instead of losing a few
     * ms in every block, which sounded like continuous glitching */
    static constexpr int kCapSkipSeconds = 30;
    static constexpr int kCapFadeFrames = 4800;

    /* History start() can use (Bungee's priming at the hand-over may reach back this far) */
    static constexpr int kPrimeFrames = 8192;
    /* Output delay of the Bungee stream (look-ahead 2050 + output buffering), in frames */
    static constexpr int kBungeeDelay = 3586;

private:
    enum class Phase { Idle, Wsola, Bungee };

    void reset();
    void feedDiscarding(const float* in, int frames);
    void ringWrite(const float* in, int frames);
    const float* at(int64_t t) const;
    void ringCopy(int64_t from, int frames, float* out) const;
    float* scratch(int frames);
    int64_t chooseSegment(int64_t outPos, double nominal) const;
    void processWsola(float* out, int frames, int64_t t0);
    void processBungee(float* out, int frames);
    void handOver(int64_t t0);
    /* Backlog input for Bungee: the crossfade buffer of a skip first, then the FIFO */
    int64_t inputAvailable() const { return fifo.size() + (pendingCount - pendingPos); }
    int popInput(int frames);
    void skipAhead();

    /* WSOLA: Hann windows of 2 hops (21 ms at 48 kHz), 50 % overlap */
    static constexpr int kHop = 512;
    static constexpr int kSearchFrames = 320;  /* +-6.7 ms around the nominal position */
    static constexpr int kMatchFrames = 384;   /* compared with the audio just played */
    static constexpr int kMatchStep = 3;       /* every 3rd frame is compared (cost) */
    static constexpr int kSpliceFrames = 480;  /* WSOLA -> Bungee crossfade */
    static constexpr int kRingFrames = 1 << 16; /* captured audio kept (power of 2) */

    const int sampleRate;
    std::atomic<float> targetSpeed{1.0f};
    std::atomic<bool> resetRequested{false}; /* the constructor already builds a fresh stream */
    std::atomic<int64_t> backlog{0};

    Phase phase = Phase::Idle;

    /* Captured audio; frame index t = number of frames captured before it */
    std::vector<float> ring;
    int64_t written = 0;

    /* WSOLA state: segment A fades out, segment B fades in; *Out = output time of the segment's
     * window start, *In = input position it reads from */
    int64_t segAOut = 0, segAIn = 0, segBOut = 0, segBIn = 0;
    double nominalIn = 0.0;
    bool needSegB = true;
    std::vector<float> window;
    std::vector<float> outRing; /* last kMatchFrames output frames */
    int outRingPos = 0;         /* oldest frame in outRing */

    /* Bungee */
    ChunkedFifo fifo;
    std::unique_ptr<SpeedStream> stream;
    double fraction = 0.0;
    std::vector<float> inL, inR, outL, outR;
    std::vector<float> scratchBuf, spliceBuf;
    /* crossfade of a skip at the backlog limit (planar), consumed before the FIFO */
    std::vector<float> pendL, pendR, fadeAL, fadeAR;
    int pendingPos = 0, pendingCount = 0;
};

} // namespace chain

#endif // BUNGEE_PROCESSORS_H
