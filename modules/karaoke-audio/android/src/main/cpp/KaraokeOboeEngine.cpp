#include "KaraokeOboeEngine.h"

#include "AudioTiming.h"
#include "LockFreeRingBuffer.h"
#include "dsp/VocalDsp.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <utility>

KaraokeOboeEngine::~KaraokeOboeEngine() {
  stop();
}

namespace {

constexpr int kScratchFrames = 4096;

int positiveFrames(const std::shared_ptr<oboe::AudioStream>& stream) {
  if (!stream) {
    return 0;
  }
  int size = stream->getBufferSizeInFrames();
  if (size <= 0) {
    size = stream->getFramesPerBurst();
  }
  return std::max(0, size);
}

float clampUnit(float value, float high) {
  return std::max(0.f, std::min(high, value));
}

}  // namespace

bool KaraokeOboeEngine::startDuplex(int sampleRate, int framesPerBurst, int inputDeviceId, int outputDeviceId) {
  std::lock_guard<std::mutex> lock(control_);
  if (running_.load(std::memory_order_acquire) && mode_ == Mode::Duplex) {
    return true;
  }
  running_.store(false, std::memory_order_release);
  closeStream(input_);
  closeStream(output_);
  input_.reset();
  output_.reset();
  oboe::DefaultStreamValues::SampleRate = sampleRate > 0 ? sampleRate : 48000;
  oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst > 0 ? framesPerBurst : 192;
  if (!openOutput(sampleRate, framesPerBurst, outputDeviceId, true)) {
    setError("The low-latency speaker stream did not open.");
    return false;
  }
  const int openedRate = output_->getSampleRate();
  if (!openInput(openedRate > 0 ? openedRate : sampleRate, framesPerBurst, inputDeviceId, true)) {
    closeStream(output_);
    output_.reset();
    setError("The low-latency microphone stream did not open.");
    return false;
  }
  const int rate = input_->getSampleRate() > 0 ? input_->getSampleRate() : openedRate;
  dsp_ = std::make_unique<VocalDsp>(rate > 0 ? rate : 48000);
  const int burst = std::max(output_->getFramesPerBurst(), framesPerBurst);
  ring_ = std::make_unique<LockFreeRingBuffer>(std::max(2048, burst * 16));
  keepFrames_ = std::max(burst, 96);
  ensureScratch(std::max(kScratchFrames, burst * 4));
  tune(output_, framesPerBurst);
  tune(input_, framesPerBurst);
  rememberStream(output_, positiveFrames(input_), positiveFrames(output_));
  mode_ = Mode::Duplex;
  running_.store(true, std::memory_order_release);
  if (input_->requestStart() != oboe::Result::OK || output_->requestStart() != oboe::Result::OK) {
    running_.store(false, std::memory_order_release);
    closeStream(input_);
    closeStream(output_);
    input_.reset();
    output_.reset();
    mode_ = Mode::Idle;
    setError("The low-latency stream did not start.");
    return false;
  }
  return true;
}

bool KaraokeOboeEngine::startInput(int sampleRate, int framesPerBurst, int inputDeviceId) {
  std::lock_guard<std::mutex> lock(control_);
  if (running_.load(std::memory_order_acquire) && mode_ == Mode::Input) {
    return true;
  }
  running_.store(false, std::memory_order_release);
  closeStream(input_);
  closeStream(output_);
  input_.reset();
  output_.reset();
  oboe::DefaultStreamValues::SampleRate = sampleRate > 0 ? sampleRate : 48000;
  oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst > 0 ? framesPerBurst : 240;
  if (!openInput(sampleRate, framesPerBurst, inputDeviceId, false)) {
    setError("The low-latency microphone stream did not open.");
    return false;
  }
  ensureScratch(kScratchFrames);
  tune(input_, framesPerBurst);
  rememberStream(input_, positiveFrames(input_), 0);
  mode_ = Mode::Input;
  running_.store(true, std::memory_order_release);
  if (input_->requestStart() != oboe::Result::OK) {
    running_.store(false, std::memory_order_release);
    closeStream(input_);
    input_.reset();
    mode_ = Mode::Idle;
    setError("The low-latency microphone stream did not start.");
    return false;
  }
  return true;
}

