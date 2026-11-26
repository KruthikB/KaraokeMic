export const GATE_THRESHOLD = 0.02;

export type GateMemory = {
  envelope: number;
  gain: number;
  hold: number;
};

export function createGateMemory(): GateMemory {
  return { envelope: 0, gain: 0, hold: 0 };
}

function coefficient(sampleRate: number, seconds: number): number {
  const frames = Math.max(1, seconds * sampleRate);
  return 1 - Math.exp(-1 / frames);
}

/** One sample of the browser noise gate. Quiet room noise closes; a voice opens. */
export function processGateSample(
  memory: GateMemory,
  sample: number,
  enabled: boolean,
  sampleRate: number,
): number {
  if (!enabled) {
    memory.gain = 1;
    memory.envelope = Math.abs(sample);
    memory.hold = 0;
    return sample;
  }
  const attackEnvelope = coefficient(sampleRate, 0.004);
  const releaseEnvelope = coefficient(sampleRate, 0.13);
  const attackGain = coefficient(sampleRate, 0.003);
  const releaseGain = coefficient(sampleRate, 0.09);
  const holdSamples = Math.max(1, Math.round(0.045 * sampleRate));
  const level = Math.abs(sample);
  const envelopeCoefficient = level > memory.envelope ? attackEnvelope : releaseEnvelope;
  memory.envelope += (level - memory.envelope) * envelopeCoefficient;
  if (memory.envelope >= GATE_THRESHOLD) {
    memory.hold = holdSamples;
  } else if (memory.hold > 0) {
    memory.hold -= 1;
  }
  const target = memory.envelope >= GATE_THRESHOLD || memory.hold > 0 ? 1 : 0;
  const gainCoefficient = target > memory.gain ? attackGain : releaseGain;
  memory.gain += (target - memory.gain) * gainCoefficient;
  return sample * memory.gain;
}
