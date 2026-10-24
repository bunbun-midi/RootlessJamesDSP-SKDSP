#include <algorithm>
#include <jni.h>
#include <new>

#include "RecorderTap.h"

namespace chain {

static size_t nextPowerOfTwo(size_t n) {
    size_t p = 1;
    while (p < n)
        p <<= 1;
    return p;
}

RecorderTap::RecorderTap(size_t capacityFrames) :
    capacity(nextPowerOfTwo(std::max<size_t>(capacityFrames, 1024))),
    mask(capacity - 1),
    ring(capacity * 2, 0.0f) {}

void RecorderTap::write(const float* left, const float* right, size_t frames) {
    const uint64_t w = writeIndex.load(std::memory_order_relaxed);
    const uint64_t r = readIndex.load(std::memory_order_acquire);
    if (frames > capacity - (size_t)(w - r)) {
        dropped.fetch_add(frames, std::memory_order_relaxed);
        return;
    }
    for (size_t i = 0; i < frames; i++) {
        const size_t pos = (size_t)((w + i) & mask) * 2;
        ring[pos] = left[i];
        ring[pos + 1] = right[i];
    }
    writeIndex.store(w + frames, std::memory_order_release);
}

size_t RecorderTap::read(float* out, size_t maxFrames) {
    const uint64_t w = writeIndex.load(std::memory_order_acquire);
    const uint64_t r = readIndex.load(std::memory_order_relaxed);
    const size_t n = std::min<size_t>((size_t)(w - r), maxFrames);
    for (size_t i = 0; i < n; i++) {
        const size_t pos = (size_t)((r + i) & mask) * 2;
        out[2 * i] = ring[pos];
        out[2 * i + 1] = ring[pos + 1];
    }
    readIndex.store(r + n, std::memory_order_release);
    return n;
}

} // namespace chain

// ------------------------------------------------------------------------------------------------
// JNI: me.timschneeberger.rootlessjamesdsp.interop.RecorderTap
// ------------------------------------------------------------------------------------------------

static inline chain::RecorderTap* tap(jlong handle) {
    return reinterpret_cast<chain::RecorderTap*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_RecorderTap_nativeCreate(JNIEnv*, jclass, jint capacityFrames)
{
    if (capacityFrames <= 0)
        return 0;
    return reinterpret_cast<jlong>(new (std::nothrow) chain::RecorderTap((size_t)capacityFrames));
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_RecorderTap_nativeDestroy(JNIEnv*, jclass, jlong handle)
{
    delete tap(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_RecorderTap_nativeRead(JNIEnv* env, jclass, jlong handle, jfloatArray output)
{
    if (handle == 0 || output == nullptr)
        return 0;
    const jsize length = env->GetArrayLength(output);
    jfloat* out = env->GetFloatArrayElements(output, nullptr);
    const size_t frames = tap(handle)->read(out, (size_t)length / 2);
    env->ReleaseFloatArrayElements(output, out, 0);
    return (jint)frames;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_RecorderTap_nativeDroppedFrames(JNIEnv*, jclass, jlong handle)
{
    return handle != 0 ? (jlong)tap(handle)->droppedFrames() : 0;
}
