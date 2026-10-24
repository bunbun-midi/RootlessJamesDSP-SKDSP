#include <android/log.h>

#define TAG "JamesDspWrapper_JNI"
#include <Log.h>

#include <string>
#include <cstring>
#include <mutex>
#include <jni.h>
#if defined(__aarch64__)
#include <cstdint>
#endif

#include "JamesDspWrapper.h"
#include "JArrayList.h"
#include "EelVmVariable.h"

extern "C" {
#include "../EELStdOutExtension.h"
#include <jdsp_header.h>
}

// C interop
inline JamesDSPLib* cast(void* raw){
    if(raw == nullptr)
    {
        LOGE("JamesDspWrapper::cast: JamesDSPLib pointer is NULL")
    }
    return static_cast<JamesDSPLib*>(raw);
}

inline JamesDspWrapper* castWrapper(jlong raw){
    if(raw == 0)
    {
        LOGE("JamesDspWrapper::castWrapper: JamesDspWrapper pointer is NULL")
    }
    return reinterpret_cast<JamesDspWrapper*>(raw);
}

#define RETURN_IF_NULL(name, retval) \
    if(name == nullptr)      \
        return retval;

#define DECLARE_WRAPPER(retval) \
     if(self == 0L) \
        return retval; \
     auto* wrapper = castWrapper(self); \
     RETURN_IF_NULL(wrapper, retval)

#define DECLARE_DSP(retval) \
    DECLARE_WRAPPER(retval) \
    std::lock_guard<std::recursive_mutex> dspLockGuard(wrapper->lock); \
    auto* dsp = cast(wrapper->dsp); \
    RETURN_IF_NULL(dsp, retval)

static JavaVM* javaVm = nullptr;

/* JNIEnv pointers are thread-local. Callbacks (e.g. Liveprog stdout) can be triggered from the
 * audio thread, so we must not use the JNIEnv stored during alloc(). */
static JNIEnv* getEnv()
{
    JNIEnv* env = nullptr;
    if(javaVm == nullptr || javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return nullptr;
    return env;
}

/* Denormal (subnormal) floats appear in IIR filter feedback paths when the signal decays
 * towards silence and can make processing much slower on some CPUs, causing dropouts.
 * Flush them to zero on the audio thread. This is inaudible. */
static inline void enableFlushToZero()
{
    static thread_local bool applied = false;
    if(applied)
        return;
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24); /* FZ */
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__x86_64__) || defined(__i386__)
    unsigned int csr;
    __asm__ __volatile__("stmxcsr %0" : "=m"(csr));
    csr |= 0x8040; /* FTZ | DAZ */
    __asm__ __volatile__("ldmxcsr %0" : : "m"(csr));
#endif
    applied = true;
}

#define DECLARE_WRAPPER_V DECLARE_WRAPPER()
#define DECLARE_DSP_V DECLARE_DSP()
#define DECLARE_WRAPPER_B DECLARE_WRAPPER(false)
#define DECLARE_DSP_B DECLARE_DSP(false)

inline int32_t arySearch(int32_t *array, int32_t N, int32_t x)
{
    for (int32_t i = 0; i < N; i++)
    {
        if (array[i] == x)
            return i;
    }
    return -1;
}

