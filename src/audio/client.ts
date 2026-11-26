import { PermissionsAndroid, Platform } from "react-native";

import KaraokeAudio from "../../modules/karaoke-audio/src/KaraokeAudioModule";
import type {
  ErrorEvent,
  KaraokeStatus,
  LevelEvent,
  RouteEvent,
  StateEvent,
} from "../../modules/karaoke-audio/src/KaraokeAudio.types";
import type { VocalSettings } from "./session";
import { toNativeConfig } from "./session";
import { webMicEngine } from "./webEngine";

const EMPTY_STATUS: KaraokeStatus = {
  running: false,
  muted: false,
  route: "unknown",
  routeName: "",
  sampleRate: 0,
  lowLatency: false,
};

function onDevice(): boolean {
  return Platform.OS === "ios" || Platform.OS === "android";
}

function onWeb(): boolean {
  return Platform.OS === "web";
}

function emptySubscription() {
  return { remove() {} };
}

export function isNativePassthroughSupported(): boolean {
  return onDevice();
}

export function readPermission(): "granted" | "denied" | "undetermined" {
  if (onWeb()) {
    return "undetermined";
  }
  if (!onDevice()) {
    return "undetermined";
  }
  const value = KaraokeAudio.getPermission();
  if (value === "granted" || value === "denied") {
    return value;
  }
  return "undetermined";
}

export function readStatus(): KaraokeStatus {
  if (onWeb()) {
    return webMicEngine.status();
  }
  if (!onDevice()) {
    return EMPTY_STATUS;
  }
  return KaraokeAudio.getStatus();
}

export async function requestMicPermission(gate = true): Promise<boolean> {
  if (onWeb()) {
    return webMicEngine.ensurePermission(gate);
  }
  if (Platform.OS === "ios") {
    return KaraokeAudio.requestPermission();
  }
  if (Platform.OS === "android") {
    const mic = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
    if (typeof Platform.Version === "number" && Platform.Version >= 33) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    }
    if (typeof Platform.Version === "number" && Platform.Version >= 31) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
    }
    return mic === PermissionsAndroid.RESULTS.GRANTED;
  }
  return false;
}

export async function startPassthrough(settings: VocalSettings): Promise<void> {
  if (onWeb()) {
    await webMicEngine.start(settings);
    return;
  }
  if (!onDevice()) {
    throw new Error("StageMic cannot find a microphone on this platform.");
  }
  await KaraokeAudio.start(toNativeConfig(settings));
}

export async function stopPassthrough(): Promise<void> {
  if (onWeb()) {
    await webMicEngine.stop();
    return;
  }
  if (!onDevice()) {
    return;
  }
  await KaraokeAudio.stop();
}

export function pushGain(settings: VocalSettings): void {
  if (onWeb()) {
    webMicEngine.remember(settings);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setGain(settings.gain);
  }
}

export function pushReverb(settings: VocalSettings): void {
  if (onWeb()) {
    webMicEngine.remember(settings);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setReverb(settings.reverb);
  }
}

export function pushEcho(settings: VocalSettings): void {
  if (onWeb()) {
    webMicEngine.remember(settings);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setEcho(settings.echo);
  }
}

export function pushGate(settings: VocalSettings): void {
  if (onWeb()) {
    webMicEngine.remember(settings);
    void webMicEngine.setGate(settings.gate);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setGate(settings.gate);
  }
}

export function pushSpace(settings: VocalSettings): void {
  if (onWeb()) {
    webMicEngine.remember(settings);
    webMicEngine.setSpace(settings.space);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setPreset(settings.space);
  }
}

export function pushMuted(muted: boolean): void {
  if (onWeb()) {
    webMicEngine.setMuted(muted);
    return;
  }
  if (onDevice()) {
    KaraokeAudio.setMuted(muted);
  }
}

export function listenLevels(listener: (event: LevelEvent) => void) {
  if (onWeb()) {
    return webMicEngine.addLevelListener(listener);
  }
  if (!onDevice()) {
    return emptySubscription();
  }
  return KaraokeAudio.addListener("onLevel", listener);
}

export function listenRoute(listener: (event: RouteEvent) => void) {
  if (onWeb()) {
    return webMicEngine.addRouteListener(listener);
  }
  if (!onDevice()) {
    return emptySubscription();
  }
  return KaraokeAudio.addListener("onRoute", listener);
}

export function listenState(listener: (event: StateEvent) => void) {
  if (onWeb()) {
    return webMicEngine.addStateListener(listener);
  }
  if (!onDevice()) {
    return emptySubscription();
  }
  return KaraokeAudio.addListener("onState", listener);
}

export function listenErrors(listener: (event: ErrorEvent) => void) {
  if (onWeb()) {
    return webMicEngine.addErrorListener(listener);
  }
  if (!onDevice()) {
    return emptySubscription();
  }
  return KaraokeAudio.addListener("onError", listener);
}