bool KaraokeOboeEngine::startOutput(int sampleRate, int framesPerBurst, int outputDeviceId) {
  std::lock_guard<std::mutex> lock(control_);
  if (running_.load(std::memory_order_acquire) && mode_ == Mode::Output) {
    return true;
  }
  running_.store(false, std::memory_order_release);
  closeStream(input_);
  closeStream(output_);
  input_.reset();
  output_.reset();
  oboe::DefaultStreamValues::SampleRate = sampleRate > 0 ? sampleRate : 48000;
  oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst > 0 ? framesPerBurst : 240;
  if (!openOutput(sampleRate, framesPerBurst, outputDeviceId, false)) {
    setError("The low-latency speaker stream did not open.");
    return false;
  }
  ensureScratch(kScratchFrames);
  tune(output_, framesPerBurst);
  rememberStream(output_, 0, positiveFrames(output_));
  mode_ = Mode::Output;
  running_.store(true, std::memory_order_release);
  if (output_->requestStart() != oboe::Result::OK) {
    running_.store(false, std::memory_order_release);
    closeStream(output_);
    output_.reset();
    mode_ = Mode::Idle;
    setError("The low-latency speaker stream did not start.");
    return false;
  }
  return true;
}

void KaraokeOboeEngine::stop() {
  std::shared_ptr<oboe::AudioStream> input;
  std::shared_ptr<oboe::AudioStream> output;
  {
    std::lock_guard<std::mutex> lock(control_);
    running_.store(false, std::memory_order_release);
    input = std::move(input_);
    output = std::move(output_);
    mode_ = Mode::Idle;
    lowLatency_.store(false, std::memory_order_relaxed);
  }
  closeStream(input);
  closeStream(output);
  std::lock_guard<std::mutex> lock(control_);
  dsp_.reset();
  ring_.reset();
}

int KaraokeOboeEngine::readFloats(float* destination, int frames, int64_t timeoutNanoseconds) {
  std::shared_ptr<oboe::AudioStream> stream;
  {
    std::lock_guard<std::mutex> lock(control_);
    if (mode_ != Mode::Input || !running_.load(std::memory_order_acquire) || !input_ || destination == nullptr) {
      return -1;
    }
    stream = input_;
  }
  const int channels = std::max(1, stream->getChannelCount());
  const int request = std::max(1, std::min(frames, scratchFrames_ > 0 ? scratchFrames_ : frames));
  const bool isFloat = stream->getFormat() == oboe::AudioFormat::Float;
  void* raw = isFloat ? static_cast<void*>(convertFloat_.get()) : static_cast<void*>(convertInt_.get());
  if (raw == nullptr) {
    return -1;
  }
  auto result = stream->read(raw, request, timeoutNanoseconds);
  if (!result) {
    return result.error() == oboe::Result::ErrorTimeout ? 0 : -1;
  }
  const int got = std::max(0, result.value());
  if (isFloat) {
    const float* input = convertFloat_.get();
    for (int frame = 0; frame < got; ++frame) {
      float sum = 0.f;
      for (int channel = 0; channel < channels; ++channel) {
        sum += input[frame * channels + channel];
      }
      destination[frame] = sum / static_cast<float>(channels);
    }
  } else {
    const int16_t* input = convertInt_.get();
    for (int frame = 0; frame < got; ++frame) {
      float sum = 0.f;
      for (int channel = 0; channel < channels; ++channel) {
        sum += static_cast<float>(input[frame * channels + channel]) / 32768.f;
      }
      destination[frame] = sum / static_cast<float>(channels);
    }
  }
  updateLevels(destination, got);
  return got;
}

