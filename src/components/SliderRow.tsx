import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";

import { theme } from "../theme";

type SliderRowProps = {
  label: string;
  value: number;
  display: string;
  hint?: string;
  onChange: (value: number) => void;
};

export function SliderRow({ label, value, display, hint, onChange }: SliderRowProps) {
  const [width, setWidth] = useState(0);
  const position = Math.min(1, Math.max(0, value));

  function setFromX(locationX: number) {
    if (width <= 0) {
      return;
    }
    const next = Math.min(1, Math.max(0, locationX / width));
    onChange(next);
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.labelRow}>
        <Text style={styles.label}>{label}</Text>
        <Text style={styles.value}>{display}</Text>
      </View>
      <View
        accessibilityRole="adjustable"
        accessibilityLabel={label}
        accessibilityValue={{ min: 0, max: 100, now: Math.round(position * 100) }}
        accessibilityActions={[
          { name: "increment", label: "Increase" },
          { name: "decrement", label: "Decrease" },
        ]}
        onAccessibilityAction={(event) => {
          const step = event.nativeEvent.actionName === "increment" ? 0.05 : -0.05;
          onChange(Math.min(1, Math.max(0, position + step)));
        }}
        onLayout={(event) => setWidth(event.nativeEvent.layout.width)}
        onStartShouldSetResponder={() => true}
        onMoveShouldSetResponder={() => true}
        onResponderGrant={(event) => setFromX(event.nativeEvent.locationX)}
        onResponderMove={(event) => setFromX(event.nativeEvent.locationX)}
        style={styles.hit}
      >
        <View style={styles.track}>
          <View style={[styles.fill, { width: `${position * 100}%` }]} />
        </View>
        <View
          pointerEvents="none"
          style={[styles.thumb, { left: Math.max(0, position * width - 12) }]}
        />
      </View>
      {hint ? <Text style={styles.hint}>{hint}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 8,
  },
  labelRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "baseline",
  },
  label: {
    color: theme.text,
    fontSize: 16,
    fontWeight: "600",
  },
  value: {
    color: theme.amber,
    fontSize: 15,
    fontVariant: ["tabular-nums"],
    fontWeight: "700",
  },
  hit: {
    height: 36,
    justifyContent: "center",
  },
  track: {
    height: 6,
    borderRadius: 3,
    backgroundColor: "#2A241C",
    overflow: "hidden",
  },
  fill: {
    height: "100%",
    backgroundColor: theme.amber,
  },
  thumb: {
    position: "absolute",
    top: 6,
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: theme.text,
    borderWidth: 3,
    borderColor: theme.amber,
  },
  hint: {
    color: theme.faint,
    fontSize: 13,
    lineHeight: 18,
  },
});
