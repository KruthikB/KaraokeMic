#include "AudioTiming.h"
#include "LockFreeRingBuffer.h"
#include "dsp/VocalDsp.h"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

namespace {

int failures = 0;

void expect(bool condition, const char* message) {
  if (!condition) {
    std::cerr << message << "\n";
    failures += 1;
  }
}

void testEchoImpulse() {
  VocalDsp dsp(48000);
  dsp.setGain(1.f);
  dsp.setGateEnabled(false);
  dsp.setReverbMix(0.f);
  dsp.setEchoMix(1.f);
  dsp.setSpace("clean");
  // Clean space uses a 0.12 s echo. Drive one impulse and look for energy later.
  std::vector<float> input(48000, 0.f);
  std::vector<float> output(48000, 0.f);
  input[0] = 1.f;
  dsp.process(input.data(), output.data(), static_cast<int>(input.size()));
  const int delay = static_cast<int>(0.12f * 48000.f);
  expect(std::fabs(output[delay]) > 0.2f, "echo did not repeat the impulse near 120 ms");
  expect(std::fabs(output[delay / 2]) < 0.05f, "echo leaked into the middle of the delay");
}

void testGate() {
  VocalDsp quiet(48000);
  quiet.setGain(1.f);
  quiet.setGateEnabled(true);
  quiet.setEchoMix(0.f);
  quiet.setReverbMix(0.f);
  std::vector<float> input(24000, 0.002f);
  std::vector<float> output(24000, 0.f);
  quiet.process(input.data(), output.data(), static_cast<int>(input.size()));
  expect(std::fabs(output.back()) < 0.0005f, "gate left a quiet sample open");

  VocalDsp loud(48000);
  loud.setGain(1.f);
  loud.setGateEnabled(true);
  loud.setEchoMix(0.f);
  loud.setReverbMix(0.f);
  for (int index = 0; index < 4800; ++index) {
    input[static_cast<size_t>(index)] =
        0.25f * std::sin(2.0 * 3.14159265358979323846 * 1000.0 * index / 48000.0);
  }
  loud.process(input.data(), output.data(), 4800);
  expect(std::fabs(output[4700]) > 0.05f, "gate did not open for a sung tone");
}

void testLimiter() {
  VocalDsp dsp(48000);
  dsp.setGain(1.5f);
  dsp.setGateEnabled(false);
  dsp.setEchoMix(0.f);
  dsp.setReverbMix(0.f);
  std::vector<float> input(4000, 0.f);
  std::vector<float> output(4000, 0.f);
  for (int index = 0; index < 4000; ++index) {
    input[static_cast<size_t>(index)] =
        std::sin(2.0 * 3.14159265358979323846 * 1000.0 * index / 48000.0);
  }
  dsp.process(input.data(), output.data(), 4000);
  bool heard = false;
  for (int index = 1000; index < 4000; ++index) {
    if (std::fabs(output[static_cast<size_t>(index)]) > 1.01f) {
      expect(false, "limiter let a sample past full scale");
      return;
    }
    if (std::fabs(output[static_cast<size_t>(index)]) > 0.5f) {
      heard = true;
    }
  }
  expect(heard, "limiter silenced a loud tone");
}

void testRing() {
  LockFreeRingBuffer ring(8);
  const float input[5] = {1.f, 2.f, 3.f, 4.f, 5.f};
  expect(ring.write(input, 5) == 5, "ring rejected a short write");
  float output[5] = {};
  expect(ring.read(output, 5) == 5, "ring short-read");
  expect(output[0] == 1.f && output[4] == 5.f, "ring reordered samples");
  expect(ring.write(input, 5) == 5, "ring rejected the second write");
  expect(ring.available() == 5, "ring available count drifted");
  ring.discard(3);
  expect(ring.available() == 2, "ring discard did not drop the oldest frames");
}

void testRingThreads() {
  LockFreeRingBuffer ring(1024);
  constexpr int kCount = 20000;
  std::atomic<int> started{0};
  std::thread producer([&] {
    started.fetch_add(1);
    while (started.load() < 2) {
    }
    int sent = 0;
    while (sent < kCount) {
      float value = static_cast<float>(sent);
      sent += ring.write(&value, 1);
    }
  });
  std::thread consumer([&] {
    started.fetch_add(1);
    while (started.load() < 2) {
    }
    int got = 0;
    while (got < kCount) {
      float value = -1.f;
      if (ring.read(&value, 1) == 1) {
        if (value != static_cast<float>(got)) {
          expect(false, "ring thread delivered samples out of order");
          return;
        }
        got += 1;
      }
    }
  });
  producer.join();
  consumer.join();
}

void testLabels() {
  expect(std::string(backendLabel(BackendApi::AAudio, BackendPerformance::LowLatency)) == "AAudio Low-Latency",
         "AAudio label drifted");
  expect(std::string(backendLabel(BackendApi::OpenSLES, BackendPerformance::LowLatency)) == "OpenSL ES",
         "OpenSL label drifted");
  expect(latencyMilliseconds(480, 48000) == 10.0, "latency formula drifted");
  expect(latencyMilliseconds(0, 48000) == 0.0, "empty buffer should report 0 ms");
}

}  // namespace

int main() {
  testEchoImpulse();
  testGate();
  testLimiter();
  testRing();
  testRingThreads();
  testLabels();
  if (failures != 0) {
    std::cerr << failures << " native test(s) failed\n";
    return 1;
  }
  std::cout << "native dsp tests passed\n";
  return 0;
}