int KaraokeOboeEngine::writeShorts(const int16_t* mono, int frames, int64_t timeoutNanoseconds) {
  std::shared_ptr<oboe::AudioStream> stream;
  {
    std::lock_guard<std::mutex> lock(control_);
    if (mode_ != Mode::Output || !running_.load(std::memory_order_acquire) || !output_ || mono == nullptr) {
      return -1;
    }
    stream = output_;
  }
  const int channels = std::max(1, stream->getChannelCount());
  const int request = std::max(1, std::min(frames, scratchFrames_ > 0 ? scratchFrames_ : frames));
  const bool isFloat = stream->getFormat() == oboe::AudioFormat::Float;
  if (isFloat) {
    if (!convertFloat_) {
      return -1;
    }
    for (int frame = 0; frame < request; ++frame) {
      const float sample = static_cast<float>(mono[frame]) / 32768.f;
      for (int channel = 0; channel < channels; ++channel) {
        convertFloat_[frame * channels + channel] = sample;
      }
    }
    auto result = stream->write(convertFloat_.get(), request, timeoutNanoseconds);
    if (!result) {
      return result.error() == oboe::Result::ErrorTimeout ? 0 : -1;
    }
    return std::max(0, result.value());
  }
  if (!convertInt_) {
    return -1;
  }
  for (int frame = 0; frame < request; ++frame) {
    for (int channel = 0; channel < channels; ++channel) {
      convertInt_[frame * channels + channel] = mono[frame];
    }
  }
  auto result = stream->write(convertInt_.get(), request, timeoutNanoseconds);
  if (!result) {
    return result.error() == oboe::Result::ErrorTimeout ? 0 : -1;
  }
  return std::max(0, result.value());
}

void KaraokeOboeEngine::setGain(float value) { gain_.store(clampUnit(value, 1.5f), std::memory_order_relaxed); }
void KaraokeOboeEngine::setReverbMix(float value) { reverbMix_.store(clampUnit(value, 1.f), std::memory_order_relaxed); }
void KaraokeOboeEngine::setEchoMix(float value) { echoMix_.store(clampUnit(value, 1.f), std::memory_order_relaxed); }
void KaraokeOboeEngine::setGateEnabled(bool enabled) { gateEnabled_.store(enabled, std::memory_order_relaxed); }
void KaraokeOboeEngine::setMuted(bool muted) { muted_.store(muted, std::memory_order_relaxed); }

void KaraokeOboeEngine::setSpace(const char* name) {
  int space = 0;
  if (name != nullptr && name[0] == 'h') {
    space = 2;
  } else if (name != nullptr && name[0] == 's') {
    space = 1;
  }
  space_.store(space, std::memory_order_relaxed);
}

int KaraokeOboeEngine::sampleRate() const { return sampleRate_.load(std::memory_order_relaxed); }
double KaraokeOboeEngine::latencyMs() const { return latencyMs_.load(std::memory_order_relaxed); }
bool KaraokeOboeEngine::lowLatency() const { return lowLatency_.load(std::memory_order_relaxed); }
bool KaraokeOboeEngine::isRunning() const { return running_.load(std::memory_order_acquire); }
float KaraokeOboeEngine::rms() const { return rms_.load(std::memory_order_relaxed); }
float KaraokeOboeEngine::peak() const { return peak_.load(std::memory_order_relaxed); }

std::string KaraokeOboeEngine::backend() const {
  std::lock_guard<std::mutex> lock(control_);
  return backend_;
}

std::string KaraokeOboeEngine::lastError() const {
  std::lock_guard<std::mutex> lock(errorMutex_);
  return lastError_;
}

oboe::DataCallbackResult KaraokeOboeEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames) {
  if (!running_.load(std::memory_order_acquire) || stream == nullptr || numFrames <= 0) {
    return oboe::DataCallbackResult::Stop;
  }
  if (input_ && stream == input_.get()) {
    handleInput(stream, audioData, numFrames);
  } else {
    handleOutput(stream, audioData, numFrames);
  }
  return oboe::DataCallbackResult::Continue;
}

void KaraokeOboeEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
  running_.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(errorMutex_);
  lastError_ = std::string("The audio stream closed: ") + oboe::convertToText(error);
  __android_log_print(ANDROID_LOG_WARN, "StageMic", "%s", lastError_.c_str());
}

