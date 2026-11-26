#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <vector>

/**
 * Zero-allocation vocal chain. Buffers are allocated in the constructor.
 * process() only reads atomics and writes the existing delay lines.
 * The coefficients match the Kotlin VocalProcessor used by the AudioTrack fallback.
 */
class VocalDsp {
 public:
  explicit VocalDsp(int sampleRate)
      : sampleRate_(std::max(sampleRate, 1)),
        highpass_(sampleRate_, 90.f),
        gate_(sampleRate_),
        echo_(sampleRate_),
        reverb_(sampleRate_) {}

  void setGain(float value) { gain_.store(clamp(value, 0.f, 1.5f), std::memory_order_relaxed); }
  void setReverbMix(float value) { reverbMix_.store(clamp(value, 0.f, 1.f), std::memory_order_relaxed); }
  void setEchoMix(float value) { echoMix_.store(clamp(value, 0.f, 1.f), std::memory_order_relaxed); }
  void setGateEnabled(bool enabled) { gateEnabled_.store(enabled, std::memory_order_relaxed); }
  void setMuted(bool muted) { muted_.store(muted, std::memory_order_relaxed); }

  void setSpace(const char* name) {
    const bool studio = name != nullptr && name[0] == 's';
    const bool hall = name != nullptr && name[0] == 'h';
    if (hall) {
      room_.store(0.9f, std::memory_order_relaxed);
      damp_.store(0.2f, std::memory_order_relaxed);
      decay_.store(0.86f, std::memory_order_relaxed);
      echoDelay_.store(0.28f, std::memory_order_relaxed);
      echoFeedback_.store(0.36f, std::memory_order_relaxed);
      return;
    }
    if (studio) {
      room_.store(0.5f, std::memory_order_relaxed);
      damp_.store(0.32f, std::memory_order_relaxed);
      decay_.store(0.74f, std::memory_order_relaxed);
      echoDelay_.store(0.16f, std::memory_order_relaxed);
      echoFeedback_.store(0.28f, std::memory_order_relaxed);
      return;
    }
    room_.store(0.22f, std::memory_order_relaxed);
    damp_.store(0.5f, std::memory_order_relaxed);
    decay_.store(0.5f, std::memory_order_relaxed);
    echoDelay_.store(0.12f, std::memory_order_relaxed);
    echoFeedback_.store(0.16f, std::memory_order_relaxed);
  }

  void process(const float* input, float* output, int frames) {
    const float gain = gain_.load(std::memory_order_relaxed);
    const bool muted = muted_.load(std::memory_order_relaxed);
    gate_.enabled = gateEnabled_.load(std::memory_order_relaxed);
    echo_.mix = echoMix_.load(std::memory_order_relaxed);
    echo_.delaySeconds = echoDelay_.load(std::memory_order_relaxed);
    echo_.feedback = echoFeedback_.load(std::memory_order_relaxed);
    reverb_.mix = reverbMix_.load(std::memory_order_relaxed);
    reverb_.room = room_.load(std::memory_order_relaxed);
    reverb_.damp = damp_.load(std::memory_order_relaxed);
    reverb_.decay = decay_.load(std::memory_order_relaxed);
    for (int index = 0; index < frames; ++index) {
      float sample = highpass_.process(input[index]) * gain;
      sample = gate_.process(sample);
      sample = echo_.process(sample);
      sample = reverb_.process(sample);
      sample = limiter_.process(sample);
      output[index] = muted ? 0.f : sample;
    }
  }

 private:
  static float clamp(float value, float low, float high) {
    return std::max(low, std::min(high, value));
  }

  class OnePoleHighpass {
   public:
    OnePoleHighpass(int sampleRate, float cutoffHz) {
      const double dt = 1.0 / static_cast<double>(std::max(sampleRate, 1));
      const double rc = 1.0 / (2.0 * 3.14159265358979323846 * static_cast<double>(std::max(cutoffHz, 1.f)));
      alpha_ = static_cast<float>(rc / (rc + dt));
    }

