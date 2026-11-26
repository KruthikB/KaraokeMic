import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import { GATE_THRESHOLD, createGateMemory, processGateSample } from "./webGate";

describe("browser noise gate", () => {
  it("closes on room noise and opens for a voice", () => {
    const memory = createGateMemory();
    let quiet = 1;
    for (let index = 0; index < 24_000; index += 1) {
      quiet = processGateSample(memory, 0.002, true, 48_000);
    }
    assert.ok(Math.abs(quiet) < 0.0002, `quiet sample was ${quiet}`);

    const loudMemory = createGateMemory();
    let loud = 0;
    for (let index = 0; index < 2_400; index += 1) {
      loud = processGateSample(loudMemory, 0.25, true, 48_000);
    }
    assert.ok(loud > 0.2, `loud sample was ${loud}`);
  });

  it("passes a quiet sample when the gate is off", () => {
    const memory = createGateMemory();
    assert.equal(processGateSample(memory, 0.002, false, 48_000), 0.002);
  });

  it("uses the same threshold as the audio worklet", () => {
    const source = readFileSync("public/gate-processor.js", "utf8");
    assert.match(source, new RegExp(`GATE_THRESHOLD = ${GATE_THRESHOLD}`));
    assert.match(source, /registerProcessor\("stagemic-gate"/);
  });
});
