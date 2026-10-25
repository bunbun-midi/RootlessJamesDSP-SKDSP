#ifndef RECORDER_TAP_H
#define RECORDER_TAP_H

/*
 * "Recorder" effect block: copies the audio at its position in the effect chain into a lock-free
 * single-producer/single-consumer ring buffer. The audio thread writes (write()), a Kotlin writer
 * thread reads (read()) and stores the audio in a WAV file.
 *
 * Lifetime: the chain only holds a raw pointer, set/cleared under the wrapper lock
 * (JamesDspWrapper.setRecorderTap). After the pointer was cleared, the audio thread no longer
 * touches the tap, so it can be drained and destroyed safely.
 */

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace chain {

class RecorderTap {
public:
    /* Capacity is rounded up to a power of two (frames) */
    explicit RecorderTap(size_t capacityFrames);

    /* Audio thread. If the ring is full, the whole block is dropped and counted. */
    void write(const float* left, const float* right, size_t frames);

    /* Audio thread: like write(), but first applies the same post gain + peak limiter as the
     * output stage (used when the recorder sits in the middle of the chain, where levels can
     * exceed 0 dBFS; the recording then has the same level safety as the playback). */
    void writeLimited(const float* left, const float* right, size_t frames,
                      float postGain, float threshold, float releaseCoef);

    /* Writer thread. Reads up to maxFrames interleaved stereo frames; returns the frame count. */
    size_t read(float* interleaved, size_t maxFrames);

    uint64_t droppedFrames() const { return dropped.load(std::memory_order_relaxed); }
    size_t capacityFrames() const { return capacity; }

private:
    size_t capacity;
    size_t mask;
    std::vector<float> ring; /* interleaved stereo */
    std::atomic<uint64_t> writeIndex{0};
    std::atomic<uint64_t> readIndex{0};
    std::atomic<uint64_t> dropped{0};
    float limiterEnvelope = 0.0f; /* audio thread only */
    std::vector<float> scratchL, scratchR;
};

} // namespace chain

#endif // RECORDER_TAP_H
