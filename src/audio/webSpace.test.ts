import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { impulseSample, spaceShape } from "./webSpace";

describe("browser reverb shape", () => {
  it("makes the hall longer than the clean voice", () => {
    const clean = spaceShape("clean", true);
    const hall = spaceShape("hall", true);
    assert.ok(hall.seconds > clean.seconds);
    assert.ok(hall.delaySeconds > clean.delaySeconds);
    assert.equal(clean.highpassHz, 140);
    assert.equal(spaceShape("studio", false).highpassHz, 90);
  });

  it("fades the impulse and stays finite", () => {
    const start = Math.abs(impulseSample(0, 1000, 2, 0.8, 1));
    const end = Math.abs(impulseSample(999, 1000, 2, 0.8, 1));
    assert.ok(start > end);
    assert.ok(Number.isFinite(impulseSample(10, 100, 1.3, 0.5, -0.4)));
    assert.equal(impulseSample(0, 1, 2, 1, 1), 0);
  });
});
