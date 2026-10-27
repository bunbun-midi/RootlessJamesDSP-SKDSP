#include <algorithm>
#include <cmath>
#include <cstring>

#include "ChainProcessor.h"

/* libjamesdsp internals that are not declared in jdsp_header.h (all have external linkage) */
extern "C" {
void JamesDSPProcess(JamesDSPLib *jdsp, size_t n);
void JamesDSPProcessCheckBenchmarkReady(JamesDSPLib *jdsp, size_t n);
void JamesDSPRefreshConvolutions(JamesDSPLib *jdsp, char refreshAll);
extern char benchmarkCompletionFlag;
}

namespace chain {

const int kDefaultOrder[COUNT] = {
    RECORDER_B, /* dry: before all effects */
    PITCH_SHIFT, PITCH_SHIFT_HQ, TUBE, COMPANDER, COMPRESSOR, BASS_BOOST, MULTI_EQ, ARBITRARY_EQ,
    CONVOLVER, DDC, LIVEPROG, CROSSFEED, STEREO_WIDE, REVERB, RECORDER
};

/* 20 / log2(10): converts log2(x) to dB */
static constexpr float kDbPerLog2 = 6.0205999f;
static constexpr float kHalfPi = 1.57079632679f;

/* State of the processing call currently running on this thread. The library calls
 * processInternal without any user pointer, so we pass our state through TLS. */
static thread_local State* tlsState = nullptr;

// ------------------------------------------------------------------------------------------------
// State
// ------------------------------------------------------------------------------------------------

State::State() {
    std::memcpy(order, kDefaultOrder, sizeof(order));
}

void State::setOrder(const int* ids, int count) {
    bool used[COUNT] = {};
    int out = 0;

    for (int i = 0; i < count && out < COUNT; i++) {
        int id = ids[i];
        if (id < 0 || id >= COUNT || used[id])
            continue;
        used[id] = true;
        order[out++] = id;
    }

    // Insert missing effects right after their nearest default-order predecessor that is present;
    // if there is none, before the first effect that follows them in the default order.
    auto indexOf = [&](int id) {
        for (int i = 0; i < out; i++)
            if (order[i] == id) return i;
        return -1;
    };
    for (int d = 0; d < COUNT; d++) {
        const int id = kDefaultOrder[d];
        if (used[id])
            continue;
        int pos = -1;
        for (int p = d - 1; p >= 0 && pos < 0; p--) {
            const int i = indexOf(kDefaultOrder[p]);
            if (i >= 0) pos = i + 1;
        }
        if (pos < 0) {
            pos = out;
            for (int f = d + 1; f < COUNT; f++) {
                const int i = indexOf(kDefaultOrder[f]);
                if (i >= 0 && i < pos) pos = i;
            }
        }
        std::memmove(order + pos + 1, order + pos, (out - pos) * sizeof(int));
        order[pos] = id;
        used[id] = true;
        out++;
    }
}

// ------------------------------------------------------------------------------------------------
// Latency, recorder alignment
// ------------------------------------------------------------------------------------------------

int State::latencyOf(int id, const JamesDSPLib* d) const {
    switch (id) {
        case TUBE: /* 2x oversampling filters (used at 30..65 kHz, i.e. at 44.1/48 kHz) */
            return d->tubeEnabled && d->tube.needOversample ? 10 : 0;
        case COMPANDER: /* FFT frame delay, by time-frequency transform */
            if (!d->compEnabled) return 0;
            switch (d->comp.tfresolution) {
                case 0: return 1792; /* STFT */
                case 1: return 3584; /* continuous wavelet */
                case 2: return 2048; /* undersampling */
                default: return 0;   /* time domain */
            }
        case BASS_BOOST: { /* look-ahead delay line of the dynamic bass boost */
            if (!d->bassBoostEnabled) return 0;
            const integerDelayLine& dl = d->dbb.dL[0];
            return dl.allocateLen > 0 ? ((dl.inPoint - dl.outPoint) % dl.allocateLen + dl.allocateLen) % dl.allocateLen : 0;
        }
        case CROSSFEED: /* HRTF "realistic surround": direct-sound delay of the impulse responses */
            return d->crossfeedEnabled && d->advXF.mode == 5 ? (d->fs < 46050.0f ? 19 : 20) : 0;
        case STEREO_WIDE:
            return d->sterEnhEnabled ? 3 : 0;
        case PITCH_SHIFT:
            return fadePitch.running && fadePitch.warm == 0 ? PitchShifter::kLatency : 0;
        case PITCH_SHIFT_HQ:
            return fadePitchHq.running && fadePitchHq.warm == 0 ? BungeePitchShifter::kLatency : 0;
        default:
            /* Equalizers, convolver, DDC, reverb (dry path), compressor: no delay.
             * Liveprog depends on the script and is assumed to have none. */
            return 0;
    }
}

void AlignDelay::push(const float* left, const float* right, size_t n) {
    for (size_t i = 0; i < n; i++) {
        const size_t r = (size_t)((written + i) & (kCapacity - 1)) * 2;
        ring[r] = left[i];
        ring[r + 1] = right[i];
    }
    written += n;
}

void AlignDelay::read(int delay, float* left, float* right, size_t n) const {
    delay = std::clamp(delay, 0, kCapacity - (int)std::min<size_t>(n, kCapacity) - 1);
    const int64_t start = (int64_t)written - (int64_t)n - delay;
    for (size_t i = 0; i < n; i++) {
        const int64_t t = start + (int64_t)i;
        if (t < 0) {
            left[i] = right[i] = 0.0f;
        } else {
            const size_t r = (size_t)(t & (kCapacity - 1)) * 2;
            left[i] = ring[r];
            right[i] = ring[r + 1];
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Compressor
// ------------------------------------------------------------------------------------------------

void Compressor::setParams(bool enable, float threshold, float r, float attack, float release,
                           float knee, float makeup) {
    if (enable && !enabled)
        reset(); // start without leftover gain reduction

    enabled = enable;
    thresholdDb = std::clamp(threshold, -80.0f, 0.0f);
    ratio = std::clamp(r, 1.0f, 100.0f);
    attackMs = std::clamp(attack, 0.01f, 2000.0f);
    releaseMs = std::clamp(release, 1.0f, 10000.0f);
    kneeDb = std::clamp(knee, 0.0f, 48.0f);
    makeupDb = std::clamp(makeup, -24.0f, 48.0f);
    dirty = true;
}

void Compressor::prepare(float fs) {
    preparedFs = fs;
    dirty = false;

    attackCoef = std::exp(-1.0f / (attackMs * 0.001f * fs));
    releaseCoef = std::exp(-1.0f / (releaseMs * 0.001f * fs));
    slope = 1.0f / ratio - 1.0f;
    kneeStartLin = std::pow(10.0f, (thresholdDb - kneeDb * 0.5f) / 20.0f);
    makeupLin = std::pow(10.0f, makeupDb / 20.0f);
}

void Compressor::process(float* left, float* right, size_t n, float fs) {
    if (dirty || fs != preparedFs)
        prepare(fs);

    const float threshold = thresholdDb;
    const float knee = kneeDb;
    const float halfKnee = knee * 0.5f;
    const float kneeScale = knee > 0.0f ? -slope / (2.0f * knee) : 0.0f;
    const float kneeStart = kneeStartLin;
    const float aCoef = attackCoef;
    const float rCoef = releaseCoef;
    const float makeup = makeupLin;
    float gr = grDb;

    for (size_t i = 0; i < n; i++) {
        const float l = left[i];
        const float r = right[i];
        const float peak = std::max(std::fabs(l), std::fabs(r));

        // Gain computer (static curve). Below the knee, no log is needed at all.
        float target = 0.0f;
        if (peak > kneeStart) {
            const float over = kDbPerLog2 * std::log2(peak) - threshold;
            if (over < halfKnee) {
                const float t = over + halfKnee; // 0..knee
                target = kneeScale * t * t;
            } else {
                target = -slope * over;
            }
        }

        // Level detector: smooth branching (separate attack/release), in the dB domain
        const float coef = target > gr ? aCoef : rCoef;
        gr = target + coef * (gr - target);

        const float gain = gr > 1e-5f ? makeup * std::exp2(-gr / kDbPerLog2) : makeup;
        left[i] = l * gain;
        right[i] = r * gain;
    }

    grDb = gr < 1e-9f ? 0.0f : gr;
}

// ------------------------------------------------------------------------------------------------
// Ordered processing
// ------------------------------------------------------------------------------------------------

static void processConvolver(State* st, JamesDSPLib* jdsp, size_t n) {
    ConvolverMix& mix = st->convolverMix;
    const float start = mix.current;
    const float end = mix.target;

    // Fast path: fully wet (no copy needed)
    if (start >= 0.9999f && end >= 0.9999f) {
        jdsp->conv.process(jdsp, n);
        mix.current = end;
        return;
    }

    float* left = jdsp->tmpBuffer[0];
    float* right = jdsp->tmpBuffer[1];

    if (mix.dry.size() < n * 2)
        mix.dry.resize(n * 2); // Only grows when the block size increases
    float* dryL = mix.dry.data();
    float* dryR = dryL + n;
    std::memcpy(dryL, left, n * sizeof(float));
    std::memcpy(dryR, right, n * sizeof(float));

    jdsp->conv.process(jdsp, n);

    if (start == end) {
        const float wet = end;
        const float dry = 1.0f - end;
        for (size_t i = 0; i < n; i++) {
            left[i] = left[i] * wet + dryL[i] * dry;
            right[i] = right[i] * wet + dryR[i] * dry;
        }
    } else {
        // Ramp linearly over this block to avoid zipper noise when the slider moves
        const float step = (end - start) / (float)n;
        float wet = start;
        for (size_t i = 0; i < n; i++) {
            wet += step;
            left[i] = left[i] * wet + dryL[i] * (1.0f - wet);
            right[i] = right[i] * wet + dryR[i] * (1.0f - wet);
        }
    }
    mix.current = end;
}

/* Output stage; identical to the end of JamesDSPProcess() in libjamesdsp */
static void processOutputLimiter(JamesDSPLib* jdsp, size_t n) {
    for (size_t i = 0; i < n; i++) {
        float xL = jdsp->tmpBuffer[0][i] * jdsp->postGain;
        float xR = jdsp->tmpBuffer[1][i] * jdsp->postGain;
        float rect1 = fabsf(xL);
        float rect2 = fabsf(xR);
        float maxLR = std::max(rect1, rect2);
        if (maxLR < jdsp->limiter.threshold)
            maxLR = jdsp->limiter.threshold;
        if (maxLR > jdsp->limiter.envOverThreshold)
            jdsp->limiter.envOverThreshold = maxLR;
        else
            jdsp->limiter.envOverThreshold = maxLR + jdsp->limiter.relCoef * (jdsp->limiter.envOverThreshold - maxLR);
        float gR = jdsp->limiter.threshold / jdsp->limiter.envOverThreshold;
        jdsp->tmpBuffer[0][i] = xL * gR;
        jdsp->tmpBuffer[1][i] = xR * gR;
    }
}

static void ensureScratch(State* st, size_t n) {
    if (st->scratchL.size() < n) { /* only grows when the block size increases */
        st->scratchL.resize(n);
        st->scratchR.resize(n);
    }
}

/*
 * Runs a pitch shifter with gapless on/off switching (see ShifterFade). `restarted` is true when
 * the shifter's output starts from silence again (it was re-enabled or rebuilt); `update` sets
 * its parameters for this block (glide) and is only called while it is enabled.
 */
template <class Shifter, class Update>
static void processShifter(State* st, Shifter& shifter, ShifterFade& f, bool enabled, bool restarted,
                           int latency, float* left, float* right, size_t n, float fs, Update&& update) {
    const int fadeLen = std::max(1, (int)(fs / 100.0f)); /* 10 ms */
    if (enabled && (!f.running || restarted)) {
        f.running = true;
        f.warm = latency;
        f.fadeIn = fadeLen;
    }
    if (!f.running)
        return;

    ensureScratch(st, n);
    float* dryL = st->scratchL.data();
    float* dryR = st->scratchR.data();
    std::memcpy(dryL, left, n * sizeof(float));
    std::memcpy(dryR, right, n * sizeof(float));

    if (enabled)
        update();
    shifter.process(left, right, n);

    if (!enabled) {
        // Switched off: crossfade back to the dry signal (equal power), unless the shifted
        // signal was not audible yet
        f.running = false;
        const size_t m = f.warm > 0 ? 0 : std::min<size_t>(n, fadeLen);
        for (size_t i = 0; i < m; i++) {
            const float a = kHalfPi * ((float)i + 0.5f) / (float)m;
            const float g = std::cos(a), h = std::sin(a);
            left[i] = left[i] * g + dryL[i] * h;
            right[i] = right[i] * g + dryR[i] * h;
        }
        std::memcpy(left + m, dryL + m, (n - m) * sizeof(float));
        std::memcpy(right + m, dryR + m, (n - m) * sizeof(float));
        return;
    }

    for (size_t i = 0; i < n && (f.warm > 0 || f.fadeIn > 0); i++) {
        if (f.warm > 0) {
            // Output still silent: play the dry signal
            left[i] = dryL[i];
            right[i] = dryR[i];
            f.warm--;
        } else {
            const float a = kHalfPi * ((float)(fadeLen - f.fadeIn) + 0.5f) / (float)fadeLen;
            const float g = std::sin(a), h = std::cos(a);
            left[i] = left[i] * g + dryL[i] * h;
            right[i] = right[i] * g + dryR[i] * h;
            f.fadeIn--;
        }
    }
}

static void orderedProcess(JamesDSPLib* jdsp, size_t n) {
    State* st = tlsState;
    if (st == nullptr) {
        // Not called through our JNI layer; fall back to the built-in chain
        JamesDSPProcess(jdsp, n);
        return;
    }

    // Delay between each position and the output, for aligning mid-chain recorder taps
    int afterPos[COUNT];
    {
        int sum = 0;
        for (int pos = COUNT - 1; pos >= 0; pos--) {
            afterPos[pos] = sum;
            sum += st->latencyOf(st->order[pos], jdsp);
        }
    }

    for (int pos = 0; pos < COUNT; pos++) {
        const int id = st->order[pos];
        switch (id) {
            case TUBE:
                if (jdsp->tubeEnabled)
                    VacuumTubeProcess(jdsp, n);
                break;
            case COMPANDER:
                if (jdsp->compEnabled)
                    CompressorProcess(jdsp, n);
                break;
            case BASS_BOOST:
                if (jdsp->bassBoostEnabled)
                    BassBoostProcess(jdsp, n);
                break;
            case MULTI_EQ:
                if (jdsp->equalizerEnabled)
                    MultimodalEqualizerProcess(jdsp, n);
                break;
            case ARBITRARY_EQ:
                if (jdsp->arbitraryMagEnabled)
                    ArbitraryResponseEqualizerProcess(jdsp, n);
                break;
            case CONVOLVER:
                // These three are also guarded by libjamesdsp's own lock, like in JamesDSPProcess()
                jdsp_lock(jdsp);
                if (jdsp->convolverEnabled && jdsp->conv.process)
                    processConvolver(st, jdsp, n);
                jdsp_unlock(jdsp);
                break;
            case DDC:
                jdsp_lock(jdsp);
                if (jdsp->ddcEnabled)
                    DDCProcess(jdsp, n);
                jdsp_unlock(jdsp);
                break;
            case LIVEPROG:
                jdsp_lock(jdsp);
                if (jdsp->liveprogEnabled)
                    LiveProgProcess(jdsp, n);
                jdsp_unlock(jdsp);
                break;
            case CROSSFEED:
                if (jdsp->crossfeedEnabled)
                    CrossfeedProcess(jdsp, n);
                break;
            case STEREO_WIDE:
                if (jdsp->sterEnhEnabled)
                    StereoEnhancementProcess(jdsp, n);
                break;
            case REVERB:
                if (jdsp->reverbEnabled)
                    ReverbProcess(jdsp, n);
                break;
            case COMPRESSOR:
                if (st->compressor.enabled)
                    st->compressor.process(jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n, jdsp->fs);
                break;
            case PITCH_SHIFT: {
                PitchShifter& ps = st->pitchShifter;
                processShifter(st, ps, st->fadePitch, ps.isEnabled(), ps.resetPending(), PitchShifter::kLatency,
                               jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n, jdsp->fs, [&] {
                    if (st->pitchGlide.active)
                        ps.setParams(true, st->pitchGlide.advance(n, jdsp->fs));
                });
                break;
            }
            case PITCH_SHIFT_HQ: {
                BungeePitchShifter& ps = st->pitchShifterHq;
                const bool restarted = ps.generation() != st->fadePitchHq.generation;
                st->fadePitchHq.generation = ps.generation();
                processShifter(st, ps, st->fadePitchHq, ps.isEnabled(), restarted, BungeePitchShifter::kLatency,
                               jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n, jdsp->fs, [&] {
                    if (st->pitchGlideHq.active)
                        ps.setParams(true, st->pitchGlideHq.advance(n, jdsp->fs));
                });
                break;
            }
            case RECORDER:
            case RECORDER_B: {
                // Last in the chain: recorded after the output stage below (exactly what is heard).
                // Elsewhere: recorded here, with the output stage's gain + limiter applied to the copy.
                // The copy is delayed by the latency of the effects after this position, so that
                // recordings at different positions line up with each other and with the output.
                RecorderTap* tap = id == RECORDER ? st->recorder : st->recorderB;
                if (pos != COUNT - 1) {
                    AlignDelay& align = id == RECORDER ? st->alignA : st->alignB;
                    align.push(jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
                    if (tap != nullptr) {
                        ensureScratch(st, n);
                        align.read(afterPos[pos], st->scratchL.data(), st->scratchR.data(), n);
                        tap->writeLimited(st->scratchL.data(), st->scratchR.data(), n,
                                          jdsp->postGain, jdsp->limiter.threshold, jdsp->limiter.relCoef);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    processOutputLimiter(jdsp, n);

    {
        const int last = st->order[COUNT - 1];
        RecorderTap* tap = last == RECORDER ? st->recorder : last == RECORDER_B ? st->recorderB : nullptr;
        if (tap != nullptr)
            tap->write(jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
    }

    // Same as JamesDSPProcessCheckBenchmarkReady(): once benchmark results are available,
    // rebuild convolutions to use the optimal partitioning (once).
    if (!st->benchmarkHandled && benchmarkCompletionFlag == 1) {
        JamesDSPRefreshConvolutions(jdsp, 0);
        st->benchmarkHandled = true;
    }
}

void beginProcess(State* state, JamesDSPLib* dsp) {
    tlsState = state;

    if (dsp->processInternal == JamesDSPProcess) {
        state->benchmarkHandled = true;
        dsp->processInternal = orderedProcess;
    } else if (dsp->processInternal == JamesDSPProcessCheckBenchmarkReady) {
        state->benchmarkHandled = false;
        dsp->processInternal = orderedProcess;
    }
}

void endProcess() {
    tlsState = nullptr;
}

} // namespace chain
