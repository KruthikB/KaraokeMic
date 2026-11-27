import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  GAIN_MAX,
  applyGate,
  applyPreset,
  applySlider,
  clampGain,
  clampUnit,
  toNativeConfig,
} from "./session";

describe("vocal settings", () => {
  it("starts each preset from that preset's levels and room", () => {
    const hall = applyPreset("hall");
    assert.equal(hall.preset, "hall");
    assert.equal(hall.space, "hall");
    assert.equal(hall.reverb, 0.62);
    assert.equal(hall.echo, 0.24);
    assert.equal(hall.gate, true);
  });

  it("marks the sound custom when a slider leaves the preset, and keeps the room", () => {
    const next = applySlider(applyPreset("studio"), "reverb", 0.5);
    assert.equal(next.preset, "custom");
    assert.equal(next.space, "studio");
    assert.equal(next.reverb, 0.5);
  });

  it("lights the preset chip again when the sliders match it", () => {
    const studio = applyPreset("studio");
    const drifted = applySlider(studio, "echo", 0.4);
    const restored = applySlider(drifted, "echo", studio.echo);
    assert.equal(restored.preset, "studio");
  });

  it("treats the noise gate as part of the preset", () => {
    const next = applyGate(applyPreset("clean"), false);
    assert.equal(next.preset, "custom");
    assert.equal(next.gate, false);
    assert.equal(next.space, "clean");
  });

  it("sends the room id to native code, not the custom chip state", () => {
    const custom = applySlider(applyPreset("hall"), "gain", 1.1);
    const config = toNativeConfig(custom);
    assert.equal(config.preset, "hall");
    assert.equal(config.gain, 1.1);
    assert.equal(config.gate, true);
  });

  it("clamps gain and wet mixes into the range the engines accept", () => {
    assert.equal(clampGain(4), GAIN_MAX);
    assert.equal(clampGain(-1), 0);
    assert.equal(clampGain(Number.NaN), 0);
    assert.equal(clampUnit(2), 1);
    assert.equal(clampUnit(-0.2), 0);
    const clamped = toNativeConfig({
      ...applyPreset("clean"),
      gain: 9,
      reverb: 3,
      echo: -1,
    });
    assert.equal(clamped.gain, GAIN_MAX);
    assert.equal(clamped.reverb, 1);
    assert.equal(clamped.echo, 0);
    assert.equal(clamped.mode, "direct");
  });

  it("sends the Wi-Fi role and address with the vocal settings", () => {
    const config = toNativeConfig(applyPreset("clean"), {
      mode: "wifi",
      wifiRole: "sender",
      wifiHost: " 192.168.1.23 ",
      wifiPort: 49152,
    });
    assert.equal(config.mode, "wifi");
    assert.equal(config.wifiRole, "sender");
    assert.equal(config.wifiHost, "192.168.1.23");
    assert.equal(config.wifiPort, 49152);
    assert.equal(config.preset, "clean");
  });
});
