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
static constexpr float kPi = 3.14159265358979f;

struct BungeeStream {
    Bungee::Stretcher<Bungee::Basic> stretcher; /* must be constructed before `stream` */
    Bungee::Stream<Bungee::Basic> stream;

    explicit BungeeStream(int sampleRate) :
        stretcher({sampleRate, sampleRate}, 2, 0),
        stream(stretcher, kMaxBlockFrames, 2) {}
};

/* Speed shifter stream (pitch 1): grains only need +-2048 input frames around their position
 * (measured with Bungee::Basic at hop adjust 0), so it runs with that look-ahead instead of the
 * pitch-shift worst case. Output delay 3586 frames (75 ms at 48 kHz) instead of 9728. */
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
        enabled = false; /* the stream is kept so the chain can fade the effect out */
    }
}

void BungeePitchShifter::rebuild() {
    stream.reset();
    if (sampleRate <= 0.0f)
        return;
    stream = std::make_unique<BungeeStream>((int)std::lround(sampleRate));
    builds++;
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
    ring.assign((size_t)kRingFrames * 2, 0.0f);
    outRing.assign((size_t)kMatchFrames * 2, 0.0f);
    scratchBuf.assign((size_t)kMaxBlockFrames * 2, 0.0f);
    spliceBuf.assign((size_t)kSpliceFrames * 2, 0.0f);
    window.resize(2 * kHop);
    for (int i = 0; i < 2 * kHop; i++)
        window[i] = 0.5f - 0.5f * std::cos(kPi * (float)i / (float)kHop); /* periodic Hann, N = 2 hops */
}

SpeedShifter::~SpeedShifter() = default;

