#include <algorithm>
#include <cmath>
#include <cstring>

#include <bungee/Bungee.h>
#include <bungee/Stream.h>

#include "BungeeLowLatencyStream.h"
#include "BungeeProcessors.h"

namespace chain {

/* Largest block passed to Bungee::Stream::process at once; larger blocks are split */
static constexpr int kMaxBlockFrames = 16384;

struct BungeeStream {
    Bungee::Stretcher<Bungee::Basic> stretcher; /* must be constructed before `stream` */
    Bungee::Stream<Bungee::Basic> stream;

    explicit BungeeStream(int sampleRate) :
        stretcher({sampleRate, sampleRate}, 2, 0),
        stream(stretcher, kMaxBlockFrames, 2) {}
};

/* Speed shifter stream (pitch 1): grains only need +-2048 input frames around their position
 * (measured with Bungee::Basic at hop adjust 0), so it runs with that look-ahead instead of the
 * pitch-shift worst case. Output delay ~2.6 k frames (~55 ms at 48 kHz) instead of ~9.7 k. */
static constexpr int kSpeedLookahead = 2050;

struct SpeedStream {
    Bungee::Stretcher<Bungee::Basic> stretcher; /* must be constructed before `stream` */
    Bungee::SkDsp::LowLatencyStream<Bungee::Basic> stream;

