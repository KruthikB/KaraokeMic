#pragma once

#include "LockFreeRingBuffer.h"
#include "dsp/VocalDsp.h"

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

/**
 * Full-duplex Oboe engine for direct monitoring, plus one-way streams for the
 * Wi-Fi mic. Direct mode runs the vocal DSP on the input callback and a
 * lock-free ring feeds the output callback. Wi-Fi mode uses blocking read
 * or write on a dedicated urgent thread so the Kotlin jitter buffer stays
 * the playout clock.
 *
 * Usage is MEDIA and content type is MUSIC. Nothing here requests audio
 * focus or switches Bluetooth into SCO. SharingMode::Exclusive is only the
 * AAudio low-latency MMAP path; if another app has the device, open falls
 * back to Shared.
 */
class KaraokeOboeEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback,
                          public std::enable_shared_from_this<KaraokeOboeEngine> {
 public:
  ~KaraokeOboeEngine() override;

  bool startDuplex(int sampleRate, int framesPerBurst, int inputDeviceId, int outputDeviceId);
  bool startInput(int sampleRate, int framesPerBurst, int inputDeviceId);
  bool startOutput(int sampleRate, int framesPerBurst, int outputDeviceId);
  void stop();

  int readFloats(float* destination, int frames, int64_t timeoutNanoseconds);
  int writeShorts(const int16_t* mono, int frames, int64_t timeoutNanoseconds);

  void setGain(float value);
  void setReverbMix(float value);
  void setEchoMix(float value);
  void setGateEnabled(bool enabled);
  void setMuted(bool muted);
  void setSpace(const char* name);

  int sampleRate() const;
  double latencyMs() const;
  bool lowLatency() const;
  bool isRunning() const;
  float rms() const;
  float peak() const;
  std::string backend() const;
  std::string lastError() const;

  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
  void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

 private:
  enum class Mode { Idle, Duplex, Input, Output };

  bool openOutput(int sampleRate, int framesPerBurst, int deviceId, bool withCallback);
  bool openInput(int sampleRate, int framesPerBurst, int deviceId, bool withCallback);
  bool tryOpen(
      oboe::Direction direction,
      int channels,
      int sampleRate,
      int framesPerCallback,
      int deviceId,
      oboe::SharingMode sharing,
      oboe::PerformanceMode performance,
      oboe::AudioFormat format,
      oboe::InputPreset preset,
      bool allowConversion,
      bool withCallback,
      std::shared_ptr<oboe::AudioStream>& out);
  void tune(const std::shared_ptr<oboe::AudioStream>& stream, int requestedBurst);
  void rememberStream(const std::shared_ptr<oboe::AudioStream>& stream, int inputFrames, int outputFrames);
  void closeStream(const std::shared_ptr<oboe::AudioStream>& stream);
  void ensureScratch(int frames);
  void applyDspControls();
  void updateLevels(const float* samples, int count);
  void readMono(oboe::AudioStream* stream, const void* audioData, int frameOffset, int frames, float* mono);
  void writeMono(oboe::AudioStream* stream, const float* mono, int frames, void* audioData, int frameOffset);
  void handleInput(oboe::AudioStream* stream, const void* audioData, int32_t numFrames);
  void handleOutput(oboe::AudioStream* stream, void* audioData, int32_t numFrames);
  void setError(const std::string& message);

  mutable std::mutex control_;
  mutable std::mutex errorMutex_;
  Mode mode_ = Mode::Idle;
  std::shared_ptr<oboe::AudioStream> input_;
  std::shared_ptr<oboe::AudioStream> output_;
  std::unique_ptr<VocalDsp> dsp_;
  std::unique_ptr<LockFreeRingBuffer> ring_;
  std::unique_ptr<float[]> inputScratch_;
  std::unique_ptr<float[]> processedScratch_;
  std::unique_ptr<float[]> outputScratch_;
  std::unique_ptr<float[]> convertFloat_;
  std::unique_ptr<int16_t[]> convertInt_;
  int scratchFrames_ = 0;
  int keepFrames_ = 192;

  std::atomic<bool> running_{false};
  std::atomic<float> gain_{0.85f};
  std::atomic<float> reverbMix_{0.06f};
  std::atomic<float> echoMix_{0.f};
  std::atomic<bool> gateEnabled_{true};
  std::atomic<bool> muted_{false};
  std::atomic<int> space_{0};
  std::atomic<float> rms_{0.f};
  std::atomic<float> peak_{0.f};
  std::atomic<int> sampleRate_{0};
  std::atomic<double> latencyMs_{0.0};
  std::atomic<bool> lowLatency_{false};

  std::string backend_ = "Oboe";
  std::string lastError_;
};
