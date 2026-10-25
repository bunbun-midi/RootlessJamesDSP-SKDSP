#include <algorithm>
#include <cstring>
#include <ctime>
#include <jni.h>
#include <new>

#include <lame.h>
#include <vorbis/vorbisenc.h>

#include "AudioEncoder.h"

namespace recorder {

// ------------------------------------------------------------------------------------------------
// MP3 (LAME)
// ------------------------------------------------------------------------------------------------

class Mp3Encoder : public AudioEncoder {
public:
    bool init(const EncoderSettings& s) {
        lame = lame_init();
        if (lame == nullptr)
            return false;
        lame_set_in_samplerate(lame, s.sampleRate);
        lame_set_out_samplerate(lame, s.sampleRate);
        lame_set_num_channels(lame, 2);
        lame_set_mode(lame, JOINT_STEREO);
        lame_set_quality(lame, 2); /* algorithm quality ("-h"): good, still fast */
        lame_set_bWriteVbrTag(lame, 1);
        if (s.variableBitrate) {
            lame_set_VBR(lame, vbr_default);
            /* UI 10 = best = LAME V0, UI 0 = V9 */
            lame_set_VBR_quality(lame, (10 - std::clamp(s.quality, 0, 10)) * 0.9f);
        } else {
            lame_set_VBR(lame, vbr_off);
            lame_set_brate(lame, std::clamp(s.bitrateKbps, 32, 320));
        }
        id3tag_init(lame);
        id3tag_set_comment(lame, "Recorded with SK DSP");
        return lame_init_params(lame) >= 0;
    }

    ~Mp3Encoder() override {
        if (lame != nullptr)
            lame_close(lame);
    }

    bool encode(const float* in, int frames, std::vector<uint8_t>& out) override {
        const size_t start = out.size();
        out.resize(start + (size_t)(1.25 * frames) + 7200);
        const int n = lame_encode_buffer_interleaved_ieee_float(lame, in, frames,
                                                                out.data() + start, (int)(out.size() - start));
        out.resize(start + std::max(n, 0));
        return n >= 0;
    }

    bool finish(std::vector<uint8_t>& out) override {
        const size_t start = out.size();
        out.resize(start + 7200);
        const int n = lame_encode_flush(lame, out.data() + start, 7200);
        out.resize(start + std::max(n, 0));
        return n >= 0;
    }

    std::vector<uint8_t> headerUpdate() override {
        std::vector<uint8_t> frame(4096);
        const size_t n = lame_get_lametag_frame(lame, frame.data(), frame.size());
        frame.resize(n <= frame.size() ? n : 0);
        return frame;
    }

private:
    lame_global_flags* lame = nullptr;
};

// ------------------------------------------------------------------------------------------------
// Ogg Vorbis
// ------------------------------------------------------------------------------------------------

class VorbisEncoder : public AudioEncoder {
public:
    bool init(const EncoderSettings& s) {
        vorbis_info_init(&vi);
        infoInit = true;
        int ret;
        if (s.variableBitrate) {
            /* UI 0..10 -> Vorbis quality 0.0..1.0 (q0 ~ 64 kbps, q6 ~ 192 kbps, q10 ~ 500 kbps) */
            ret = vorbis_encode_init_vbr(&vi, 2, s.sampleRate, std::clamp(s.quality, 0, 10) / 10.0f);
        } else {
            /* Average bitrate */
            ret = vorbis_encode_init(&vi, 2, s.sampleRate, -1, std::clamp(s.bitrateKbps, 32, 500) * 1000, -1);
        }
        if (ret != 0)
            return false;

        vorbis_comment_init(&vc);
        vorbis_comment_add_tag(&vc, "ENCODER", "SK DSP");
        if (vorbis_analysis_init(&vd, &vi) != 0)
            return false;
        dspInit = true;
        vorbis_block_init(&vd, &vb);
        blockInit = true;

        std::srand((unsigned)std::time(nullptr));
        ogg_stream_init(&os, std::rand());
        streamInit = true;

        ogg_packet header, comments, codebooks;
        vorbis_analysis_headerout(&vd, &vc, &header, &comments, &codebooks);
        ogg_stream_packetin(&os, &header);
        ogg_stream_packetin(&os, &comments);
        ogg_stream_packetin(&os, &codebooks);
        /* The headers go into their own page(s); written with the first encode() call */
        while (ogg_stream_flush(&os, &og) != 0)
            appendPage(pendingHeaders);
        return true;
    }

    ~VorbisEncoder() override {
        if (streamInit) ogg_stream_clear(&os);
        if (blockInit) vorbis_block_clear(&vb);
        if (dspInit) vorbis_dsp_clear(&vd);
        vorbis_comment_clear(&vc);
        if (infoInit) vorbis_info_clear(&vi);
    }

