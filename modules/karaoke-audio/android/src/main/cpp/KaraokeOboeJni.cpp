#include "KaraokeOboeEngine.h"

#include <jni.h>

#include <algorithm>
#include <mutex>
#include <string>

namespace {

std::mutex gMutex;
std::shared_ptr<KaraokeOboeEngine> gEngine;

std::shared_ptr<KaraokeOboeEngine> engine() {
  std::lock_guard<std::mutex> lock(gMutex);
  if (!gEngine) {
    gEngine = std::make_shared<KaraokeOboeEngine>();
  }
  return gEngine;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeStartDuplex(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint framesPerBurst,
    jint inputDeviceId,
    jint outputDeviceId) {
  return engine()->startDuplex(sampleRate, framesPerBurst, inputDeviceId, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeStartInput(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint framesPerBurst,
    jint inputDeviceId) {
  return engine()->startInput(sampleRate, framesPerBurst, inputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeStartOutput(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint framesPerBurst,
    jint outputDeviceId) {
  return engine()->startOutput(sampleRate, framesPerBurst, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeStop(JNIEnv*, jobject) {
  engine()->stop();
}

JNIEXPORT jint JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeRead(
    JNIEnv* env,
    jobject,
    jfloatArray array,
    jint frames) {
  if (array == nullptr || frames <= 0) {
    return -1;
  }
  const jsize length = env->GetArrayLength(array);
  const int count = std::min(static_cast<int>(frames), static_cast<int>(length));
  jfloat* data = env->GetFloatArrayElements(array, nullptr);
  if (data == nullptr) {
    return -1;
  }
  const int got = engine()->readFloats(data, count, 100000000LL);
  env->ReleaseFloatArrayElements(array, data, 0);
  return got;
}

JNIEXPORT jint JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeWrite(
    JNIEnv* env,
    jobject,
    jshortArray array,
    jint frames) {
  if (array == nullptr || frames <= 0) {
    return -1;
  }
  const jsize length = env->GetArrayLength(array);
  const int count = std::min(static_cast<int>(frames), static_cast<int>(length));
  jshort* data = env->GetShortArrayElements(array, nullptr);
  if (data == nullptr) {
    return -1;
  }
  const int got = engine()->writeShorts(reinterpret_cast<const int16_t*>(data), count, 100000000LL);
  env->ReleaseShortArrayElements(array, data, JNI_ABORT);
  return got;
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetGain(JNIEnv*, jobject, jfloat value) {
  engine()->setGain(value);
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetReverb(JNIEnv*, jobject, jfloat value) {
  engine()->setReverbMix(value);
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetEcho(JNIEnv*, jobject, jfloat value) {
  engine()->setEchoMix(value);
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetGate(JNIEnv*, jobject, jboolean enabled) {
  engine()->setGateEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetMuted(JNIEnv*, jobject, jboolean muted) {
  engine()->setMuted(muted == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSetSpace(JNIEnv* env, jobject, jstring name) {
  if (name == nullptr) {
    engine()->setSpace("clean");
    return;
  }
  const char* chars = env->GetStringUTFChars(name, nullptr);
  engine()->setSpace(chars != nullptr ? chars : "clean");
  if (chars != nullptr) {
    env->ReleaseStringUTFChars(name, chars);
  }
}

JNIEXPORT jint JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeSampleRate(JNIEnv*, jobject) {
  return engine()->sampleRate();
}

JNIEXPORT jdouble JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeLatencyMs(JNIEnv*, jobject) {
  return engine()->latencyMs();
}

JNIEXPORT jboolean JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeLowLatency(JNIEnv*, jobject) {
  return engine()->lowLatency() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeRms(JNIEnv*, jobject) {
  return engine()->rms();
}

JNIEXPORT jfloat JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativePeak(JNIEnv*, jobject) {
  return engine()->peak();
}

JNIEXPORT jstring JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeBackend(JNIEnv* env, jobject) {
  return env->NewStringUTF(engine()->backend().c_str());
}

JNIEXPORT jstring JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeLastError(JNIEnv* env, jobject) {
  return env->NewStringUTF(engine()->lastError().c_str());
}

JNIEXPORT jboolean JNICALL Java_expo_modules_karaokeaudio_NativeOboe_nativeIsRunning(JNIEnv*, jobject) {
  return engine()->isRunning() ? JNI_TRUE : JNI_FALSE;
}

}
