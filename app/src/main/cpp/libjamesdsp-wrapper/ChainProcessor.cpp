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
    PITCH_SHIFT, PITCH_SHIFT_HQ, TUBE, COMPANDER, COMPRESSOR, BASS_BOOST, MULTI_EQ, ARBITRARY_EQ,
    CONVOLVER, DDC, LIVEPROG, CROSSFEED, STEREO_WIDE, REVERB
};

/* 20 / log2(10): converts log2(x) to dB */
static constexpr float kDbPerLog2 = 6.0205999f;

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

static void orderedProcess(JamesDSPLib* jdsp, size_t n) {
    State* st = tlsState;
    if (st == nullptr) {
        // Not called through our JNI layer; fall back to the built-in chain
        JamesDSPProcess(jdsp, n);
        return;
    }

    for (int id : st->order) {
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
            case PITCH_SHIFT:
                if (st->pitchShifter.isEnabled())
                    st->pitchShifter.process(jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
                break;
            case PITCH_SHIFT_HQ:
                if (st->pitchShifterHq.isEnabled())
                    st->pitchShifterHq.process(jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
                break;
            default:
                break;
        }
    }

    processOutputLimiter(jdsp, n);

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
