#ifndef AUDIO_ENCODER_H
#define AUDIO_ENCODER_H

/*
 * Compressed-audio encoders for the recorder (used from the writer thread, never the audio thread):
 *  - MP3 via LAME (LGPL)
 *  - Ogg Vorbis via libvorbis + libogg (BSD)
 *
 * Input is interleaved stereo float (-1..1). Encoded bytes are appended to `out`.
 */

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace recorder {

enum class Codec : int { MP3 = 1, VORBIS = 2 };

struct EncoderSettings {
    Codec codec = Codec::MP3;
    int sampleRate = 48000;
    bool variableBitrate = true;  /* true: quality-based VBR, false: (average) bitrate */
    int quality = 7;              /* 0 (smallest) .. 10 (best) */
    int bitrateKbps = 192;
};

class AudioEncoder {
public:
    virtual ~AudioEncoder() = default;
    /* Returns false on encoder error */
    virtual bool encode(const float* interleaved, int frames, std::vector<uint8_t>& out) = 0;
    /* Flushes the remaining data; call once at the end */
    virtual bool finish(std::vector<uint8_t>& out) = 0;
    /* MP3 only: the final Xing/LAME tag frame that replaces the first frame of the file
       (correct duration/seeking for VBR). Empty if not applicable. */
    virtual std::vector<uint8_t> headerUpdate() { return {}; }

    /* nullptr if the settings are not supported */
    static std::unique_ptr<AudioEncoder> create(const EncoderSettings& settings);
};

} // namespace recorder

#endif // AUDIO_ENCODER_H
