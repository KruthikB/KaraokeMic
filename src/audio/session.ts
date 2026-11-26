import type { VocalConfig } from "../../modules/karaoke-audio/src/KaraokeAudio.types";

export const GAIN_MAX = 1.5;

export type PresetId = "clean" | "studio" | "hall";

export type VocalSettings = {
  gain: number;
  reverb: number;
  echo: number;
  gate: boolean;
  /** Which room the native engine uses. Stays put when a slider moves. */
  space: PresetId;
  /** Which chip is lit. "custom" when the sliders leave a preset. */
  preset: PresetId | "custom";
};

export const PRESETS = [
  {
    id: "clean",
    label: "Clean Voice",
    detail: "Tight and dry",
    gain: 0.85,
    reverb: 0.06,
    echo: 0,
    gate: true,
  },
  {
    id: "studio",
    label: "Studio Reverb",
    detail: "Short room",
    gain: 0.85,
    reverb: 0.32,
    echo: 0.12,
    gate: true,
  },
  {
    id: "hall",
    label: "Concert Hall",
    detail: "Long and wide",
    gain: 0.9,
    reverb: 0.62,
    echo: 0.24,
    gate: true,
  },
] as const satisfies readonly {
  id: PresetId;
  label: string;
  detail: string;
  gain: number;
  reverb: number;
  echo: number;
  gate: boolean;
}[];

const MATCH_EPSILON = 0.011;

export function clampUnit(value: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.min(1, Math.max(0, value));
}

export function clampGain(value: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.min(GAIN_MAX, Math.max(0, value));
}

export function applyPreset(id: PresetId): VocalSettings {
  const preset = PRESETS.find((item) => item.id === id) ?? PRESETS[0];
  return {
    gain: preset.gain,
    reverb: preset.reverb,
    echo: preset.echo,
    gate: preset.gate,
    space: preset.id,
    preset: preset.id,
  };
}

export function matchPreset(settings: VocalSettings): PresetId | "custom" {
  for (const preset of PRESETS) {
    const sameSpace = preset.id === settings.space;
    const sameLevels =
      Math.abs(preset.gain - settings.gain) < MATCH_EPSILON &&
      Math.abs(preset.reverb - settings.reverb) < MATCH_EPSILON &&
      Math.abs(preset.echo - settings.echo) < MATCH_EPSILON &&
      preset.gate === settings.gate;
    if (sameSpace && sameLevels) {
      return preset.id;
    }
  }
  return "custom";
}

export function applySlider(
  current: VocalSettings,
  key: "gain" | "reverb" | "echo",
  value: number,
): VocalSettings {
  const next: VocalSettings = {
    ...current,
    [key]: key === "gain" ? clampGain(value) : clampUnit(value),
  };
  next.preset = matchPreset(next);
  return next;
}

export function applyGate(current: VocalSettings, gate: boolean): VocalSettings {
  const next: VocalSettings = { ...current, gate };
  next.preset = matchPreset(next);
  return next;
}

export function toNativeConfig(settings: VocalSettings): VocalConfig {
  return {
    gain: clampGain(settings.gain),
    reverb: clampUnit(settings.reverb),
    echo: clampUnit(settings.echo),
    gate: settings.gate,
    preset: settings.space,
  };
}

export function messageFrom(error: unknown): string {
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message;
  }
  return "The microphone did not start.";
}
