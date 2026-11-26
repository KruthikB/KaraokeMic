import type { RouteKind } from "../../modules/karaoke-audio/src/KaraokeAudio.types";

export function classifyWebOutputs(labels: string[]): { kind: RouteKind; name: string } {
  const named = labels.map((label) => label.trim()).filter((label) => label.length > 0);
  const blob = named.join(" ");
  const bluetooth = named.find((label) => /bluetooth|airpods|buds|jbl|bose/i.test(label));
  if (bluetooth || /bluetooth|airpods|buds/i.test(blob)) {
    return { kind: "bluetooth", name: bluetooth || named[0] || "Bluetooth speaker" };
  }
  const airplay = named.find((label) => /airplay/i.test(label));
  if (airplay) {
    return { kind: "airplay", name: airplay };
  }
  const wired = named.find((label) => /headphone|headset|usb|aux|line out|earpod/i.test(label));
  if (wired) {
    return { kind: "wired", name: wired };
  }
  if (named.length === 0) {
    return { kind: "unknown", name: "" };
  }
  return { kind: "phone", name: named[0] || "This device's speaker" };
}
