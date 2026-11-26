import { StyleSheet, Text, View } from "react-native";

import type { RoutePresentation, RouteTone } from "../audio/routeCopy";
import { theme } from "../theme";

type RouteBannerProps = {
  presentation: RoutePresentation;
};

const tones: Record<RouteTone, { bg: string; ink: string; mark: string }> = {
  calm: { bg: "#14261C", ink: theme.good, mark: "#7DCEA0" },
  warn: { bg: "#2A2112", ink: theme.warn, mark: theme.amber },
  danger: { bg: "#2C1612", ink: theme.danger, mark: "#FF8A78" },
};

export function RouteBanner({ presentation }: RouteBannerProps) {
  const tone = tones[presentation.tone];
  return (
    <View style={[styles.banner, { backgroundColor: tone.bg, borderColor: tone.mark }]}>
      <View style={styles.kickerRow}>
        <View style={[styles.dot, { backgroundColor: tone.mark }]} />
        <Text style={[styles.kicker, { color: tone.ink }]}>{presentation.eyebrow}</Text>
      </View>
      <Text style={styles.title}>{presentation.title}</Text>
      <Text style={styles.body}>{presentation.body}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    borderWidth: 1,
    borderRadius: 18,
    paddingHorizontal: 16,
    paddingVertical: 14,
    gap: 6,
  },
  kickerRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  kicker: {
    fontSize: 12,
    letterSpacing: 1.1,
    textTransform: "uppercase",
    fontWeight: "700",
  },
  title: {
    color: theme.text,
    fontSize: 20,
    fontWeight: "700",
  },
  body: {
    color: theme.muted,
    fontSize: 14,
    lineHeight: 20,
  },
});