bool KaraokeOboeEngine::openOutput(int sampleRate, int framesPerBurst, int deviceId, bool withCallback) {
  const int burst = framesPerBurst > 0 ? framesPerBurst : 0;
  const oboe::SharingMode exclusive = oboe::SharingMode::Exclusive;
  const oboe::SharingMode shared = oboe::SharingMode::Shared;
  const oboe::PerformanceMode low = oboe::PerformanceMode::LowLatency;
  const oboe::PerformanceMode none = oboe::PerformanceMode::None;
  const oboe::InputPreset ignored = oboe::InputPreset::Generic;
  if (tryOpen(oboe::Direction::Output, 2, sampleRate, burst, deviceId, exclusive, low, oboe::AudioFormat::Unspecified, ignored, false, withCallback, output_)) {
    return true;
  }
  if (tryOpen(oboe::Direction::Output, 2, sampleRate, burst, 0, exclusive, low, oboe::AudioFormat::Unspecified, ignored, false, withCallback, output_)) {
    return true;
  }
  if (tryOpen(oboe::Direction::Output, 2, sampleRate, burst, 0, shared, low, oboe::AudioFormat::Unspecified, ignored, false, withCallback, output_)) {
    return true;
  }
  if (tryOpen(oboe::Direction::Output, 2, sampleRate, 0, 0, shared, low, oboe::AudioFormat::I16, ignored, true, withCallback, output_)) {
    return true;
  }
  if (tryOpen(oboe::Direction::Output, 2, sampleRate, 0, 0, shared, low, oboe::AudioFormat::Float, ignored, true, withCallback, output_)) {
    return true;
  }
  return tryOpen(oboe::Direction::Output, 2, 0, 0, 0, shared, none, oboe::AudioFormat::Float, ignored, true, withCallback, output_);
}

bool KaraokeOboeEngine::openInput(int sampleRate, int framesPerBurst, int deviceId, bool withCallback) {
  const int burst = framesPerBurst > 0 ? framesPerBurst : 0;
  const oboe::InputPreset presets[] = {
      oboe::InputPreset::VoicePerformance,
      oboe::InputPreset::Unprocessed,
      oboe::InputPreset::VoiceRecognition,
      oboe::InputPreset::Generic,
  };
  for (oboe::InputPreset preset : presets) {
    if (tryOpen(oboe::Direction::Input, 1, sampleRate, burst, deviceId, oboe::SharingMode::Exclusive, oboe::PerformanceMode::LowLatency, oboe::AudioFormat::Unspecified, preset, false, withCallback, input_)) {
      return true;
    }
    if (tryOpen(oboe::Direction::Input, 1, sampleRate, burst, 0, oboe::SharingMode::Shared, oboe::PerformanceMode::LowLatency, oboe::AudioFormat::Unspecified, preset, false, withCallback, input_)) {
      return true;
    }
  }
  return tryOpen(
      oboe::Direction::Input,
      1,
      sampleRate,
      0,
      0,
      oboe::SharingMode::Shared,
      oboe::PerformanceMode::None,
      oboe::AudioFormat::I16,
      oboe::InputPreset::Generic,
      true,
      withCallback,
      input_);
}

bool KaraokeOboeEngine::tryOpen(
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
    std::shared_ptr<oboe::AudioStream>& out) {
  oboe::AudioStreamBuilder builder;
  builder.setDirection(direction);
  builder.setSharingMode(sharing);
  builder.setPerformanceMode(performance);
  builder.setFormat(format);
  builder.setChannelCount(channels);
  builder.setFormatConversionAllowed(allowConversion);
  builder.setSampleRateConversionQuality(
      allowConversion ? oboe::SampleRateConversionQuality::Low : oboe::SampleRateConversionQuality::None);
  if (sampleRate > 0) {
    builder.setSampleRate(sampleRate);
  }
  if (deviceId > 0) {
    builder.setDeviceId(deviceId);
  }
  if (withCallback && framesPerCallback > 0) {
    builder.setFramesPerCallback(framesPerCallback);
  }
  if (direction == oboe::Direction::Output) {
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
  } else {
    builder.setInputPreset(preset);
  }
  if (withCallback) {
    builder.setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(shared_from_this()));
    builder.setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(shared_from_this()));
  }
  const oboe::Result result = builder.openStream(out);
  if (result != oboe::Result::OK || !out) {
    if (out) {
      out->close();
      out.reset();
    }
    {
      std::lock_guard<std::mutex> lock(errorMutex_);
      lastError_ = oboe::convertToText(result);
    }
    return false;
  }
  return true;
}

