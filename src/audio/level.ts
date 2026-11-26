/** Ballistics for the on-screen meter. Attack is quick, release is slower. */
export function smoothLevel(previous: number, rms: number): number {
  const safeRms = Number.isFinite(rms) ? rms : 0;
  const target = Math.min(1, Math.max(0, safeRms * 2.6));
  const coefficient = target > previous ? 0.5 : 0.18;
  return previous + (target - previous) * coefficient;
}

export function decayLevel(previous: number): number {
  const next = previous * 0.72;
  return next < 0.01 ? 0 : next;
}
