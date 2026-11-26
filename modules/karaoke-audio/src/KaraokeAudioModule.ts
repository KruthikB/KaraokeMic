import { NativeModule, requireNativeModule } from "expo";

import { KaraokeAudioModuleEvents, KaraokeStatus, VocalConfig } from "./KaraokeAudio.types";

declare class KaraokeAudioModule extends NativeModule<KaraokeAudioModuleEvents> {
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
}

export default requireNativeModule<KaraokeAudioModule>("KaraokeAudio");