void KaraokeOboeEngine::tune(const std::shared_ptr<oboe::AudioStream>& stream, int requestedBurst) {
  if (!stream) {
    return;
  }
  int burst = stream->getFramesPerBurst();
  if (burst <= 0) {
    burst = requestedBurst;
  }
  if (burst > 0) {
    stream->setBufferSizeInFrames(burst);
  }
}

void KaraokeOboeEngine::rememberStream(
    const std::shared_ptr<oboe::AudioStream>& stream,
    int inputFrames,
    int outputFrames) {
  const int rate = stream && stream->getSampleRate() > 0 ? stream->getSampleRate() : 48000;
  sampleRate_.store(rate, std::memory_order_relaxed);
  latencyMs_.store(latencyMilliseconds(inputFrames + outputFrames, rate), std::memory_order_relaxed);
  BackendApi api = BackendApi::Other;
  if (stream && stream->getAudioApi() == oboe::AudioApi::AAudio) {
    api = BackendApi::AAudio;
  } else if (stream && stream->getAudioApi() == oboe::AudioApi::OpenSLES) {
    api = BackendApi::OpenSLES;
  }
  const bool low = stream && stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
  lowLatency_.store(low, std::memory_order_relaxed);
  backend_ = backendLabel(api, low ? BackendPerformance::LowLatency : BackendPerformance::Other);
  std::lock_guard<std::mutex> lock(errorMutex_);
  lastError_.clear();
}

void KaraokeOboeEngine::closeStream(const std::shared_ptr<oboe::AudioStream>& stream) {
  if (!stream) {
    return;
  }
  stream->close();
}

void KaraokeOboeEngine::ensureScratch(int frames) {
  if (scratchFrames_ >= frames && inputScratch_ && convertFloat_ && convertInt_) {
    return;
  }
  scratchFrames_ = frames;
  inputScratch_ = std::make_unique<float[]>(static_cast<size_t>(frames));
  processedScratch_ = std::make_unique<float[]>(static_cast<size_t>(frames));
  outputScratch_ = std::make_unique<float[]>(static_cast<size_t>(frames));
  convertFloat_ = std::make_unique<float[]>(static_cast<size_t>(frames) * 2);
  convertInt_ = std::make_unique<int16_t[]>(static_cast<size_t>(frames) * 2);
}

void KaraokeOboeEngine::applyDspControls() {
  if (!dsp_) {
    return;
  }
  dsp_->setGain(gain_.load(std::memory_order_relaxed));
  dsp_->setReverbMix(reverbMix_.load(std::memory_order_relaxed));
  dsp_->setEchoMix(echoMix_.load(std::memory_order_relaxed));
  dsp_->setGateEnabled(gateEnabled_.load(std::memory_order_relaxed));
  dsp_->setMuted(muted_.load(std::memory_order_relaxed));
  const int space = space_.load(std::memory_order_relaxed);
  dsp_->setSpace(space == 2 ? "hall" : space == 1 ? "studio" : "clean");
}

void KaraokeOboeEngine::updateLevels(const float* samples, int count) {
  if (samples == nullptr || count <= 0) {
    return;
  }
  double sum = 0.0;
  float peak = 0.f;
  for (int index = 0; index < count; ++index) {
    const float magnitude = std::fabs(samples[index]);
    sum += static_cast<double>(magnitude) * static_cast<double>(magnitude);
    if (magnitude > peak) {
      peak = magnitude;
    }
  }
  rms_.store(static_cast<float>(std::sqrt(sum / count)), std::memory_order_relaxed);
  peak_.store(peak, std::memory_order_relaxed);
}