void SpeedShifter::reset() {
    fifo.clear();
    fraction = 0.0;
    stream = std::make_unique<SpeedStream>(sampleRate);
    backlog.store(0);
    phase = Phase::Idle;
    written = 0;
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

// ---- captured audio (ring) ------------------------------------------------------------------------

void SpeedShifter::ringWrite(const float* in, int frames) {
    for (int i = 0; i < frames; i++) {
        const size_t r = (size_t)((written + i) & (kRingFrames - 1)) * 2;
        ring[r] = in[2 * i];
        ring[r + 1] = in[2 * i + 1];
    }
    written += frames;
}

/* Captured frame at absolute index t (0 = first frame given to start()/process()); zeros outside */
inline const float* SpeedShifter::at(int64_t t) const {
    static const float zero[2] = {0.0f, 0.0f};
    if (t < 0 || t >= written || t < written - kRingFrames)
        return zero;
    return &ring[(size_t)(t & (kRingFrames - 1)) * 2];
}

void SpeedShifter::ringCopy(int64_t from, int frames, float* out) const {
    for (int i = 0; i < frames; i++) {
        const float* f = at(from + i);
        out[2 * i] = f[0];
        out[2 * i + 1] = f[1];
    }
}

// ---- start ----------------------------------------------------------------------------------------

void SpeedShifter::start(const float* history, int historyFrames) {
    if (resetRequested.exchange(false) || phase != Phase::Idle)
        reset();
    if (history != nullptr && historyFrames > 0) {
        const int n = std::min(historyFrames, kRingFrames / 2);
        ringWrite(history + 2 * (historyFrames - n), n);
    }

    /*
     * Seamless start: the audio played so far is treated as a WSOLA segment with input position ==
     * output position (segment A, the one that is fading out). Output continues from it without a
     * jump, while each new segment advances through the input at `speed`, so the output falls
     * behind the input gradually. See processWsola().
     */
    const int64_t t = written; /* output (= capture) time of the next frame */
    segAOut = t - kHop;
    segAIn = t - kHop;
    nominalIn = (double)(t - kHop);
    segBOut = t;
    segBIn = t; /* first new segment is chosen at the first output frame */
    needSegB = true;
    phase = Phase::Wsola;
    std::fill(outRing.begin(), outRing.end(), 0.0f);
    for (int i = 0; i < kMatchFrames; i++) {
        /* what was played just before: the dry signal */
        const float* f = at(t - kMatchFrames + i);
        outRing[2 * i] = f[0];
        outRing[2 * i + 1] = f[1];
    }
    outRingPos = 0;
    backlog.store(0);
}

// ---- WSOLA (start phase) --------------------------------------------------------------------------

int64_t SpeedShifter::chooseSegment(int64_t outPos, double nominal) const {
    /*
     * Candidates around the nominal input position, never after the output position (the input for
     * an output frame must already be captured). The candidate whose preceding kMatchFrames best
     * match the audio just played (normalized cross-correlation, both channels) is used, so the
     * new segment continues the waveform smoothly.
     */
    const int64_t nom = (int64_t)std::floor(nominal);
    const int64_t hi = std::min(nom + kSearchFrames, outPos);
    const int64_t lo = std::max<int64_t>(nom - kSearchFrames, std::max<int64_t>(written - kRingFrames + kMatchFrames, 0));
    if (hi <= lo)
        return std::clamp<int64_t>(nom, 0, outPos);

    double pp = 0.0; /* energy of the played audio */
    for (int i = 0; i < kMatchFrames; i += kMatchStep) {
        const float* y = &outRing[(size_t)((outRingPos + i) % kMatchFrames) * 2];
        pp += (double)y[0] * y[0] + (double)y[1] * y[1];
    }
    int64_t best = std::clamp<int64_t>(nom, lo, hi);
    double bestScore = -2.0;
    for (int64_t a = hi; a >= lo; a--) { /* ties: prefer the later candidate (smaller lag) */
        double c = 0.0, xx = 0.0;
        for (int i = 0; i < kMatchFrames; i += kMatchStep) {
            const float* y = &outRing[(size_t)((outRingPos + i) % kMatchFrames) * 2];
            const float* x = at(a - kMatchFrames + i);
            c += (double)x[0] * y[0] + (double)x[1] * y[1];
            xx += (double)x[0] * x[0] + (double)x[1] * x[1];
        }
        const double score = pp > 1e-12 && xx > 1e-12 ? c / std::sqrt(pp * xx) : 0.0;
        if (score > bestScore + 1e-9) {
            bestScore = score;
            best = a;
        }
    }
    return best;
}

void SpeedShifter::processWsola(float* out, int frames, int64_t t0) {
    const double speed = std::clamp(targetSpeed.load(), kMinSpeed, kMaxSpeed);
    for (int i = 0; i < frames; i++) {
        const int64_t t = t0 + i;
        if (needSegB || t >= segBOut + kHop) {
            if (!needSegB) {
                /* segment B becomes the fading-out segment A */
                segAOut = segBOut;
                segAIn = segBIn;
                segBOut += kHop;
            }
            needSegB = false;
            nominalIn += speed * kHop;
            segBIn = chooseSegment(segBOut, nominalIn);
        }
        const int jA = (int)(t - segAOut); /* kHop .. 2 kHop - 1: second half of A's window */
        const int jB = (int)(t - segBOut); /* 0 .. kHop - 1: first half of B's window */
        const float* xa = at(segAIn + jA);
        const float* xb = at(segBIn + jB);
        const float wa = window[jA], wb = window[jB];
        const float l = xa[0] * wa + xb[0] * wb;
        const float r = xa[1] * wa + xb[1] * wb;
        out[2 * i] = l;
        out[2 * i + 1] = r;
        float* y = &outRing[(size_t)outRingPos * 2];
        y[0] = l;
        y[1] = r;
        outRingPos = (outRingPos + 1) % kMatchFrames;
    }
}

// ---- Bungee (after the start phase) ---------------------------------------------------------------

void SpeedShifter::processBungee(float* out, int frames) {
    for (int off = 0; off < frames;) {
        const int n = std::min(frames - off, kMaxBlockFrames);

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
}

void SpeedShifter::handOver(int64_t t0) {
    /*
     * The WSOLA output has fallen kBungeeDelay frames behind: start Bungee so that its first output
     * frame is the input position WSOLA is playing at t0. Bungee outputs input position e - delay
     * after being fed up to e, so it is primed with the captured audio up to e = position + delay
     * (at speed 1, output discarded); the audio captured after e goes into the backlog.
     */
    const int64_t pos = segBIn + (t0 - segBOut);
    const int64_t e = std::min(pos + kBungeeDelay, written);
    const int64_t from = std::max<int64_t>(e - kPrimeFrames, std::max<int64_t>(written - kRingFrames, 0));
    /* `stream` is still unused here (fresh from the constructor or reset()) */
    for (int64_t p = from; p < e;) {
        const int n = (int)std::min<int64_t>(e - p, kMaxBlockFrames);
        ringCopy(p, n, scratch(n));
        feedDiscarding(scratchBuf.data(), n);
        p += n;
    }
    fifo.clear();
    for (int64_t p = e; p < written;) {
        const int n = (int)std::min<int64_t>(written - p, kMaxBlockFrames);
        ringCopy(p, n, scratch(n));
        fifo.push(scratchBuf.data(), n);
        p += n;
    }
    fraction = 0.0;
    phase = Phase::Bungee;
}

float* SpeedShifter::scratch(int frames) {
    if (scratchBuf.size() < (size_t)frames * 2)
        scratchBuf.resize((size_t)frames * 2);
    return scratchBuf.data();
}

void SpeedShifter::process(const float* in, float* out, int frames) {
    if (resetRequested.exchange(false))
        reset();
    if (phase == Phase::Idle)
        start(nullptr, 0);

    const int64_t t0 = written;
    ringWrite(in, frames);

    if (phase == Phase::Wsola) {
        // Switch to Bungee once WSOLA lags far enough behind (only at speed < 1)
        const int64_t lag = segBOut - segBIn;
        if (!needSegB && lag >= kBungeeDelay) {
            // Crossfade from WSOLA to Bungee over the start of this block (same input position)
            const int splice = std::min(frames, kSpliceFrames);
            float* wsola = spliceBuf.data();
            processWsola(wsola, splice, t0);
            handOver(t0);
            processBungee(out, frames);
            for (int i = 0; i < splice; i++) {
                const float g = 0.5f - 0.5f * std::cos(kPi * ((float)i + 0.5f) / (float)splice);
                out[2 * i] = out[2 * i] * g + wsola[2 * i] * (1.0f - g);
                out[2 * i + 1] = out[2 * i + 1] * g + wsola[2 * i + 1] * (1.0f - g);
            }
        } else {
            processWsola(out, frames, t0);
            backlog.store(std::max<int64_t>(0, segBOut - segBIn));
            return;
        }
    } else {
        // Bungee: the captured block joins the backlog
        for (int off = 0; off < frames;) {
            const int n = std::min(frames - off, kMaxBlockFrames);
            fifo.push(in + off * 2, n);
            off += n;
        }
        processBungee(out, frames);
    }

    // Bound memory: skip the oldest audio if the backlog exceeds the limit
    const int64_t maxFrames = (int64_t)kMaxBacklogSeconds * sampleRate;
    if (fifo.size() > maxFrames)
        fifo.drop(fifo.size() - maxFrames);
    backlog.store(fifo.size() + kBungeeDelay);
}

} // namespace chain
