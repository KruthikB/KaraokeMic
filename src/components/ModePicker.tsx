import { Pressable, StyleSheet, Text, View } from "react-native";

import type { EngineMode } from "../../modules/karaoke-audio/src/KaraokeAudio.types";
import { theme } from "../theme";

const MODES: { id: EngineMode; label: string; detail: string }[] = [
  {
    id: "direct",
    label: "Direct Speaker",
    detail: "Mic and speaker on this phone",
  },
  {
    id: "wifi",
    label: "Wi-Fi Mic (2 Phones)",
    detail: "Handheld mic over local Wi-Fi",
  },
];

export function ModePicker({
  mode,
  onChange,
}: {
  mode: EngineMode;
  onChange: (mode: EngineMode) => void;
}) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.heading}>How the mic connects</Text>
      <View style={styles.row}>
        {MODES.map((item) => {
          const selected = mode === item.id;
          return (
            <Pressable
              key={item.id}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              accessibilityLabel={item.label}
              onPress={() => onChange(item.id)}
              style={[styles.chip, selected ? styles.chipOn : null]}
            >
              <Text style={[styles.label, selected ? styles.labelOn : null]}>{item.label}</Text>
              <Text style={[styles.detail, selected ? styles.detailOn : null]}>{item.detail}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 10,
  },
  heading: {
    color: theme.text,
    fontSize: 16,
    fontWeight: "700",
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
  detailOn: {
    color: theme.warn,
  },
});
