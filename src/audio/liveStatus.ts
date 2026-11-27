export function formatMilliseconds(value: number): string {
  if (!Number.isFinite(value) || value <= 0) {
    return "";
  }
  if (value >= 10) {
    return `${Math.round(value)} ms`;
  }
  return `${value.toFixed(1)} ms`;
}

export function formatSampleRate(sampleRate: number): string {
  if (!Number.isFinite(sampleRate) || sampleRate <= 0) {
    return "";
  }
  const khz = sampleRate / 1000;
  const text = Number.isInteger(khz) ? khz.toFixed(0) : khz.toFixed(1);
  return `${text} kHz`;
}

export type LiveStatusInput = {
  platform: string;
  mode: string;
  wifiRole: string;
  audioBackend: string;
  latencyMs: number;
  sampleRate: number;
  lowLatency: boolean;
  wifi?: {
    latencyMs: number;
    jitterMs: number;
    bufferMs: number;
    localAddress: string;
    remoteAddress: string;
  } | null;
};

export function formatLiveStatus(input: LiveStatusInput): string {
  const background = input.platform === "web" ? "screen stays on" : "stays on in the background";
  if (input.mode === "wifi") {
    const latency = formatMilliseconds(input.wifi?.latencyMs || input.latencyMs);
    const jitter = formatMilliseconds(input.wifi?.jitterMs ?? 0);
    const buffer = formatMilliseconds(input.wifi?.bufferMs ?? 0);
    if (input.wifiRole === "sender") {
      const target = input.wifi?.remoteAddress
        ? `to ${input.wifi.remoteAddress}`
        : "looking for a receiver";
      return join(["Wi-Fi mic", latency && `${latency} latency`, jitter && `${jitter} jitter`, target, background]);
    }
    const where = input.wifi?.localAddress || "this phone";
    return join([
      input.audioBackend || "Speaker receiver",
      buffer && `buffer ${buffer}`,
      jitter && `${jitter} jitter`,
      latency && `${latency} latency`,
      where,
      background,
    ]);
  }
  const backend = input.audioBackend || (input.lowLatency ? "short buffer" : "speaker buffer");
  const latency = formatMilliseconds(input.latencyMs);
  return join([backend, latency && `${latency} latency`, formatSampleRate(input.sampleRate), background]);
}

function join(parts: (string | false)[]): string {
  return parts.filter((part): part is string => Boolean(part)).join(" · ");
}
