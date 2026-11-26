import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { asRouteKind, routePresentation } from "./routeCopy";

describe("speaker banner", () => {
  it("warns that Bluetooth stereo is delayed", () => {
    const copy = routePresentation("bluetooth", "JBL Flip");
    assert.equal(copy.title, "JBL Flip");
    assert.equal(copy.tone, "warn");
    assert.match(copy.body, /stereo/i);
    assert.match(copy.body, /delay/i);
    assert.match(copy.body, /cable/i);
  });

  it("warns about howling on the phone speaker", () => {
    const copy = routePresentation("phone", "");
    assert.equal(copy.tone, "danger");
    assert.match(copy.body, /howl/i);
    assert.match(copy.body, /Bluetooth or wired/i);
  });

  it("treats a cable as the low-latency path", () => {
    const copy = routePresentation("wired", "USB-C DAC");
    assert.equal(copy.title, "USB-C DAC");
    assert.equal(copy.tone, "calm");
    assert.match(copy.body, /lowest-latency/i);
  });

  it("explains when Bluetooth has fallen back to call audio", () => {
    const copy = routePresentation("bluetoothCall", "Car kit");
    assert.equal(copy.tone, "danger");
    assert.match(copy.body, /mono/i);
    assert.match(copy.body, /stereo Bluetooth/i);
  });

  it("mentions AirPlay delay", () => {
    const copy = routePresentation("airplay", "Living Room");
    assert.match(copy.body, /delay/i);
    assert.match(copy.body, /cable/i);
  });

  it("rejects unknown route ids", () => {
    assert.equal(asRouteKind("carrier-pigeon"), "unknown");
    assert.equal(asRouteKind("wired"), "wired");
  });
});
