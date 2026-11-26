import type { RouteKind } from "../../modules/karaoke-audio/src/KaraokeAudio.types";

export type RouteTone = "calm" | "warn" | "danger";

export type RoutePresentation = {
  eyebrow: string;
  title: string;
  body: string;
  tone: RouteTone;
};

const ROUTE_KINDS: readonly RouteKind[] = [
  "bluetooth",
  "bluetoothCall",
  "wired",
  "airplay",
  "phone",
  "unknown",
];

export function asRouteKind(value: string): RouteKind {
  if (ROUTE_KINDS.includes(value as RouteKind)) {
    return value as RouteKind;
  }
  return "unknown";
}

export function routePresentation(kind: RouteKind, deviceName: string): RoutePresentation {
  const name = deviceName.trim();
  switch (kind) {
    case "bluetooth":
      return {
        eyebrow: "Bluetooth speaker",
        title: name || "Bluetooth speaker",
        body: "Music stays in stereo. Bluetooth adds a delay, often 150–250 ms, so your voice can feel late. A wired AUX or USB-C cable is closer to real time.",
        tone: "warn",
      };
    case "bluetoothCall":
      return {
        eyebrow: "Bluetooth call audio",
        title: name || "Bluetooth headset",
        body: "This connection switched to phone-call audio: mono and muffled. StageMic keeps the phone mic and asks for stereo Bluetooth again. If it stays stuck, reconnect the speaker or use a cable.",
        tone: "danger",
      };
    case "wired":
      return {
        eyebrow: "Wired speaker",
        title: name || "Cable connected",
        body: "A cable is the lowest-latency path. Your voice and the music leave through the same wire.",
        tone: "calm",
      };
    case "airplay":
      return {
        eyebrow: "AirPlay",
        title: name || "AirPlay speaker",
        body: "AirPlay adds delay, often more than Bluetooth. A cable is closer to real time.",
        tone: "warn",
      };
    case "phone":
      return {
        eyebrow: "Phone speaker",
        title: "Phone speaker",
        body: "The mic can hear this speaker and start to howl. Connect a Bluetooth or wired speaker, lower the mic volume, and keep the phone away from the speaker.",
        tone: "danger",
      };
    default:
      return {
        eyebrow: "Speaker",
        title: name || "Waiting for a speaker",
        body: "Connect Bluetooth or plug in AUX / USB-C. StageMic sends your voice to whatever speaker the phone is using.",
        tone: "warn",
      };
  }
}