void KaraokeOboeEngine::readMono(
    oboe::AudioStream* stream,
    const void* audioData,
    int frameOffset,
    int frames,
    float* mono) {
  const int channels = std::max(1, stream->getChannelCount());
  if (stream->getFormat() == oboe::AudioFormat::Float) {
    const float* input = static_cast<const float*>(audioData) + frameOffset * channels;
    for (int frame = 0; frame < frames; ++frame) {
      float sum = 0.f;
      for (int channel = 0; channel < channels; ++channel) {
        sum += input[frame * channels + channel];
      }
      mono[frame] = sum / static_cast<float>(channels);
    }
    return;
  }
  const int16_t* input = static_cast<const int16_t*>(audioData) + frameOffset * channels;
  for (int frame = 0; frame < frames; ++frame) {
    float sum = 0.f;
    for (int channel = 0; channel < channels; ++channel) {
      sum += static_cast<float>(input[frame * channels + channel]) / 32768.f;
    }
    mono[frame] = sum / static_cast<float>(channels);
  }
}

void KaraokeOboeEngine::writeMono(
    oboe::AudioStream* stream,
    const float* mono,
    int frames,
    void* audioData,
    int frameOffset) {
  const int channels = std::max(1, stream->getChannelCount());
  if (stream->getFormat() == oboe::AudioFormat::Float) {
    float* output = static_cast<float*>(audioData) + frameOffset * channels;
    for (int frame = 0; frame < frames; ++frame) {
      for (int channel = 0; channel < channels; ++channel) {
        output[frame * channels + channel] = mono[frame];
      }
    }
    return;
  }
  int16_t* output = static_cast<int16_t*>(audioData) + frameOffset * channels;
  for (int frame = 0; frame < frames; ++frame) {
    const float clamped = std::max(-1.f, std::min(1.f, mono[frame]));
    const auto sample = static_cast<int16_t>(clamped * 32767.f);
    for (int channel = 0; channel < channels; ++channel) {
      output[frame * channels + channel] = sample;
    }
  }
}

void KaraokeOboeEngine::handleInput(oboe::AudioStream* stream, const void* audioData, int32_t numFrames) {
  if (!dsp_ || !inputScratch_ || !processedScratch_) {
    return;
  }
  int offset = 0;
  while (offset < numFrames) {
    const int chunk = std::min(numFrames - offset, scratchFrames_);
    readMono(stream, audioData, offset, chunk, inputScratch_.get());
    applyDspControls();
    dsp_->process(inputScratch_.get(), processedScratch_.get(), chunk);
    if (ring_) {
      ring_->write(processedScratch_.get(), chunk);
    }
    updateLevels(processedScratch_.get(), chunk);
    offset += chunk;
  }
}

void KaraokeOboeEngine::handleOutput(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
  if (!ring_ || !outputScratch_) {
    const int channels = std::max(1, stream->getChannelCount());
    const int samples = numFrames * channels;
    if (stream->getFormat() == oboe::AudioFormat::Float) {
      std::fill_n(static_cast<float*>(audioData), static_cast<size_t>(samples), 0.f);
    } else {
      std::fill_n(static_cast<int16_t*>(audioData), static_cast<size_t>(samples), static_cast<int16_t>(0));
    }
    return;
  }
  const int keep = std::max(keepFrames_, numFrames);
  const int ready = ring_->available();
  if (ready > keep) {
    ring_->discard(ready - keep);
  }
  int offset = 0;
  while (offset < numFrames) {
    const int chunk = std::min(numFrames - offset, scratchFrames_);
    const int got = ring_->read(outputScratch_.get(), chunk);
    for (int index = got; index < chunk; ++index) {
      outputScratch_[index] = 0.f;
    }
    writeMono(stream, outputScratch_.get(), chunk, audioData, offset);
    offset += chunk;
  }
}

void KaraokeOboeEngine::setError(const std::string& message) {
  std::lock_guard<std::mutex> lock(errorMutex_);
  if (!lastError_.empty()) {
    lastError_ = message + " " + lastError_;
  } else {
    lastError_ = message;
  }
  __android_log_print(ANDROID_LOG_WARN, "StageMic", "%s", lastError_.c_str());
}
