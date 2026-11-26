import { StyleSheet, View } from "react-native";

import { theme } from "../theme";

const SEGMENTS = 18;

type LevelMeterProps = {
  level: number;
};

export function LevelMeter({ level }: LevelMeterProps) {
  const lit = Math.round(Math.min(1, Math.max(0, level)) * SEGMENTS);
  return (
    <View
      accessibilityLabel="Microphone input level"
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: Math.round(level * 100) }}
      style={styles.row}
    >
      {Array.from({ length: SEGMENTS }, (_, index) => {
        const on = index < lit;
        const color = segmentColor(index, on);
        return <View key={index} style={[styles.segment, { backgroundColor: color }]} />;
      })}
    </View>
  );
}

function segmentColor(index: number, on: boolean): string {
  if (!on) {
    return "#4A4036";
  }
  if (index >= 15) {
    return theme.live;
  }
  if (index >= 11) {
    return theme.amber;
  }
  return "#7DCEA0";
}

const styles = StyleSheet.create({
  row: {
    alignSelf: "stretch",
    width: "100%",
    flexDirection: "row",
    gap: 4,
    height: 22,
    alignItems: "flex-end",
  },
  segment: {
    flexGrow: 1,
    flexShrink: 1,
    flexBasis: 0,
    minWidth: 8,
    height: 16,
    borderRadius: 3,
  },
});