    float process(float sample) {
      const float output = alpha_ * (previousOutput_ + sample - previousInput_);
      previousInput_ = sample;
      previousOutput_ = std::fabs(output) < 1e-8f ? 0.f : output;
      return previousOutput_;
    }

   private:
    float alpha_ = 0.f;
    float previousInput_ = 0.f;
    float previousOutput_ = 0.f;
  };

  class NoiseGate {
   public:
    explicit NoiseGate(int sampleRate)
        : attackEnvelope_(coefficient(sampleRate, 0.004f)),
          releaseEnvelope_(coefficient(sampleRate, 0.130f)),
          attackGain_(coefficient(sampleRate, 0.003f)),
          releaseGain_(coefficient(sampleRate, 0.090f)),
          holdSamples_(std::max(1, static_cast<int>(0.045f * sampleRate))) {}

    bool enabled = true;

    float process(float sample) {
      if (!enabled) {
        gain_ = 1.f;
        return sample;
      }
      const float level = std::fabs(sample);
      const float envelopeCoefficient = level > envelope_ ? attackEnvelope_ : releaseEnvelope_;
      envelope_ += (level - envelope_) * envelopeCoefficient;
      if (envelope_ >= threshold_) {
        hold_ = holdSamples_;
      } else if (hold_ > 0) {
        hold_ -= 1;
      }
      const float target = (envelope_ >= threshold_ || hold_ > 0) ? 1.f : 0.f;
      const float gainCoefficient = target > gain_ ? attackGain_ : releaseGain_;
      gain_ += (target - gain_) * gainCoefficient;
      return sample * gain_;
    }

   private:
    static float coefficient(int sampleRate, float seconds) {
      const float frames = std::max(1.f, seconds * static_cast<float>(sampleRate));
      return static_cast<float>(1.0 - std::exp(-1.0 / static_cast<double>(frames)));
    }

    float threshold_ = 0.02f;
    float attackEnvelope_;
    float releaseEnvelope_;
    float attackGain_;
    float releaseGain_;
    int holdSamples_;
    float envelope_ = 0.f;
    float gain_ = 0.f;
    int hold_ = 0;
  };

  class Echo {
   public:
    explicit Echo(int sampleRate)
        : sampleRate_(sampleRate),
          maxDelay_(std::max(8, static_cast<int>(sampleRate * 1.25f))),
          buffer_(static_cast<size_t>(std::max(8, static_cast<int>(sampleRate * 1.25f)))) {}

    float mix = 0.f;
    float feedback = 0.28f;
    float delaySeconds = 0.18f;

    float process(float input) {
      const int delaySamples = std::clamp(
          static_cast<int>(delaySeconds * static_cast<float>(sampleRate_)), 1, maxDelay_ - 1);
      int readIndex = writeIndex_ - delaySamples;
      if (readIndex < 0) {
        readIndex += maxDelay_;
      }
      const float delayed = buffer_[static_cast<size_t>(readIndex)];
      const float feedbackGain = clamp(feedback, 0.f, 0.75f);
      const float written = input + delayed * feedbackGain;
      buffer_[static_cast<size_t>(writeIndex_)] = std::fabs(written) < 1e-8f ? 0.f : written;
      writeIndex_ += 1;
      if (writeIndex_ >= maxDelay_) {
        writeIndex_ = 0;
      }
      const float wet = clamp(mix, 0.f, 1.f);
      if (wet < 0.001f) {
        return input;
      }
      return input * (1.f - wet) + delayed * wet;
    }

   private:
    int sampleRate_;
    int maxDelay_;
    std::vector<float> buffer_;
    int writeIndex_ = 0;
  };

