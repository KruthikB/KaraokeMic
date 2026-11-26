import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { decayLevel, smoothLevel } from "./level";

describe("input meter", () => {
  it("rises quickly and does not pass 1", () => {
    const risen = smoothLevel(0, 0.8);
    assert.ok(risen > 0.4);
    assert.ok(smoothLevel(0.2, 10) <= 1);
  });

  it("falls more slowly than it rises", () => {
    const rise = smoothLevel(0.2, 0.8) - 0.2;
    const fall = 0.8 - smoothLevel(0.8, 0.1);
    assert.ok(rise > fall);
  });

  it("decays to silence", () => {
    let level = 0.9;
    for (let step = 0; step < 30; step += 1) {
      level = decayLevel(level);
    }
    assert.equal(level, 0);
  });

  it("ignores a bad reading", () => {
    assert.equal(smoothLevel(0.25, Number.NaN), smoothLevel(0.25, 0));
  });
});
