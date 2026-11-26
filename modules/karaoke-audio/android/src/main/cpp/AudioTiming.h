#pragma once

// Labels and the latency formula are shared with the Kotlin AudioBackend object.
// Keep the strings identical: the screen prints them as-is.

inline double latencyMilliseconds(int bufferFrames, int sampleRate) {
  if (bufferFrames <= 0 || sampleRate <= 0) {
    return 0.0;
  }
  return static_cast<double>(bufferFrames) * 1000.0 / static_cast<double>(sampleRate);
}

enum class BackendApi { AAudio, OpenSLES, Other };
enum class BackendPerformance { LowLatency, Other };

inline const char* backendLabel(BackendApi api, BackendPerformance performance) {
  if (api == BackendApi::OpenSLES) {
    return "OpenSL ES";
  }
  if (api == BackendApi::AAudio && performance == BackendPerformance::LowLatency) {
    return "AAudio Low-Latency";
  }
  if (api == BackendApi::AAudio) {
    return "AAudio";
  }
  return "Oboe";
}
