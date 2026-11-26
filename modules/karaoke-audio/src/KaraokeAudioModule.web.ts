import { NativeModule, registerWebModule } from "expo";

import {
  KaraokeAudioModuleEvents,
  KaraokeStatus,
  VocalConfig,
  WifiLinkStats,
  WifiPeer,
} from "./KaraokeAudio.types";

const EMPTY_WIFI: WifiLinkStats = {
  role: "",
  connected: false,
  advertising: false,
  localAddress: "",
  localPort: 0,
  remoteAddress: "",
  latencyMs: 0,
  jitterMs: 0,
  bufferMs: 0,
  packetsLost: 0,
  packetsReceived: 0,
  audioBackend: "",
};

const WEB_MESSAGE =
  "StageMic's live microphone runs in the iOS and Android app. A browser cannot pass the mic through to a speaker.";

class KaraokeAudioWebModule extends NativeModule<KaraokeAudioModuleEvents> {
  getPermission(): string {
    return "undetermined";
  }

  getStatus(): KaraokeStatus {
    return {
      running: false,
      muted: false,
      route: "unknown",
      routeName: "",
      sampleRate: 0,
      lowLatency: false,
      audioBackend: "",
      latencyMs: 0,
      mode: "direct",
      wifiRole: "",
    };
  }

  getWifiStats(): WifiLinkStats {
    return EMPTY_WIFI;
  }

  getDiscoveredPeers(): WifiPeer[] {
    return [];
  }

  getLocalAddress(): string {
    return "";
  }

  startDiscovery(): void {}

  stopDiscovery(): void {}

  requestPermission(): Promise<boolean> {
    return Promise.resolve(false);
  }

  start(_config: VocalConfig): Promise<void> {
    return Promise.reject(new Error(WEB_MESSAGE));
  }

  stop(): Promise<void> {
    return Promise.resolve();
  }

  setMuted(_muted: boolean): void {}
  setGain(_gain: number): void {}
  setReverb(_mix: number): void {}
  setEcho(_mix: number): void {}
  setGate(_enabled: boolean): void {}
  setPreset(_name: string): void {}
}

export default registerWebModule(KaraokeAudioWebModule, "KaraokeAudio");
