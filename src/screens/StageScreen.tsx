import { useEffect, useRef, useState } from "react";
import {
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import {
  beginWifiDiscovery,
  endWifiDiscovery,
  listenErrors,
  listenLevels,
  listenRoute,
  listenState,
  pushEcho,
  pushGain,
  pushGate,
  pushMuted,
  pushReverb,
  pushSpace,
  readLocalAddress,
  readPermission,
  readStatus,
  readWifiPeers,
  readWifiStats,
  requestMicPermission,
  startPassthrough,
  stopPassthrough,
} from "../audio/client";
import { formatLiveStatus } from "../audio/liveStatus";
import type { EngineMode, WifiLinkStats, WifiPeer, WifiRole } from "../../modules/karaoke-audio/src/KaraokeAudio.types";
import { decayLevel, smoothLevel } from "../audio/level";
import { asRouteKind, routePresentation } from "../audio/routeCopy";
import {
  GAIN_MAX,
  PRESETS,
  applyGate,
  applyPreset,
  applySlider,
  messageFrom,
  type PresetId,
  type VocalSettings,
} from "../audio/session";
import { InstallApp } from "../components/InstallApp";
import { LevelMeter } from "../components/LevelMeter";
import { ModePicker } from "../components/ModePicker";
import { RouteBanner } from "../components/RouteBanner";
import { SliderRow } from "../components/SliderRow";
import { WifiPanel } from "../components/WifiPanel";
import { theme } from "../theme";

type Phase = "idle" | "starting" | "live" | "error";

const WEB_NOTE =
  "Chrome only opens the microphone on HTTPS or on localhost. On a phone, the mic stays live while StageMic is on screen. Spotify or YouTube can keep playing in the background. Leaving Chrome or locking the phone can stop the mic.";

export function StageScreen() {
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [phase, setPhase] = useState<Phase>("idle");
  const [permission, setPermission] = useState(readPermission);
  const [settings, setSettings] = useState<VocalSettings>(() => applyPreset("clean"));
  const [muted, setMuted] = useState(false);
  const [routeKind, setRouteKind] = useState(asRouteKind(readStatus().route));
  const [routeName, setRouteName] = useState(readStatus().routeName);
  const [level, setLevel] = useState(0);
  const [sampleRate, setSampleRate] = useState(0);
  const [lowLatency, setLowLatency] = useState(false);
  const [audioBackend, setAudioBackend] = useState("");
  const [latencyMs, setLatencyMs] = useState(0);
  const [engineMode, setEngineMode] = useState<EngineMode>("direct");
  const [wifiRole, setWifiRole] = useState<WifiRole>("receiver");
  const [peers, setPeers] = useState<WifiPeer[]>([]);
  const [selectedHost, setSelectedHost] = useState("");
  const [manualHost, setManualHost] = useState("");
  const [localAddress, setLocalAddress] = useState("");
  const [wifiStats, setWifiStats] = useState<WifiLinkStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const levelRef = useRef(0);

  useEffect(() => {
    const subscriptions = [
      listenLevels((event) => {
        levelRef.current = smoothLevel(levelRef.current, event.rms);
        setLevel(levelRef.current);
      }),
      listenRoute((event) => {
        setRouteKind(asRouteKind(event.kind));
        setRouteName(event.name);
      }),
      listenState((event) => {
        setMuted(event.muted);
        setPhase(event.running ? "live" : "idle");
        if (event.running) {
          setError(null);
        }
      }),
      listenErrors((event) => {
        setError(event.message);
      }),
    ];
    return () => {
      for (const subscription of subscriptions) {
        subscription.remove();
      }
    };
  }, []);

  useEffect(() => {
    if (Platform.OS !== "android" || engineMode !== "wifi" || wifiRole !== "sender") {
      return;
    }
    beginWifiDiscovery();
    const timer = setInterval(() => setPeers(readWifiPeers()), 1000);
    const kick = setTimeout(() => setPeers(readWifiPeers()), 0);
    return () => {
      clearInterval(timer);
      clearTimeout(kick);
      endWifiDiscovery();
    };
  }, [engineMode, wifiRole]);

  useEffect(() => {
    if (Platform.OS !== "android" || engineMode !== "wifi") {
      return;
    }
    const timer = setInterval(() => setLocalAddress(readLocalAddress()), 2000);
    const kick = setTimeout(() => setLocalAddress(readLocalAddress()), 0);
    return () => {
      clearInterval(timer);
      clearTimeout(kick);
    };
  }, [engineMode, wifiRole]);

  useEffect(() => {
    if (phase !== "live") {
      return;
    }
    const timer = setInterval(() => {
      const status = readStatus();
      if (!status.running) {
        setPhase("idle");
        return;
      }
      setSampleRate(status.sampleRate);
      setLowLatency(status.lowLatency);
      setAudioBackend(status.audioBackend ?? "");
      setLatencyMs(status.latencyMs ?? 0);
      if (Platform.OS === "android" && engineMode === "wifi") {
        setWifiStats(readWifiStats());
      }
    }, 500);
    return () => clearInterval(timer);
  }, [phase, engineMode]);

  useEffect(() => {
    if (phase === "live") {
      return;
    }
    const timer = setInterval(() => {
      levelRef.current = decayLevel(levelRef.current);
      setLevel(levelRef.current);
    }, 80);
    return () => clearInterval(timer);
  }, [phase]);

  async function onMicPress() {
    if (phase === "starting") {
      return;
    }
    if (phase === "live") {
      setPhase("idle");
      setMuted(false);
      try {
        await stopPassthrough();
      } catch (caught) {
        setError(messageFrom(caught));
        setPhase("error");
      }
      return;
    }
    setPhase("starting");
    setError(null);
    try {
      const needsMic = engineMode !== "wifi" || wifiRole !== "receiver";
      if (engineMode === "wifi" && Platform.OS !== "android") {
        setPhase("idle");
        setError("Two-phone Wi-Fi mic runs in the Android app. Use Direct Speaker here.");
        return;
      }
      const host = (selectedHost || manualHost).trim();
      if (engineMode === "wifi" && wifiRole === "sender" && host.length === 0) {
        setPhase("idle");
        setError("Tap a speaker phone, or enter its IP address.");
        return;
      }
      const allowed = await requestMicPermission(settings.gate, needsMic);
      setPermission(needsMic ? (allowed ? "granted" : "denied") : permission);
      if (needsMic && !allowed) {
        setPhase("idle");
        setError("StageMic needs the microphone so your voice can reach the speaker.");
        return;
      }
      await startPassthrough(settings, {
        mode: engineMode,
        wifiRole: engineMode === "wifi" ? wifiRole : "",
        wifiHost: engineMode === "wifi" && wifiRole === "sender" ? host : "",
        wifiPort: 49152,
      });
      const status = readStatus();
      setRouteKind(asRouteKind(status.route));
      setRouteName(status.routeName);
      setSampleRate(status.sampleRate);
      setLowLatency(status.lowLatency);
      setAudioBackend(status.audioBackend ?? "");
      setLatencyMs(status.latencyMs ?? 0);
      if (Platform.OS === "android" && engineMode === "wifi") {
        setWifiStats(readWifiStats());
      }
      setMuted(status.muted);
      setPhase("live");
    } catch (caught) {
      setError(messageFrom(caught));
      setPhase("error");
    }
  }

  function onGain(position: number) {
    const next = applySlider(settings, "gain", position * GAIN_MAX);
    setSettings(next);
    if (phase === "live") {
      pushGain(next);
    }
  }

  function onReverb(position: number) {
    const next = applySlider(settings, "reverb", position);
    setSettings(next);
    if (phase === "live") {
      pushReverb(next);
    }
  }

  function onEcho(position: number) {
    const next = applySlider(settings, "echo", position);
    setSettings(next);
    if (phase === "live") {
      pushEcho(next);
    }
  }

  function onGate(enabled: boolean) {
    const next = applyGate(settings, enabled);
    setSettings(next);
    if (phase === "live") {
      pushGate(next);
    }
  }

  function onPreset(id: PresetId) {
    const next = applyPreset(id);
    setSettings(next);
    if (phase === "live") {
      pushSpace(next);
      pushGain(next);
      pushReverb(next);
      pushEcho(next);
      pushGate(next);
    }
  }

  function onMute() {
    const next = !muted;
    setMuted(next);
    pushMuted(next);
  }

  async function chooseMode(next: EngineMode) {
    if (next === engineMode) {
      return;
    }
    if (phase === "live" || phase === "starting") {
      setPhase("idle");
      setMuted(false);
      setWifiStats(null);
      try {
        await stopPassthrough();
      } catch (caught) {
        setError(messageFrom(caught));
      }
    }
    setEngineMode(next);
  }

  const live = phase === "live";
  const presentation = routePresentation(routeKind, routeName);
  const micSize = Math.min(220, Math.max(168, width * 0.46));
  const statusLine = live
    ? formatLiveStatus({
        platform: Platform.OS,
        mode: engineMode,
        wifiRole,
        audioBackend,
        latencyMs: wifiStats?.latencyMs || latencyMs,
        sampleRate,
        lowLatency,
        wifi: wifiStats,
      })
    : "Off until you start the mic";
  const actionLabel =
    phase === "starting" ? "…" : live ? "Stop" : engineMode === "wifi" && wifiRole === "sender" ? "Send" : "Start";

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[
        styles.content,
        {
          paddingTop: Math.max(insets.top, 12) + 16,
          paddingBottom: Math.max(insets.bottom, 16) + 28,
        },
      ]}
      bounces
    >
      <View style={styles.sheet}>
        <View style={styles.header}>
          <View>
            <Text style={styles.brand}>StageMic</Text>
            <Text style={styles.tagline}>Your phone is the microphone.</Text>
          </View>
          <View style={[styles.pill, live ? styles.pillLive : styles.pillIdle]}>
            <View style={[styles.pillDot, { backgroundColor: live ? theme.liveSoft : theme.faint }]} />
            <Text style={[styles.pillText, live ? styles.pillTextLive : null]}>
              {live ? (muted ? "Muted" : "Live") : "Idle"}
            </Text>
          </View>
        </View>

        <Text style={styles.intro}>
          {Platform.OS === "web"
            ? "Play music in Spotify or YouTube, then sing into this page. Chrome sends your voice to the speaker this computer or phone is using."
            : "Play music in Spotify, YouTube, Apple Music, or another app. StageMic keeps your voice on the speaker at the same time, even if you lock the phone."}
        </Text>

        <InstallApp />

        {Platform.OS === "web" ? (
          <View style={styles.webNote}>
            <Text style={styles.webNoteText}>{WEB_NOTE}</Text>
          </View>
        ) : null}

        <ModePicker mode={engineMode} onChange={(next) => void chooseMode(next)} />

        {engineMode === "wifi" ? (
          <WifiPanel
            supported={Platform.OS === "android"}
            role={wifiRole}
            onRole={(next) => {
              if (phase === "live") {
                return;
              }
              setWifiRole(next);
              setWifiStats(null);
            }}
            localAddress={localAddress}
            peers={peers}
            selectedHost={selectedHost}
            onSelectHost={setSelectedHost}
            manualHost={manualHost}
            onManualHost={(value) => {
              setManualHost(value);
              setSelectedHost("");
            }}
            stats={wifiStats}
          />
        ) : null}

        <RouteBanner presentation={presentation} />

        <View style={styles.stage}>
          <Text style={styles.meterLabel}>Mic input</Text>
          <LevelMeter level={level} />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={live ? "Turn microphone off" : "Turn microphone on"}
            accessibilityState={{ busy: phase === "starting", disabled: phase === "starting" }}
            onPress={() => {
              void onMicPress();
            }}
            style={({ pressed }) => [
              styles.mic,
              {
                width: micSize,
                height: micSize,
                borderRadius: micSize / 2,
              },
              live ? styles.micLive : styles.micIdle,
              pressed && phase !== "starting" ? styles.micPressed : null,
            ]}
          >
            <Text style={[styles.micKicker, live ? styles.micKickerLive : null]}>
              {phase === "starting" ? "Starting" : live ? "Mic on" : "Mic off"}
            </Text>
            <Text style={[styles.micAction, live ? styles.micActionLive : null]}>{actionLabel}</Text>
          </Pressable>
          <Text style={styles.statusLine}>{statusLine}</Text>
          {live ? (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={muted ? "Unmute microphone" : "Mute microphone"}
              onPress={onMute}
              style={[styles.mute, muted ? styles.muteOn : null]}
            >
              <Text style={styles.muteText}>{muted ? "Unmute" : "Mute"}</Text>
            </Pressable>
          ) : null}
          {muted && live ? (
            <Text style={styles.privacy}>
              Muted on the speaker. The phone mic stays open until you tap Stop.
            </Text>
          ) : null}
          {error ? <Text style={styles.error}>{error}</Text> : null}
          {permission === "denied" && Platform.OS !== "web" ? (
            <Pressable
              accessibilityRole="button"
              onPress={() => {
                void Linking.openSettings();
              }}
              style={styles.settingsLink}
            >
              <Text style={styles.settingsLinkText}>Open Settings</Text>
            </Pressable>
          ) : null}
        </View>

        <View style={styles.controls}>
          <SliderRow
            label="Mic volume"
            value={settings.gain / GAIN_MAX}
            display={`${Math.round((settings.gain / GAIN_MAX) * 100)}`}
            hint="100 is as loud as this phone will safely go."
            onChange={onGain}
          />
          <SliderRow
            label="Reverb"
            value={settings.reverb}
            display={`${Math.round(settings.reverb * 100)}`}
            onChange={onReverb}
          />
          <SliderRow
            label="Echo"
            value={settings.echo}
            display={`${Math.round(settings.echo * 100)}`}
            onChange={onEcho}
          />
          <View style={styles.gateRow}>
            <View style={styles.gateCopy}>
              <Text style={styles.gateTitle}>Noise gate / anti-feedback</Text>
              <Text style={styles.gateBody}>
                Turns the mic down when you are not singing. It slows howling between phrases. It
                cannot stop a loud howl once it starts — use an external speaker and less volume.
              </Text>
            </View>
            <Switch
              accessibilityLabel="Noise gate and anti-feedback"
              value={settings.gate}
              onValueChange={onGate}
              trackColor={{ false: "#3A332B", true: theme.amberDeep }}
              thumbColor={settings.gate ? theme.amber : "#D9D0C5"}
            />
          </View>
        </View>

        <View style={styles.presets}>
          <View style={styles.presetHeading}>
            <Text style={styles.presetTitle}>Presets</Text>
            <Text style={styles.presetMode}>
              {settings.preset === "custom" ? "Custom" : "Preset"}
            </Text>
          </View>
          <View style={styles.presetRow}>
            {PRESETS.map((preset) => {
              const selected = settings.preset === preset.id;
              return (
                <Pressable
                  key={preset.id}
                  accessibilityRole="button"
                  accessibilityState={{ selected }}
                  onPress={() => onPreset(preset.id)}
                  style={[styles.chip, selected ? styles.chipOn : null]}
                >
                  <Text style={[styles.chipLabel, selected ? styles.chipLabelOn : null]}>
                    {preset.label}
                  </Text>
                  <Text style={[styles.chipDetail, selected ? styles.chipDetailOn : null]}>
                    {preset.detail}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>

        <Text style={styles.footer}>
          {Platform.OS === "android"
            ? "Android shows a StageMic notification with Mute and Stop. Leaving that on is what keeps the mic alive after you switch apps."
            : Platform.OS === "ios"
              ? "On iPhone, the status bar stays orange while the mic is open. That is the system showing StageMic is still listening."
              : "On a phone, keep this page open while you sing. Spotify or YouTube can keep playing in the background. Locking the phone can stop the mic."}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: theme.bg,
  },
  content: {
    paddingHorizontal: 20,
  },
  sheet: {
    width: "100%",
    maxWidth: 560,
    alignSelf: "center",
    gap: 22,
  },
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    gap: 12,
  },
  brand: {
    color: theme.text,
    fontSize: 34,
    fontWeight: "700",
    fontFamily: Platform.select({ ios: "Georgia", android: "serif", default: "serif" }),
    letterSpacing: -0.4,
  },
  tagline: {
    color: theme.muted,
    fontSize: 15,
    marginTop: 2,
  },
  pill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderWidth: 1,
  },
  pillIdle: {
    borderColor: theme.line,
    backgroundColor: theme.bgRaised,
  },
  pillLive: {
    borderColor: theme.live,
    backgroundColor: "#3A1510",
  },
  pillDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  pillText: {
    color: theme.muted,
    fontSize: 12,
    fontWeight: "700",
    letterSpacing: 0.6,
    textTransform: "uppercase",
  },
  pillTextLive: {
    color: theme.liveSoft,
  },
  intro: {
    color: theme.muted,
    fontSize: 15,
    lineHeight: 22,
  },
  webNote: {
    backgroundColor: "#241C10",
    borderRadius: 14,
    padding: 12,
    borderWidth: 1,
    borderColor: theme.amberDeep,
  },
  webNoteText: {
    color: theme.warn,
    fontSize: 14,
    lineHeight: 20,
  },
  stage: {
    alignItems: "center",
    gap: 14,
    paddingVertical: 8,
  },
  meterLabel: {
    alignSelf: "stretch",
    color: theme.faint,
    fontSize: 12,
    letterSpacing: 1.2,
    textTransform: "uppercase",
    fontWeight: "700",
  },
  mic: {
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 2,
    marginTop: 8,
  },
  micIdle: {
    backgroundColor: "#1A1612",
    borderColor: theme.amber,
  },
  micLive: {
    backgroundColor: theme.live,
    borderColor: "#FF8A78",
  },
  micPressed: {
    transform: [{ scale: 0.98 }],
    opacity: 0.92,
  },
  micKicker: {
    color: theme.amber,
    fontSize: 13,
    letterSpacing: 1.4,
    textTransform: "uppercase",
    fontWeight: "700",
  },
  micKickerLive: {
    color: "#FFD2C8",
  },
  micAction: {
    color: theme.text,
    fontSize: 42,
    fontWeight: "700",
    fontFamily: Platform.select({ ios: "Georgia", android: "serif", default: "serif" }),
    marginTop: 2,
  },
  micActionLive: {
    color: "#FFF7F4",
  },
  statusLine: {
    color: theme.faint,
    fontSize: 13,
    textAlign: "center",
  },
  mute: {
    minHeight: 44,
    paddingHorizontal: 22,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: theme.line,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: theme.bgRaised,
  },
  muteOn: {
    borderColor: theme.amber,
  },
  muteText: {
    color: theme.text,
    fontSize: 16,
    fontWeight: "700",
  },
  privacy: {
    color: theme.warn,
    fontSize: 13,
    lineHeight: 18,
    textAlign: "center",
    paddingHorizontal: 12,
  },
  error: {
    color: theme.danger,
    fontSize: 14,
    lineHeight: 20,
    textAlign: "center",
  },
  settingsLink: {
    minHeight: 44,
    justifyContent: "center",
  },
  settingsLinkText: {
    color: theme.amber,
    fontSize: 16,
    fontWeight: "700",
  },
  controls: {
    backgroundColor: theme.bgRaised,
    borderRadius: 20,
    padding: 16,
    gap: 18,
    borderWidth: 1,
    borderColor: theme.line,
  },
  gateRow: {
    flexDirection: "row",
    gap: 12,
    alignItems: "center",
  },
  gateCopy: {
    flex: 1,
    gap: 4,
  },
  gateTitle: {
    color: theme.text,
    fontSize: 16,
    fontWeight: "600",
  },
  gateBody: {
    color: theme.muted,
    fontSize: 13,
    lineHeight: 18,
  },
  presets: {
    gap: 10,
  },
  presetHeading: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "baseline",
  },
  presetTitle: {
    color: theme.text,
    fontSize: 16,
    fontWeight: "700",
  },
  presetMode: {
    color: theme.amber,
    fontSize: 13,
    fontWeight: "700",
  },
  presetRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
  },
  chip: {
    flexGrow: 1,
    flexBasis: "30%",
    minWidth: 140,
    borderRadius: 16,
    paddingHorizontal: 12,
    paddingVertical: 12,
    backgroundColor: theme.bgRaised,
    borderWidth: 1,
    borderColor: theme.line,
    gap: 2,
  },
  chipOn: {
    backgroundColor: "#2A2112",
    borderColor: theme.amber,
  },
  chipLabel: {
    color: theme.text,
    fontSize: 15,
    fontWeight: "700",
  },
  chipLabelOn: {
    color: theme.amber,
  },
  chipDetail: {
    color: theme.faint,
    fontSize: 12,
  },
  chipDetailOn: {
    color: theme.warn,
  },
  footer: {
    color: theme.faint,
    fontSize: 13,
    lineHeight: 19,
  },
});
