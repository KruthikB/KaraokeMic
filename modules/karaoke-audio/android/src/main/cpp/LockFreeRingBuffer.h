#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

/**
 * Single-producer, single-consumer float queue.
 * The mic callback writes. The speaker callback reads and may discard stale
 * frames so a stall cannot pile up into audible delay.
 * Indices are monotonic. Capacity is a power of two.
 */
class LockFreeRingBuffer {
 public:
  explicit LockFreeRingBuffer(int minimumCapacity)
      : capacity_(nextPowerOfTwo(minimumCapacity)),
        mask_(capacity_ - 1),
        buffer_(static_cast<size_t>(capacity_)) {}

  int capacity() const { return capacity_; }

  int available() const {
    const uint32_t write = write_.load(std::memory_order_acquire);
    const uint32_t read = read_.load(std::memory_order_relaxed);
    return static_cast<int>(write - read);
  }

  int write(const float* data, int frames) {
    const uint32_t write = write_.load(std::memory_order_relaxed);
    const uint32_t read = read_.load(std::memory_order_acquire);
    const int space = capacity_ - static_cast<int>(write - read);
    const int count = frames < space ? frames : space;
    for (int index = 0; index < count; ++index) {
      buffer_[(write + static_cast<uint32_t>(index)) & mask_] = data[index];
    }
    write_.store(write + static_cast<uint32_t>(count), std::memory_order_release);
    return count;
  }

  int read(float* data, int frames) {
    const uint32_t read = read_.load(std::memory_order_relaxed);
    const uint32_t write = write_.load(std::memory_order_acquire);
    const int ready = static_cast<int>(write - read);
    const int count = frames < ready ? frames : ready;
    for (int index = 0; index < count; ++index) {
      data[index] = buffer_[(read + static_cast<uint32_t>(index)) & mask_];
    }
    read_.store(read + static_cast<uint32_t>(count), std::memory_order_release);
    return count;
  }

  /** Consumer only. Drops the oldest frames so live monitoring stays current. */
  void discard(int frames) {
    const uint32_t read = read_.load(std::memory_order_relaxed);
    const uint32_t write = write_.load(std::memory_order_acquire);
    const int ready = static_cast<int>(write - read);
    const int count = frames < ready ? frames : ready;
    read_.store(read + static_cast<uint32_t>(count), std::memory_order_release);
  }

 private:
  static int nextPowerOfTwo(int value) {
    int capacity = 2;
    int needed = value < 2 ? 2 : value;
    while (capacity < needed) {
      capacity <<= 1;
    }
    return capacity;
  }

  int capacity_;
  uint32_t mask_;
  std::vector<float> buffer_;
  std::atomic<uint32_t> write_{0};
  std::atomic<uint32_t> read_{0};
};
