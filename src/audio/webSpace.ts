import type { PresetId } from "./session";

export type SpaceShape = {
  seconds: number;
  decay: number;
  room: number;
  delaySeconds: number;
  echoFeedback: number;
  highpassHz: number;
};

export function spaceShape(space: PresetId, gate: boolean): SpaceShape {
  const highpassHz = gate ? 140 : 90;
  if (space === "studio") {
    return { seconds: 1.1, decay: 2, room: 0.55, delaySeconds: 0.16, echoFeedback: 0.28, highpassHz };
  }
  if (space === "hall") {
    return { seconds: 2.4, decay: 1.3, room: 0.8, delaySeconds: 0.28, echoFeedback: 0.36, highpassHz };
  }
  return { seconds: 0.45, decay: 3.2, room: 0.28, delaySeconds: 0.12, echoFeedback: 0.16, highpassHz };
}

/** One sample of a generated reverb impulse. `noise` is already in the range -1 to 1. */
export function impulseSample(index: number, length: number, decay: number, room: number, noise: number): number {
  if (length <= 1) {
    return 0;
  }
  const position = index / (length - 1);
  return noise * Math.pow(1 - position, decay) * room;
}
