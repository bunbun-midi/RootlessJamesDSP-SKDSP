#ifndef PITCH_SHIFTER_H
#define PITCH_SHIFTER_H

/*
 * Stereo pitch shifter (-24..+24 semitones), phase vocoder with peak-locked frequency-domain
 * shifting after Laroche & Dolson, "New phase-vocoder techniques for pitch-shifting, harmonizing
 * and other exotic effects" (IEEE WASPAA 1999):
 *
 *  - Each frame, spectral peaks are detected and the spectrum is split into regions of influence
 *    around them. Every region is moved as a whole to the shifted peak frequency and rotated by
 *    an accumulated phase so partials stay phase-coherent across frames. This keeps the
 *    "vertical" phase coherence within a partial (less phasiness than per-bin vocoders) and only
 *    needs one atan2/sincos per peak instead of per bin.
 *  - Both channels share the same peak analysis and get identical shifts/rotations, so the
 *    inter-channel phase relationship (stereo image) is preserved.
 *  - Transforms use libjamesdsp's fast Hartley transform (FHT).
 *
 * Frame size 2048, hop 512 (75% overlap, Hann analysis + synthesis window).
 * Latency: 2048 samples (43 ms at 48 kHz) while enabled.
 */

#include <cstddef>
#include <vector>

namespace chain {

class PitchShifter {
public:
    PitchShifter();

    void setParams(bool enable, float semitones);
    bool isEnabled() const { return enabled; }
    /* True after (re)enabling until the first process(): the output starts from silence again */
    bool resetPending() const { return needsReset; }

    /* In-place stereo processing; any block size */
    void process(float* left, float* right, size_t n);
    void reset();

    static constexpr int kFrameSize = 2048;
    static constexpr int kHop = kFrameSize / 4;
    static constexpr int kBins = kFrameSize / 2 + 1;
    static constexpr int kRoverStart = kFrameSize - kHop; /* FIFO fill level before the first frame */
    static constexpr int kLatency = kFrameSize;            /* total delay in samples while enabled */

private:
    struct Peak {
        int bin;          /* source bin */
        float phaseAcc;   /* accumulated synthesis phase rotation */
    };

    void processFrame();

    bool enabled = false;
    float ratio = 1.0f;
    bool needsReset = true;

    /* FHT tables */
    std::vector<unsigned> bitRev;
    std::vector<float> sinTab;
    std::vector<float> window;
    std::vector<float> binAdvance;       /* expected phase advance per hop for each bin: 2*pi*k*hop/N */

    /* Streaming buffers (per channel) */
    std::vector<float> inFifo[2];
    std::vector<float> outFifo[2];
    std::vector<float> outAccum[2];
    int rover = kRoverStart;

    /* Spectra of the current frame and the reference phase of the previous one */
    std::vector<float> fht;              /* work buffer */
    std::vector<float> re[2], im[2];     /* current analysis spectrum */
    std::vector<float> prevRe[2], prevIm[2]; /* previous analysis spectrum */
    std::vector<float> outRe[2], outIm[2];
    std::vector<float> mag;              /* combined power spectrum */

    std::vector<Peak> peaks, prevPeaks;
    std::vector<int> regionStart;
};

} // namespace chain

#endif // PITCH_SHIFTER_H
