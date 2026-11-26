// Same envelope as src/audio/webGate.ts. The worklet cannot import that module.
const GATE_THRESHOLD = 0.02;

function coefficient(sampleRate, seconds) {
  const frames = Math.max(1, seconds * sampleRate);
  return 1 - Math.exp(-1 / frames);
}

class StageMicGateProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.envelope = 0;
    this.gain = 0;
    this.hold = 0;
    this.enabled = true;
    this.attackEnvelope = coefficient(sampleRate, 0.004);
    this.releaseEnvelope = coefficient(sampleRate, 0.13);
    this.attackGain = coefficient(sampleRate, 0.003);
    this.releaseGain = coefficient(sampleRate, 0.09);
    this.holdSamples = Math.max(1, Math.round(0.045 * sampleRate));
    this.port.onmessage = (event) => {
      if (event.data && typeof event.data.enabled === "boolean") {
        this.enabled = event.data.enabled;
      }
    };
  }

  process(inputs, outputs) {
    const input = inputs[0] && inputs[0][0];
    const output = outputs[0] && outputs[0][0];
    if (!output) {
      return true;
    }
    if (!input) {
      output.fill(0);
      return true;
    }
    for (let index = 0; index < output.length; index += 1) {
      const sample = index < input.length ? input[index] : 0;
      if (!this.enabled) {
        this.gain = 1;
        output[index] = sample;
        continue;
      }
      const level = Math.abs(sample);
      const envelopeCoefficient = level > this.envelope ? this.attackEnvelope : this.releaseEnvelope;
      this.envelope += (level - this.envelope) * envelopeCoefficient;
      if (this.envelope >= GATE_THRESHOLD) {
        this.hold = this.holdSamples;
      } else if (this.hold > 0) {
        this.hold -= 1;
      }
      const target = this.envelope >= GATE_THRESHOLD || this.hold > 0 ? 1 : 0;
      const gainCoefficient = target > this.gain ? this.attackGain : this.releaseGain;
      this.gain += (target - this.gain) * gainCoefficient;
      output[index] = sample * this.gain;
    }
    return true;
  }
}

registerProcessor("stagemic-gate", StageMicGateProcessor);