#define FLOIDX 20000
/*inline void* GetStringForIndex(eel_string_context_state *st, float val, int32_t write)
{
    auto castedValue = (int32_t)(val + 0.5f);
    if (castedValue < FLOIDX)
        return nullptr;
    int32_t idx = arySearch(st->map, st->slot, castedValue);
    if (idx < 0)
        return nullptr;
    if (!write)
    {
        s_str *tmp = &st->m_literal_strings[idx];
        const char *s = s_str_c_str(tmp);
        return (void*)s;
    }
    else
        return (void*)&st->m_literal_strings[idx];
}*/

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_alloc(JNIEnv *env, jobject obj, jobject callback)
{
    auto* self = new JamesDspWrapper();
    self->callbackInterface = env->NewGlobalRef(callback);
    self->env = env;

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr)
    {
        LOGE("JamesDspWrapper::ctor: Cannot find callback class");
        delete self;
        return 0;
    }
    else
    {
        self->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput",
                                                      "(Ljava/lang/String;)V");
        self->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec",
                                                    "(Ljava/lang/String;)V");
        self->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult",
                                                          "(ILjava/lang/String;Ljava/lang/String;)V");
        self->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError",
                                                          "()V");
        if (self->callbackOnLiveprogOutput == nullptr || self->callbackOnLiveprogExec == nullptr ||
            self->callbackOnLiveprogResult == nullptr || self->callbackOnVdcParseError == nullptr)
        {
            LOGE("JamesDspWrapper::ctor: Cannot find callback method");
            delete self;
            return 0;
        }
    }


    auto* _dsp = (JamesDSPLib*)malloc(sizeof(JamesDSPLib));
    memset(_dsp, 0, sizeof(JamesDSPLib));

    if(!_dsp)
    {
        LOGE("JamesDspWrapper::ctor: Failed to allocate memory for libjamesdsp class object");
        delete self;
        return 1;
    }

    JamesDSPGlobalMemoryAllocation();
    JamesDSPInit(_dsp, 128, 48000);

    if(!JamesDSPGetMutexStatus(_dsp))
    {
        LOGE("JamesDspWrapper::ctor: JamesDSPGetMutexStatus returned false. "
                    "Cannot run safely in multi-threaded environment.");
        JamesDSPFree(_dsp);
        JamesDSPGlobalMemoryDeallocation();
        delete self;
        return 2;
    }

    self->dsp = _dsp;

    LOGD("JamesDspWrapper::ctor: memory allocated at %lx", (long)self);
    return (long)self;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_free(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_WRAPPER_V

    {
        // Wait for any in-flight processing/parameter call to finish before freeing
        std::lock_guard<std::recursive_mutex> dspLockGuard(wrapper->lock);
        auto* dsp = cast(wrapper->dsp);
        RETURN_IF_NULL(dsp,)

        LOGD("JamesDspWrapper::dtor: freeing memory allocated at %lx", (long)self);

        setStdOutHandler(nullptr, nullptr);

        JamesDSPFree(dsp);
        free(dsp);
        wrapper->dsp = nullptr;
    }

    JamesDSPGlobalMemoryDeallocation();

    env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;

    LOGD("JamesDspWrapper::dtor: memory freed");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getBenchmarkSize(JNIEnv *env, jobject obj) {
    return MAX_BENCHMARK;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_runBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::runBenchmark: started");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Start_benchmark();
    JamesDSP_Save_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, 0);
    env->ReleaseDoubleArrayElements(jc1, c1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_loadBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::loadBenchmark: loading data");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Load_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jc1, c1, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSamplingRate(JNIEnv *env,
                                                                                 jobject obj,
                                                                                 jlong self,
                                                                                 jfloat sample_rate,
                                                                                 jboolean force_refresh)
{
    DECLARE_DSP_V
    JamesDSPSetSampleRate(dsp, sample_rate, force_refresh);
    wrapper->chain.pitchShifterHq.configure(dsp->fs);
}


extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_isHandleValid(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_B // This macro returns false if the DSP object can't be accessed
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt16(JNIEnv *env, jobject obj, jlong self, jshortArray inputObj, jshortArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    enableFlushToZero();
    auto input = env->GetShortArrayElements(inputObj, nullptr);
    auto output = env->GetShortArrayElements(outputObj, nullptr);
    chain::beginProcess(&wrapper->chain, dsp);
    dsp->processInt16Multiplexd(dsp, input + offset, output, inputLength / 2);
    chain::endProcess();
    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt32(JNIEnv *env, jobject obj, jlong self, jintArray inputObj, jintArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    enableFlushToZero();
    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    chain::beginProcess(&wrapper->chain, dsp);
    dsp->processInt32Multiplexd(dsp, input + offset, output, inputLength / 2);
    chain::endProcess();
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT jbooleanArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv *env, jobject obj, jlong self, jbooleanArray inputObj)
{
    /* We need to use jbooleanArray (= unsigned 8-bit) instead of jbyteArray (= signed 8-bit) here! */

    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewBooleanArray(inputLength);

    auto input = env->GetBooleanArrayElements(inputObj, nullptr);
    auto output = env->GetBooleanArrayElements(outputObj, nullptr);
    chain::beginProcess(&wrapper->chain, dsp);
    dsp->processInt24PackedMultiplexd(dsp, input, output, inputLength / 2);
    chain::endProcess();
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt8U24(JNIEnv *env, jobject obj, jlong self, jintArray inputObj)
{
    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewIntArray(inputLength);

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    chain::beginProcess(&wrapper->chain, dsp);
    dsp->processInt8_24Multiplexd(dsp, input, output, inputLength / 2);
    chain::endProcess();
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processFloat(JNIEnv *env, jobject obj, jlong self, jfloatArray inputObj, jfloatArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    enableFlushToZero();
    auto input = env->GetFloatArrayElements(inputObj, nullptr);
    auto output = env->GetFloatArrayElements(outputObj, nullptr);

    chain::beginProcess(&wrapper->chain, dsp);
    dsp->processFloatMultiplexd(dsp, input + offset, output, inputLength / 2);
    chain::endProcess();

    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setEffectOrder(JNIEnv *env, jobject obj, jlong self, jintArray order)
{
    DECLARE_DSP_B
    jsize count = env->GetArrayLength(order);
    auto* ids = env->GetIntArrayElements(order, nullptr);
    wrapper->chain.setOrder(ids, count);
    env->ReleaseIntArrayElements(order, ids, JNI_ABORT);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompressor(JNIEnv *env, jobject obj, jlong self, jboolean enable,
                                                                               jfloat threshold, jfloat ratio, jfloat attack,
                                                                               jfloat release, jfloat knee, jfloat makeup)
{
    DECLARE_DSP_B
    wrapper->chain.compressor.setParams(enable, threshold, ratio, attack, release, knee, makeup);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPitchShift(JNIEnv *env, jobject obj, jlong self, jboolean enable, jfloat semitones)
{
    DECLARE_DSP_B
    wrapper->chain.pitchShifter.setParams(enable, semitones);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPitchShiftHq(JNIEnv *env, jobject obj, jlong self, jboolean enable, jfloat semitones)
{
    DECLARE_DSP_B
    // Allocates the Bungee stretcher here (settings thread), not on the audio thread
    wrapper->chain.pitchShifterHq.configure(dsp->fs);
    wrapper->chain.pitchShifterHq.setParams(enable, semitones);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolverMix(JNIEnv *env, jobject obj, jlong self, jfloat wet)
{
    DECLARE_DSP_B
    wrapper->chain.convolverMix.target = wet < 0.0f ? 0.0f : (wet > 1.0f ? 1.0f : wet);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiter(JNIEnv *env, jobject obj, jlong self, jfloat threshold, jfloat release)
{
    DECLARE_DSP_B
    JLimiterSetCoefficients(dsp, threshold, release);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPostGain(JNIEnv *env, jobject obj, jlong self, jfloat gain)
{
    DECLARE_DSP_B
    JamesDSPSetPostGain(dsp, gain);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setMultiEqualizer(JNIEnv *env, jobject obj, jlong self,
                                                                                   jboolean enable, jint filterType, jint interpolationMode,
                                                                                   jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 30)
    {
        LOGE("JamesDspWrapper::setMultiEqualizer: Invalid EQ data. 30 semicolon-separated fields expected, "
                      "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setMultiEqualizer: EQ band pointer is NULL. Disabling EQ");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        MultimodalEqualizerAxisInterpolation(dsp, interpolationMode, filterType, nativeBands, nativeBands + 15);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        MultimodalEqualizerEnable(dsp, 1);
    }
    else
    {
        MultimodalEqualizerDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVdc(JNIEnv *env, jobject obj, jlong self,
                                                                       jboolean enable, jstring vdcContents)
{
    DECLARE_DSP_B
    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(vdcContents, nullptr);
        DDCStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(vdcContents, nativeString);

        int ret = DDCEnable(dsp, 1);
        if (ret <= 0)
        {
            LOGE("JamesDspWrapper::setVdc: Call to DDCEnable(wrapper->dsp) failed. Invalid DDC parameter?");
            LOGE("JamesDspWrapper::setVdc: Disabling DDC engine");
            env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnVdcParseError);

            DDCDisable(dsp);
            return false;
        }
    }
    else
    {
        DDCDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompander(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat timeConstant, jint granularity, jint tfresolution, jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 14)
    {
        LOGE("JamesDspWrapper::setCompander: Invalid compander data. 14 semicolon-separated fields expected, "
             "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setCompander: Compander band pointer is NULL. Disabling compander");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        CompressorSetParam(dsp, timeConstant, granularity, tfresolution, 0);
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        CompressorSetGain(dsp, nativeBands, nativeBands + 7, 1);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        CompressorEnable(dsp, 1);
    }
    else
    {
        CompressorDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setReverb(JNIEnv *env, jobject obj, jlong self,
                                                                          jboolean enable, jint preset)
{
    DECLARE_DSP_B
    if(enable)
    {
        Reverb_SetParam(dsp, preset);
        ReverbEnable(dsp);
    }
    else
    {
        ReverbDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolver(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloatArray impulseResponse,
                                                                             jint irChannels, jint irFrames)
{
    DECLARE_DSP_B

    int success = 1;
    if(env->GetArrayLength(impulseResponse) <= 0)
    {
        LOGW("JamesDspWrapper::setConvolver: Impulse response array is empty. Disabling convolver");
        enable = false;
    }

    if(enable)
    {
        if(irFrames <= 0)
        {
            LOGW("JamesDspWrapper::setConvolver: Impulse response has zero frames");
        }

        LOGD("JamesDspWrapper::setConvolver: Impulse response loaded: channels=%d, frames=%d", irChannels, irFrames);

        Convolver1DDisable(dsp);

        auto* nativeImpulse = (env->GetFloatArrayElements(impulseResponse, nullptr));
        success = Convolver1DLoadImpulseResponse(dsp, nativeImpulse, irChannels, irFrames, 1);
        env->ReleaseFloatArrayElements(impulseResponse, nativeImpulse, JNI_ABORT);
    }

    if(enable)
        Convolver1DEnable(dsp);
    else
        Convolver1DDisable(dsp);

    if(success <= 0)
    {
        LOGD("JamesDspWrapper::setConvolver: Failed to update convolver. Convolver1DLoadImpulseResponse returned an error.");
        return false;
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setGraphicEq(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jstring graphicEq)
{
    DECLARE_DSP_B
    if(graphicEq == nullptr || env->GetStringUTFLength(graphicEq) <= 0)
    {
        LOGE("JamesDspWrapper::setGraphicEq: graphicEq is empty or NULL. Disabling graphic eq.");
        enable = false;
    }

    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(graphicEq, nullptr);
        ArbitraryResponseEqualizerStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(graphicEq, nativeString);

        ArbitraryResponseEqualizerEnable(dsp, 1);
    }
    else
        ArbitraryResponseEqualizerDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCrossfeed(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jint mode, jint customFcut, jint customFeed)
{
    DECLARE_DSP_B
    if(mode == 99)
    {
        memset(&dsp->advXF.bs2b, 0, sizeof(dsp->advXF.bs2b));
        BS2BInit(&dsp->advXF.bs2b[1], (unsigned int)dsp->fs, ((unsigned int)customFcut | ((unsigned int)customFeed << 16)));
        dsp->advXF.mode = 1;
    }
    else
    {
       CrossfeedChangeMode(dsp, mode);
    }

    if(enable)
        CrossfeedEnable(dsp, 1);
    else
        CrossfeedDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoost(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloat maxGain)
{
    DECLARE_DSP_B
    if(enable)
    {
        BassBoostSetParam(dsp, maxGain);
        BassBoostEnable(dsp);
    }
    else
    {
        BassBoostDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoEnhancement(JNIEnv *env, jobject obj, jlong self,
                                                                                     jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    StereoEnhancementDisable(dsp);
    StereoEnhancementSetParam(dsp, level / 100.0f);
    if(enable)
    {
        StereoEnhancementEnable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTube(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    if(enable)
    {
        VacuumTubeSetGain(dsp, level / 100.0f);
        VacuumTubeEnable(dsp);
    }
    else
    {
        VacuumTubeDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLiveprog(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jstring id, jstring liveprogContent)
{
    DECLARE_DSP_B

    // Attach log listener
    setStdOutHandler(receiveLiveprogStdOut, wrapper);

    LiveProgDisable(dsp);

    const char *nativeString = env->GetStringUTFChars(liveprogContent, nullptr);
    if(strlen(nativeString) < 1) {
        LOGD("JamesDspWrapper::setLiveprog: empty file")
        env->ReleaseStringUTFChars(liveprogContent, nativeString);
        return true;
    }

    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogExec, id);

    int ret = LiveProgStringParser(dsp, (char*)nativeString); // Ignore constness, libjamesdsp does not modify it
    env->ReleaseStringUTFChars(liveprogContent, nativeString);

    // Workaround due to library bug
    jdsp_unlock(dsp);

    const char* errorString = NSEEL_code_getcodeerror(dsp->eel.vm);
    if(errorString != nullptr)
    {
        LOGW("JamesDspWrapper::setLiveprog: NSEEL_code_getcodeerror: Syntax error in script file, cannot load. Reason: %s", errorString);
    }
    if(ret <= 0)
    {
        LOGW("JamesDspWrapper::setLiveprog: %s", checkErrorCode(ret));
    }

    jstring errorStringJni = env->NewStringUTF(errorString);
    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogResult, ret, id, errorStringJni);
    env->DeleteLocalRef(errorStringJni);

    if(enable)
        LiveProgEnable(dsp);
    else
        LiveProgDisable(dsp);
    return true;
}


extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_enumerateEelVariables(JNIEnv *env, jobject obj, jlong self)
{
    auto array = JArrayList(env);

    // Return empty array if DECLARE failed
    DECLARE_DSP(array.getJavaReference())

    auto *ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            // TODO fix string handling (broke after last libjamesdsp update)
            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            bool isString = valid;

            if (ctx->varTable_Names[i][j])
            {
                const char* name = ctx->varTable_Names[i][j];
                const char* value;

                if(isString)
                    value = valid;
                else
                    value = std::to_string(ctx->varTable_Values[i][j]).c_str();

                auto var = EelVmVariable(env, name, value, isString);
                array.add(var.getJavaReference());
            }
        }
    }

    return array.getJavaReference();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_manipulateEelVariable(JNIEnv *env, jobject obj, jlong self,
                                                                                      jstring name, jfloat value)
{
    DECLARE_DSP_B
    auto* ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            const char *nativeName = env->GetStringUTFChars(name, nullptr);
            if(!ctx->varTable_Names[i][j] || std::strcmp(ctx->varTable_Names[i][j], nativeName) != 0)
            {
                env->ReleaseStringUTFChars(name, nativeName);
                continue;
            }

            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            if(valid)
            {
                LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' is a string; currently only numerical variables can be manipulated", nativeName);
                env->ReleaseStringUTFChars(name, nativeName);
                return false;
            }

            ctx->varTable_Values[i][j] = value;

            env->ReleaseStringUTFChars(name, nativeName);
            return true;
        }
    }

    const char *nativeName = env->GetStringUTFChars(name, nullptr);
    LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' not found", nativeName);
    env->ReleaseStringUTFChars(name, nativeName);
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_freezeLiveprogExecution(JNIEnv *env, jobject obj, jlong self,
                                                                                        jboolean freeze)
{
    DECLARE_DSP_V
    dsp->eel.active = !freeze;
    LOGD("JamesDspWrapper::freezeLiveprogExecution: Liveprog execution has been %s", (freeze ? "frozen" : "resumed"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_eelErrorCodeToString(JNIEnv *env,
                                                                                     jobject obj,
                                                                                     jint error_code)
{
    return env->NewStringUTF(checkErrorCode(error_code));
}

void receiveLiveprogStdOut(const char *buffer, void* userData)
{
    auto* self = static_cast<JamesDspWrapper*>(userData);
    if(self == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Self reference is NULL");
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Unhandled output: %s", buffer);
        return;
    }

    JNIEnv* env = getEnv();
    if(env == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Thread not attached to JVM. Unhandled output: %s", buffer);
        return;
    }

    jstring str = env->NewStringUTF(buffer);
    env->CallVoidMethod(self->callbackInterface, self->callbackOnLiveprogOutput, str);
    env->DeleteLocalRef(str);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    javaVm = vm;
#ifndef NO_CRASHLYTICS
    firebase::crashlytics::Initialize();
#endif
    LOGD("JNI_OnLoad called")
    return JNI_VERSION_1_6;
}
