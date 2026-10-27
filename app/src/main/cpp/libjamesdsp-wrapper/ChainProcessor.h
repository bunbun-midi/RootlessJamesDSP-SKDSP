#ifndef CHAIN_PROCESSOR_H
#define CHAIN_PROCESSOR_H

/*
 * User-configurable effect chain for libjamesdsp.
 *
 * libjamesdsp runs its effects in a fixed order inside JamesDSPProcess(), which it calls through
 * the JamesDSPLib::processInternal function pointer. We replace that pointer with our own
 * function, which calls the individual (exported) effect process functions in a user-defined
 * order and adds effects that are implemented here instead of in libjamesdsp:
 *  - a classic feed-forward compressor
 *  - a wet/dry mix for the convolver
 *  - a phase-vocoder pitch shifter (PitchShifter.cpp)
 *  - "Pitch Shifter HQ" based on Bungee (BungeeProcessors.cpp)
 *  - two recorder taps (A and B) that copy the audio at their positions to file writers (RecorderTap.cpp)
 *
 * Everything in here is called while the wrapper mutex is held (see JamesDspWrapper.cpp),
 * so parameter setters and the audio thread never run concurrently.
 */

/* Keep includes minimal: this header is included by JamesDspWrapper.cpp, where Log.h declares a
 * global `namespace log` that clashes with <cmath>'s ::log. */
#include <cstddef>
#include <vector>

#include "PitchShifter.h"
#include "BungeeProcessors.h"
#include "RecorderTap.h"

extern "C" {
#include <jdsp_header.h>
}

/* ns-eel.h (pulled in by jdsp_header.h) defines min()/max() macros, which break std::min/std::max
 * and C++ standard headers included afterwards. */
#undef min
#undef max

namespace chain {

/* Stable IDs; these are persisted in the app's preferences. Never renumber. */
enum EffectId : int {
    TUBE = 0,
    COMPANDER = 1,
    BASS_BOOST = 2,
    MULTI_EQ = 3,
    ARBITRARY_EQ = 4,   /* Graphic EQ + parametric EQ (combined into one stage) */
    CONVOLVER = 5,
    DDC = 6,
    LIVEPROG = 7,
    CROSSFEED = 8,
    STEREO_WIDE = 9,
    REVERB = 10,
    COMPRESSOR = 11,
    PITCH_SHIFT = 12,
    PITCH_SHIFT_HQ = 13, /* Bungee */
    RECORDER = 14,   /* Recorder A */
    RECORDER_B = 15,
    COUNT = 16
};

/* Stereo-linked, feed-forward, log-domain compressor with soft knee
 * (Giannoulis, Massberg & Reiss, "Digital Dynamic Range Compressor Design", JAES 2012). */
struct Compressor {
    bool enabled = false;
    float thresholdDb = -18.0f;
    float ratio = 4.0f;
    float attackMs = 10.0f;
    float releaseMs = 150.0f;
    float kneeDb = 6.0f;
    float makeupDb = 0.0f;

    void setParams(bool enable, float threshold, float ratio, float attack, float release,
                   float knee, float makeup);
    void process(float* left, float* right, size_t n, float fs);
    void reset() { grDb = 0.0f; }

private:
    void prepare(float fs);

    bool dirty = true;
    float preparedFs = 0.0f;
    float attackCoef = 0.0f;
    float releaseCoef = 0.0f;
    float slope = 0.0f;          /* 1/ratio - 1 (<= 0) */
    float kneeStartLin = 0.0f;   /* linear level where gain reduction starts */
    float makeupLin = 1.0f;
    float grDb = 0.0f;           /* smoothed gain reduction in dB (>= 0) */
};

/* Wet/dry mix around the convolver. Changes are ramped over one block to avoid zipper noise. */
struct ConvolverMix {
    float target = 1.0f;   /* 1 = 100% wet (libjamesdsp default behaviour) */
    float current = 1.0f;
    std::vector<float> dry;
};

/* Portamento for the pitch shifters (floating piano keyboard): moves the interval linearly to the
 * target over a fixed time, advanced once per audio block */
struct PitchGlide {
    float current = 0.0f;
    float target = 0.0f;
    float semitonesPerSecond = 0.0f;
    bool active = false;

    void jump(float semitones) { current = target = semitones; active = false; }
    void glideTo(float semitones, float timeMs) {
        target = semitones;
        if (timeMs <= 0.0f || current == target) {
            jump(semitones);
            return;
        }
        semitonesPerSecond = (target > current ? target - current : current - target) * 1000.0f / timeMs;
        active = true;
    }
    /* Interval at the end of a block of n samples */
    float advance(size_t n, float fs) {
        if (active) {
            const float step = semitonesPerSecond * (float)n / fs;
            if (current < target) current = current + step >= target ? target : current + step;
            else current = current - step <= target ? target : current - step;
            if (current == target) active = false;
        }
        return current;
    }
};

struct State {
    State();

    /* Sets the processing order. Unknown/duplicate IDs are ignored; missing effects (e.g. ones
     * added in a newer version) are inserted next to their default neighbours, so the chain
     * always contains every effect exactly once. */
    void setOrder(const int* ids, int count);

    int order[COUNT];
    Compressor compressor;
    ConvolverMix convolverMix;
    PitchShifter pitchShifter;
    BungeePitchShifter pitchShifterHq;
    PitchGlide pitchGlide, pitchGlideHq;
    RecorderTap* recorder = nullptr;  /* Recorder A; not owned, see RecorderTap.h */
    RecorderTap* recorderB = nullptr; /* Recorder B */
    bool benchmarkHandled = false;
};

/* Default order: libjamesdsp's built-in order, plus the pitch shifter first and the compressor after the compander */
extern const int kDefaultOrder[COUNT];

/* Must be called by the JNI layer before each process call (on the audio thread). Installs the
 * ordered processing function into the libjamesdsp instance when it is safe to do so. */
void beginProcess(State* state, JamesDSPLib* dsp);
void endProcess();

} // namespace chain

#endif // CHAIN_PROCESSOR_H
