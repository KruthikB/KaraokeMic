import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { classifyWebOutputs } from "./webRoute";

describe("browser speaker labels", () => {
  it("recognizes bluetooth, cables, and the built-in speaker", () => {
    assert.equal(classifyWebOutputs(["JBL Flip Bluetooth"]).kind, "bluetooth");
    assert.equal(classifyWebOutputs(["USB Headset"]).kind, "wired");
    assert.equal(classifyWebOutputs(["MacBook Pro Speakers"]).kind, "phone");
    assert.equal(classifyWebOutputs([]).kind, "unknown");
  });
});