    explicit SpeedStream(int sampleRate) :
        stretcher({sampleRate, sampleRate}, 2, 0),
        stream(stretcher, kMaxBlockFrames, 2, kSpeedLookahead) {}
};

// ------------------------------------------------------------------------------------------------
// Pitch Shifter HQ
// ------------------------------------------------------------------------------------------------

BungeePitchShifter::BungeePitchShifter() = default;
BungeePitchShifter::~BungeePitchShifter() = default;

void BungeePitchShifter::configure(float fs) {
    if (fs == sampleRate)
        return;
    sampleRate = fs;
    if (enabled)
        rebuild();
}

void BungeePitchShifter::setParams(bool enable, float semitones) {
    pitch = std::exp2(std::clamp(semitones, -24.0f, 24.0f) / 12.0);
    if (enable && !enabled) {
        enabled = true;
        rebuild(); // fresh state, allocated here rather than on the audio thread
    } else if (!enable && enabled) {
        enabled = false;
        stream.reset();
    }
}

void BungeePitchShifter::rebuild() {
    stream.reset();
    if (sampleRate <= 0.0f)
        return;
    stream = std::make_unique<BungeeStream>((int)std::lround(sampleRate));
    outL.assign(kMaxBlockFrames, 0.0f);
    outR.assign(kMaxBlockFrames, 0.0f);
}

void BungeePitchShifter::process(float* left, float* right, size_t n) {
    if (!stream)
        return;

    for (size_t off = 0; off < n;) {
        const int m = (int)std::min<size_t>(n - off, kMaxBlockFrames);
        const float* in[2] = {left + off, right + off};
        float* out[2] = {outL.data(), outR.data()};
        const int got = std::clamp(stream->stream.process(in, out, m, (double)m, pitch), 0, m);
        std::memcpy(left + off, outL.data(), got * sizeof(float));
        std::memcpy(right + off, outR.data(), got * sizeof(float));
        std::fill(left + off + got, left + off + m, 0.0f);
        std::fill(right + off + got, right + off + m, 0.0f);
        off += m;
    }
}

// ------------------------------------------------------------------------------------------------
// Backlog storage
// ------------------------------------------------------------------------------------------------

static inline int16_t toPcm16(float x) {
    return (int16_t)std::lrint(std::clamp(x, -1.0f, 1.0f) * 32767.0f);
}

void ChunkedFifo::push(const float* in, int frames) {
    while (frames > 0) {
        if (chunks.empty() || writePos == chunkFrames) {
            if (!spare.empty()) {
                chunks.push_back(std::move(spare.back()));
                spare.pop_back();
            } else {
                chunks.emplace_back(new int16_t[(size_t)chunkFrames * 2]);
            }
            writePos = 0;
        }
        const int m = (int)std::min<int64_t>(frames, chunkFrames - writePos);
        int16_t* dst = chunks.back().get() + writePos * 2;
        for (int i = 0; i < m * 2; i++)
            dst[i] = toPcm16(in[i]);
        writePos += m;
        count += m;
        in += m * 2;
        frames -= m;
    }
}

int ChunkedFifo::pop(float* left, float* right, int frames) {
    const int n = (int)std::min<int64_t>(frames, count);
    int done = 0;
    while (done < n) {
        const int64_t end = chunks.size() == 1 ? writePos : chunkFrames;
        const int m = (int)std::min<int64_t>(n - done, end - readPos);
        const int16_t* src = chunks.front().get() + readPos * 2;
        if (left != nullptr) {
            for (int i = 0; i < m; i++) {
                left[done + i] = src[2 * i] * (1.0f / 32767.0f);
                right[done + i] = src[2 * i + 1] * (1.0f / 32767.0f);
            }
        }
        readPos += m;
        count -= m;
        done += m;
        if (readPos == chunkFrames) {
            // Front chunk fully consumed; keep a couple of chunks around for reuse
            if (spare.size() < 2)
                spare.push_back(std::move(chunks.front()));
            chunks.erase(chunks.begin());
            readPos = 0;
            if (chunks.empty())
                writePos = 0;
        }
    }
    if (count == 0 && chunks.size() == 1) {
        readPos = 0;
        writePos = 0;
    }
    return n;
}

void ChunkedFifo::drop(int64_t frames) {
    while (frames > 0 && count > 0) {
        const int m = (int)std::min<int64_t>(frames, 1 << 20);
        pop(nullptr, nullptr, m);
        frames -= m;
    }
}

void ChunkedFifo::clear() {
    for (auto& c : chunks)
        if (spare.size() < 2)
            spare.push_back(std::move(c));
    chunks.clear();
    readPos = writePos = count = 0;
}

// ------------------------------------------------------------------------------------------------
// Speed shifter
// ------------------------------------------------------------------------------------------------

SpeedShifter::SpeedShifter(int sampleRate) :
    sampleRate(sampleRate),
    fifo(sampleRate) {
    stream = std::make_unique<SpeedStream>(sampleRate);
    inL.assign(kMaxBlockFrames, 0.0f);
    inR.assign(kMaxBlockFrames, 0.0f);
    outL.assign(kMaxBlockFrames, 0.0f);
    outR.assign(kMaxBlockFrames, 0.0f);
}

SpeedShifter::~SpeedShifter() = default;

void SpeedShifter::reset() {
    fifo.clear();
    fraction = 0.0;
    stream = std::make_unique<SpeedStream>(sampleRate);
    backlog.store(0);
}

void SpeedShifter::feedDiscarding(const float* in, int frames) {
    for (int off = 0; off < frames;) {
        const int n = std::min(frames - off, kMaxBlockFrames);
        for (int i = 0; i < n; i++) {
            inL[i] = in[2 * (off + i)];
            inR[i] = in[2 * (off + i) + 1];
        }
        const float* inPtr[2] = {inL.data(), inR.data()};
        float* outPtr[2] = {outL.data(), outR.data()};
        stream->stream.process(inPtr, outPtr, n, (double)n, 1.0);
        off += n;
    }
}

int SpeedShifter::prime(const float* history, int historyFrames, const float* current, int currentFrames,
                        int spliceFrames) {
    if (resetRequested.exchange(false))
        reset();

    /*
     * Run the audio captured before switching on through the stretcher at speed 1 (output
     * discarded), so the first real block comes out as audio instead of silence. The shifted
     * audio then lags the captured audio by the stretcher delay D, i.e. switching on replays the
     * last D frames. Crossfading from the dry signal to a copy that is D frames older partly
     * cancels on tonal music, so up to kMaxAlignFrames of extra delay are added (kept in the
     * backlog), chosen so that both copies match best over the splice (as in WSOLA).
     */
    const int maxAlign = std::min(kMaxAlignFrames, historyFrames);
    feedDiscarding(history, historyFrames - maxAlign);
    const int delay = (int)std::lround(stream->stream.latency());

    // Audio at frame i relative to the start of `current` (negative = history), interleaved stereo.
    // Both channels are compared separately (a mono mix cancels out-of-phase content).
    auto frame = [&](int i) {
        return i < 0 ? history + 2 * (historyFrames + i) : current + 2 * i;
    };
    const int n = std::clamp(spliceFrames, 1, currentFrames);
    int extra = 0;
    if (delay + maxAlign + 1 <= historyFrames) {
        double ee = 0.0;
        for (int i = 0; i < n; i++) {
            const float* d = frame(i);
            ee += (double)d[0] * d[0] + (double)d[1] * d[1];
        }
        double best = -2.0;
        for (int e = 0; e <= maxAlign; e++) {
            double c = 0.0, ww = 0.0;
            for (int i = 0; i < n; i++) {
                const float* d = frame(i);
                const float* w = frame(i - delay - e);
                c += (double)d[0] * w[0] + (double)d[1] * w[1];
                ww += (double)w[0] * w[0] + (double)w[1] * w[1];
            }
            const double r = ee > 1e-12 && ww > 1e-12 ? c / std::sqrt(ee * ww) : 0.0;
            if (r > best + 1e-9) {
                best = r;
                extra = e;
            }
        }
    }

    feedDiscarding(history + 2 * (historyFrames - maxAlign), maxAlign - extra);
    fifo.push(history + 2 * (historyFrames - extra), extra);
    backlog.store(fifo.size());
    return extra;
}

void SpeedShifter::process(const float* in, float* out, int frames) {
    if (resetRequested.exchange(false))
        reset();

    for (int off = 0; off < frames;) {
        const int n = std::min(frames - off, kMaxBlockFrames);
        fifo.push(in + off * 2, n);

        // Consume speed x n frames of backlog (fractional remainder carried over) for n output frames
        const double speed = std::clamp(targetSpeed.load(), kMinSpeed, kMaxSpeed);
        const double want = n * speed + fraction;
        int k = (int)std::floor(want);
        fraction = want - k;
        k = (int)std::min<int64_t>(std::max(k, 1), fifo.size());
        const int popped = fifo.pop(inL.data(), inR.data(), k);

        const float* inPtr[2] = {inL.data(), inR.data()};
        float* outPtr[2] = {outL.data(), outR.data()};
        const int got = std::clamp(stream->stream.process(inPtr, outPtr, popped, (double)n, 1.0), 0, n);

        float* o = out + off * 2;
        for (int i = 0; i < got; i++) {
            o[2 * i] = outL[i];
            o[2 * i + 1] = outR[i];
        }
        std::fill(o + got * 2, o + n * 2, 0.0f);
        off += n;
    }

    // Bound memory: skip the oldest audio if the backlog exceeds the limit
    const int64_t maxFrames = (int64_t)kMaxBacklogSeconds * sampleRate;
    if (fifo.size() > maxFrames)
        fifo.drop(fifo.size() - maxFrames);
    backlog.store(fifo.size());
}

} // namespace chain
