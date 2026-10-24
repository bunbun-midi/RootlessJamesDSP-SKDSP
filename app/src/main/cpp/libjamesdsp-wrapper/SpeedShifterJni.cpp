#include <jni.h>

#include <algorithm>
#include <new>

#include "BungeeProcessors.h"

/*
 * JNI bindings for chain::SpeedShifter (me.timschneeberger.rootlessjamesdsp.interop.SpeedShifter).
 * process() must only be called from one thread (the audio thread); the setters are thread-safe.
 */

static inline chain::SpeedShifter* shifter(jlong handle) {
    return reinterpret_cast<chain::SpeedShifter*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeCreate(JNIEnv*, jclass, jint sampleRate)
{
    if (sampleRate <= 0)
        return 0;
    return reinterpret_cast<jlong>(new (std::nothrow) chain::SpeedShifter(sampleRate));
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeDestroy(JNIEnv*, jclass, jlong handle)
{
    delete shifter(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeSetSpeed(JNIEnv*, jclass, jlong handle, jfloat speed)
{
    if (handle != 0)
        shifter(handle)->setSpeed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeReset(JNIEnv*, jclass, jlong handle)
{
    if (handle != 0)
        shifter(handle)->requestReset();
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeBacklogFrames(JNIEnv*, jclass, jlong handle)
{
    return handle != 0 ? (jlong)shifter(handle)->backlogFrames() : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_SpeedShifter_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                                            jfloatArray input, jfloatArray output, jint frames)
{
    if (handle == 0 || input == nullptr || output == nullptr || frames <= 0)
        return;
    if (env->GetArrayLength(input) < frames * 2 || env->GetArrayLength(output) < frames * 2)
        return;

    jfloat* in = env->GetFloatArrayElements(input, nullptr);
    jfloat* out = env->GetFloatArrayElements(output, nullptr);
    shifter(handle)->process(in, out, frames);
    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, out, 0);
}