  class SchroederReverb {
   public:
    explicit SchroederReverb(int sampleRate) {
      const int combDelays[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
      const int allpassDelays[2] = {225, 556};
      for (int delay : combDelays) {
        combs_.emplace_back(scale(delay, sampleRate));
      }
      for (int delay : allpassDelays) {
        allpasses_.emplace_back(scale(delay, sampleRate));
      }
    }

    float mix = 0.f;
    float room = 0.25f;
    float damp = 0.45f;
    float decay = 0.55f;

    float process(float input) {
      const float wetMix = clamp(mix, 0.f, 1.f);
      if (wetMix < 0.001f) {
        return input;
      }
      const float feedback = clamp(decay, 0.15f, 0.92f);
      const float damping = clamp(damp, 0.05f, 0.9f);
      float sum = 0.f;
      for (Comb& comb : combs_) {
        sum += comb.process(input, feedback, damping);
      }
      float wet = sum / static_cast<float>(combs_.size());
      for (Allpass& allpass : allpasses_) {
        wet = allpass.process(wet);
      }
      const float roomGain = 0.35f + clamp(room, 0.f, 1.f) * 0.45f;
      return input * (1.f - wetMix) + (wet * roomGain) * wetMix;
    }

   private:
    static int scale(int baseSamplesAt44100, int sampleRate) {
      return std::max(8, static_cast<int>(baseSamplesAt44100 * (sampleRate / 44100.f)));
    }

    class Comb {
     public:
      explicit Comb(int delaySamples) : buffer_(static_cast<size_t>(delaySamples)), delaySamples_(delaySamples) {}

      float process(float input, float feedback, float damp) {
        const float output = buffer_[static_cast<size_t>(index_)];
        filter_ = output * (1.f - damp) + filter_ * damp;
        if (std::fabs(filter_) < 1e-8f) {
          filter_ = 0.f;
        }
        buffer_[static_cast<size_t>(index_)] = input + filter_ * feedback;
        index_ += 1;
        if (index_ >= delaySamples_) {
          index_ = 0;
        }
        return output;
      }

     private:
      std::vector<float> buffer_;
      int delaySamples_;
      int index_ = 0;
      float filter_ = 0.f;
    };

    class Allpass {
     public:
      explicit Allpass(int delaySamples) : buffer_(static_cast<size_t>(delaySamples)), delaySamples_(delaySamples) {}

      float process(float input) {
        const float buffered = buffer_[static_cast<size_t>(index_)];
        const float output = -input + buffered;
        buffer_[static_cast<size_t>(index_)] = input + buffered * 0.5f;
        index_ += 1;
        if (index_ >= delaySamples_) {
          index_ = 0;
        }
        return output;
      }

     private:
      std::vector<float> buffer_;
      int delaySamples_;
      int index_ = 0;
    };

    std::vector<Comb> combs_;
    std::vector<Allpass> allpasses_;
  };

  class SoftLimiter {
   public:
    float process(float sample) const {
      const float magnitude = std::fabs(sample);
      if (magnitude <= kKnee) {
        return sample;
      }
      const float sign = sample < 0.f ? -1.f : 1.f;
      const float over = magnitude - kKnee;
      const float shaped = kKnee + kHeadroom * std::tanh(over / kHeadroom);
      return sign * shaped;
    }

   private:
    static constexpr float kKnee = 0.9f;
    static constexpr float kHeadroom = 0.099f;
  };

  int sampleRate_;
  OnePoleHighpass highpass_;
  NoiseGate gate_;
  Echo echo_;
  SchroederReverb reverb_;
  SoftLimiter limiter_;
  std::atomic<float> gain_{0.85f};
  std::atomic<float> reverbMix_{0.06f};
  std::atomic<float> echoMix_{0.f};
  std::atomic<float> room_{0.22f};
  std::atomic<float> damp_{0.5f};
  std::atomic<float> decay_{0.5f};
  std::atomic<float> echoDelay_{0.12f};
  std::atomic<float> echoFeedback_{0.16f};
  std::atomic<bool> gateEnabled_{true};
  std::atomic<bool> muted_{false};
};
