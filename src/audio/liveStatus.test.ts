import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { formatLiveStatus, formatMilliseconds } from "./liveStatus";

describe("live latency line", () => {
  it("names the Oboe backend and the measured latency", () => {
    const line = formatLiveStatus({
      platform: "android",
      mode: "direct",
      wifiRole: "",
      audioBackend: "AAudio Low-Latency",
      latencyMs: 8.2,
      sampleRate: 48000,
      lowLatency: true,
    });
    assert.match(line, /AAudio Low-Latency/);
    assert.match(line, /8\.2 ms latency/);
    assert.match(line, /48 kHz/);
    assert.match(line, /background/);
  });

  it("shows the AudioTrack fallback when Oboe did not open", () => {
    const line = formatLiveStatus({
      platform: "android",
      mode: "direct",
      wifiRole: "",
      audioBackend: "AudioTrack Fallback",
      latencyMs: 42,
      sampleRate: 48000,
      lowLatency: false,
    });
    assert.match(line, /AudioTrack Fallback/);
    assert.match(line, /42 ms latency/);
  });

  it("shows Wi-Fi sender latency, jitter, and the receiver address", () => {
    const line = formatLiveStatus({
      platform: "android",
      mode: "wifi",
      wifiRole: "sender",
      audioBackend: "AAudio Low-Latency",
      latencyMs: 0,
      sampleRate: 48000,
      lowLatency: true,
      wifi: {
        latencyMs: 18.4,
        jitterMs: 1.2,
        bufferMs: 15,
        localAddress: "192.168.1.20",
        remoteAddress: "192.168.1.23",
      },
    });
    assert.match(line, /Wi-Fi mic/);
    assert.match(line, /18 ms latency/);
    assert.match(line, /1.2 ms jitter/);
    assert.match(line, /192\.168\.1\.23/);
  });

  it("shows the receiver buffer and its own address", () => {
    const line = formatLiveStatus({
      platform: "android",
      mode: "wifi",
      wifiRole: "receiver",
      audioBackend: "AAudio Low-Latency",
      latencyMs: 20,
      sampleRate: 48000,
      lowLatency: true,
      wifi: {
        latencyMs: 20,
        jitterMs: 0.8,
        bufferMs: 14,
        localAddress: "192.168.1.23",
        remoteAddress: "",
      },
    });
    assert.match(line, /AAudio Low-Latency/);
    assert.match(line, /buffer 14 ms/);
    assert.match(line, /192\.168\.1\.23/);
  });

  it("hides a zero latency instead of printing 0 ms", () => {
    assert.equal(formatMilliseconds(0), "");
    assert.equal(formatMilliseconds(Number.NaN), "");
  });
});
