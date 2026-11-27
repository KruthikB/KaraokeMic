import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

import type { WifiLinkStats, WifiPeer, WifiRole } from "../../modules/karaoke-audio/src/KaraokeAudio.types";
import { theme } from "../theme";

const ROLES: { id: WifiRole; label: string; detail: string }[] = [
  {
    id: "receiver",
    label: "Speaker Receiver",
    detail: "Plugged into the speaker",
  },
  {
    id: "sender",
    label: "Handheld Mic",
    detail: "The phone you sing into",
  },
];

export function WifiPanel({
  supported,
  role,
  onRole,
  localAddress,
  peers,
  selectedHost,
  onSelectHost,
  manualHost,
  onManualHost,
  stats,
}: {
  supported: boolean;
  role: WifiRole;
  onRole: (role: WifiRole) => void;
  localAddress: string;
  peers: WifiPeer[];
  selectedHost: string;
  onSelectHost: (host: string) => void;
  manualHost: string;
  onManualHost: (host: string) => void;
  stats: WifiLinkStats | null;
}) {
  if (!supported) {
    return (
      <View style={styles.note}>
        <Text style={styles.noteText}>
          Two-phone Wi-Fi mic runs in the Android app. Install StageMic from GitHub Releases, then
          pick a speaker phone and a handheld phone on the same Wi-Fi. Direct Speaker still works
          here.
        </Text>
      </View>
    );
  }

  const port = stats?.localPort || 49152;
  const connected = stats?.connected === true;
  return (
    <View style={styles.wrap}>
      <View style={styles.row}>
        {ROLES.map((item) => {
          const selected = role === item.id;
          return (
            <Pressable
              key={item.id}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              accessibilityLabel={item.label}
              onPress={() => onRole(item.id)}
              style={[styles.chip, selected ? styles.chipOn : null]}
            >
              <Text style={[styles.label, selected ? styles.labelOn : null]}>{item.label}</Text>
              <Text style={styles.detail}>{item.detail}</Text>
            </Pressable>
          );
        })}
      </View>

      {role === "receiver" ? (
        <View style={styles.card}>
          <Text style={styles.kicker}>Wi-Fi address on this phone</Text>
          <Text style={styles.ip}>{localAddress || "Waiting for Wi-Fi"}</Text>
          <Text style={styles.body}>
            Port {port}. Plug this phone into the speaker, tap Start, and leave Spotify or YouTube
            playing here. The other phone finds this one automatically. If it does not, type this
            address on the handheld phone.
          </Text>
          <Text style={styles.body}>
            {connected
              ? "Handheld mic connected."
              : stats?.advertising
                ? "Advertising on this Wi-Fi. Waiting for a handheld mic."
                : "Tap Start to listen for a handheld mic."}
          </Text>
        </View>
      ) : (
        <View style={styles.card}>
          <Text style={styles.kicker}>Speaker phones on this Wi-Fi</Text>
          {peers.length === 0 ? (
            <Text style={styles.body}>
              Looking for a StageMic receiver. You can also type its IP address.
            </Text>
          ) : (
            peers.map((peer) => {
              const selected = selectedHost === peer.host;
              return (
                <Pressable
                  key={`${peer.host}:${peer.port}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected }}
                  onPress={() => onSelectHost(peer.host)}
                  style={[styles.peer, selected ? styles.chipOn : null]}
                >
                  <Text style={[styles.label, selected ? styles.labelOn : null]}>{peer.name}</Text>
                  <Text style={styles.detail}>
                    {peer.host}:{peer.port}
                  </Text>
                </Pressable>
              );
            })
          )}
          <Text style={styles.kicker}>Or enter the IP</Text>
          <TextInput
            value={manualHost}
            onChangeText={onManualHost}
            placeholder="192.168.1.23"
            placeholderTextColor={theme.faint}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="numbers-and-punctuation"
            accessibilityLabel="Speaker phone IP address"
            style={styles.input}
          />
        </View>
      )}

      {stats ? (
        <Text style={styles.stats}>
          {connected ? "Connected" : "Not connected yet"}
          {stats.latencyMs > 0 ? ` · ${Math.round(stats.latencyMs)} ms latency` : ""}
          {stats.jitterMs > 0 ? ` · ${stats.jitterMs.toFixed(1)} ms jitter` : ""}
          {stats.bufferMs > 0 ? ` · buffer ${Math.round(stats.bufferMs)} ms` : ""}
          {stats.packetsLost > 0 ? ` · ${Math.round(stats.packetsLost)} lost` : ""}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 10,
  },
  row: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
  },
  chip: {
    flexGrow: 1,
    flexBasis: "46%",
    minWidth: 150,
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
  label: {
    color: theme.text,
    fontSize: 15,
    fontWeight: "700",
  },
  labelOn: {
    color: theme.amber,
  },
  detail: {
    color: theme.faint,
    fontSize: 12,
    lineHeight: 16,
  },
  card: {
    backgroundColor: theme.bgRaised,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: theme.line,
    padding: 14,
    gap: 8,
  },
  kicker: {
    color: theme.faint,
    fontSize: 12,
    fontWeight: "700",
    letterSpacing: 0.8,
    textTransform: "uppercase",
  },
  ip: {
    color: theme.amber,
    fontSize: 28,
    fontWeight: "700",
  },
  body: {
    color: theme.muted,
    fontSize: 14,
    lineHeight: 20,
  },
  peer: {
    borderRadius: 12,
    borderWidth: 1,
    borderColor: theme.line,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 2,
  },
  input: {
    minHeight: 48,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: theme.line,
    paddingHorizontal: 12,
    color: theme.text,
    fontSize: 18,
    backgroundColor: theme.bgSunken,
  },
  stats: {
    color: theme.muted,
    fontSize: 13,
    lineHeight: 18,
  },
  note: {
    backgroundColor: "#241C10",
    borderRadius: 14,
    padding: 12,
    borderWidth: 1,
    borderColor: theme.amberDeep,
  },
  noteText: {
    color: theme.warn,
    fontSize: 14,
    lineHeight: 20,
  },
});