    bool encode(const float* in, int frames, std::vector<uint8_t>& out) override {
        flushHeaders(out);
        if (frames <= 0)
            return true;
        float** buffer = vorbis_analysis_buffer(&vd, frames);
        for (int i = 0; i < frames; i++) {
            buffer[0][i] = in[2 * i];
            buffer[1][i] = in[2 * i + 1];
        }
        vorbis_analysis_wrote(&vd, frames);
        drain(out, false);
        return true;
    }

    bool finish(std::vector<uint8_t>& out) override {
        flushHeaders(out);
        vorbis_analysis_wrote(&vd, 0);
        drain(out, true);
        return true;
    }

private:
    void appendPage(std::vector<uint8_t>& out) {
        out.insert(out.end(), og.header, og.header + og.header_len);
        out.insert(out.end(), og.body, og.body + og.body_len);
    }

    void flushHeaders(std::vector<uint8_t>& out) {
        if (!pendingHeaders.empty()) {
            out.insert(out.end(), pendingHeaders.begin(), pendingHeaders.end());
            pendingHeaders.clear();
        }
    }

    void drain(std::vector<uint8_t>& out, bool final) {
        while (vorbis_analysis_blockout(&vd, &vb) == 1) {
            vorbis_analysis(&vb, nullptr);
            vorbis_bitrate_addblock(&vb);
            ogg_packet op;
            while (vorbis_bitrate_flushpacket(&vd, &op) == 1) {
                ogg_stream_packetin(&os, &op);
                while (ogg_stream_pageout(&os, &og) != 0)
                    appendPage(out);
            }
        }
        if (final) {
            while (ogg_stream_flush(&os, &og) != 0)
                appendPage(out);
        }
    }

    vorbis_info vi{};
    vorbis_comment vc{};
    vorbis_dsp_state vd{};
    vorbis_block vb{};
    ogg_stream_state os{};
    ogg_page og{};
    bool infoInit = false, dspInit = false, blockInit = false, streamInit = false;
    std::vector<uint8_t> pendingHeaders;
};

std::unique_ptr<AudioEncoder> AudioEncoder::create(const EncoderSettings& settings) {
    if (settings.codec == Codec::MP3) {
        auto enc = std::make_unique<Mp3Encoder>();
        if (enc->init(settings))
            return enc;
    } else if (settings.codec == Codec::VORBIS) {
        auto enc = std::make_unique<VorbisEncoder>();
        if (enc->init(settings))
            return enc;
    }
    return nullptr;
}

} // namespace recorder

// ------------------------------------------------------------------------------------------------
// JNI: me.timschneeberger.rootlessjamesdsp.interop.AudioEncoder
// ------------------------------------------------------------------------------------------------

static inline recorder::AudioEncoder* encoder(jlong handle) {
    return reinterpret_cast<recorder::AudioEncoder*>(handle);
}

static jbyteArray toByteArray(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray array = env->NewByteArray((jsize)data.size());
    if (array != nullptr && !data.empty())
        env->SetByteArrayRegion(array, 0, (jsize)data.size(), reinterpret_cast<const jbyte*>(data.data()));
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_AudioEncoder_nativeCreate(JNIEnv*, jclass, jint codec, jint sampleRate,
                                                                          jboolean variableBitrate, jint quality, jint bitrateKbps)
{
    recorder::EncoderSettings s;
    s.codec = (recorder::Codec)codec;
    s.sampleRate = sampleRate;
    s.variableBitrate = variableBitrate;
    s.quality = quality;
    s.bitrateKbps = bitrateKbps;
    return reinterpret_cast<jlong>(recorder::AudioEncoder::create(s).release());
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_AudioEncoder_nativeDestroy(JNIEnv*, jclass, jlong handle)
{
    delete encoder(handle);
}

/* Returns the encoded bytes (possibly empty), or null on error */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_AudioEncoder_nativeEncode(JNIEnv* env, jclass, jlong handle,
                                                                          jfloatArray input, jint frames)
{
    if (handle == 0 || input == nullptr || frames < 0 || env->GetArrayLength(input) < frames * 2)
        return nullptr;
    std::vector<uint8_t> out;
    jfloat* in = env->GetFloatArrayElements(input, nullptr);
    const bool ok = encoder(handle)->encode(in, frames, out);
    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    return ok ? toByteArray(env, out) : nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_AudioEncoder_nativeFinish(JNIEnv* env, jclass, jlong handle)
{
    if (handle == 0)
        return nullptr;
    std::vector<uint8_t> out;
    return encoder(handle)->finish(out) ? toByteArray(env, out) : nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_AudioEncoder_nativeHeaderUpdate(JNIEnv* env, jclass, jlong handle)
{
    if (handle == 0)
        return nullptr;
    return toByteArray(env, encoder(handle)->headerUpdate());
}
