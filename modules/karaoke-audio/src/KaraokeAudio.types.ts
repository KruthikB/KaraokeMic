export type RouteKind =
  | "bluetooth"
  | "bluetoothCall"
  | "wired"
  | "airplay"
  | "phone"
  | "unknown";

export type VocalConfig = {
  gain: number;
  reverb: number;
  echo: number;
  gate: boolean;
  /** Room algorithm: clean, studio, or hall. */
  preset: string;
};

export type KaraokeStatus = {
  running: boolean;
  muted: boolean;
  route: string;
  routeName: string;
  sampleRate: number;
  lowLatency: boolean;
};

export type LevelEvent = {
  rms: number;
  peak: number;
};

export type RouteEvent = {
  kind: string;
  name: string;
};

export type StateEvent = {
  running: boolean;
  muted: boolean;
};

export type ErrorEvent = {
  message: string;
};

export type KaraokeAudioModuleEvents = {
  onLevel: (event: LevelEvent) => void;
  onRoute: (event: RouteEvent) => void;
  onState: (event: StateEvent) => void;
  onError: (event: ErrorEvent) => void;
};

export type KaraokeNative = {
  getPermission(): string;
  getStatus(): KaraokeStatus;
  requestPermission(): Promise<boolean>;
  start(config: VocalConfig): Promise<void>;
  stop(): Promise<void>;
  setMuted(muted: boolean): void;
  setGain(gain: number): void;
  setReverb(mix: number): void;
  setEcho(mix: number): void;
  setGate(enabled: boolean): void;
  setPreset(name: string): void;
  addListener<EventName extends keyof KaraokeAudioModuleEvents>(
    eventName: EventName,
    listener: KaraokeAudioModuleEvents[EventName],
  ): { remove(): void };
};
