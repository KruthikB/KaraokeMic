export type RouteKind =
  | "bluetooth"
  | "bluetoothCall"
  | "wired"
  | "airplay"
  | "phone"
  | "wifi"
  | "unknown";

export type EngineMode = "direct" | "wifi";

export type WifiRole = "receiver" | "sender";

export type VocalConfig = {
  gain: number;
  reverb: number;
  echo: number;
  gate: boolean;
  /** Room algorithm: clean, studio, or hall. */
  preset: string;
  mode?: EngineMode;
  wifiRole?: WifiRole | "";
  wifiHost?: string;
  wifiPort?: number;
};

export type KaraokeStatus = {
  running: boolean;
  muted: boolean;
  route: string;
  routeName: string;
  sampleRate: number;
  lowLatency: boolean;
  audioBackend: string;
  latencyMs: number;
  mode: EngineMode | string;
  wifiRole: WifiRole | string;
};

export type WifiPeer = {
  name: string;
  host: string;
  port: number;
};

export type WifiLinkStats = {
  role: string;
  connected: boolean;
  advertising: boolean;
  localAddress: string;
  localPort: number;
  remoteAddress: string;
  latencyMs: number;
  jitterMs: number;
  bufferMs: number;
  packetsLost: number;
  packetsReceived: number;
  audioBackend: string;
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
  getWifiStats(): WifiLinkStats;
  getDiscoveredPeers(): WifiPeer[];
  getLocalAddress(): string;
  startDiscovery(): void;
  stopDiscovery(): void;
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
