#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>
#include <mutex>

typedef struct
{
    void* dsp;
    /* Serializes audio processing and parameter changes. Parameters are updated from a
     * background thread while the audio thread processes; most effects are not protected
     * by libjamesdsp's internal lock. Recursive because callbacks into Java may re-enter. */
    std::recursive_mutex lock;
    JNIEnv* env; /* Only valid on the thread that called alloc(); use getEnv() for callbacks */
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
