import { useEffect, useState } from "react";
import { Platform, Pressable, StyleSheet, Text, View } from "react-native";

import { INSTALL_FALLBACK, isStandaloneDisplay, registerStageMicServiceWorker } from "../pwa/install";
import { theme } from "../theme";

type InstallPrompt = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
};

function alreadyInstalled(): boolean {
  if (Platform.OS !== "web" || typeof window === "undefined") {
    return false;
  }
  const navigatorStandalone = (navigator as Navigator & { standalone?: boolean }).standalone === true;
  const mediaStandalone = window.matchMedia("(display-mode: standalone)").matches;
  return isStandaloneDisplay(mediaStandalone, navigatorStandalone);
}

export function InstallApp() {
  const [prompt, setPrompt] = useState<InstallPrompt | null>(null);
  const [installed, setInstalled] = useState(alreadyInstalled);

  useEffect(() => {
    if (Platform.OS !== "web" || typeof window === "undefined") {
      return;
    }
    registerStageMicServiceWorker();

    const onPrompt = (event: Event) => {
      event.preventDefault();
      setPrompt(event as InstallPrompt);
    };
    const onInstalled = () => {
      setInstalled(true);
      setPrompt(null);
    };
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onPrompt);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, []);

  if (Platform.OS !== "web" || installed) {
    return null;
  }

  return (
    <View style={styles.wrap}>
      {prompt ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Install StageMic"
          onPress={() => {
            void prompt.prompt();
          }}
          style={styles.button}
        >
          <Text style={styles.buttonText}>Install app</Text>
        </Pressable>
      ) : null}
      <Text style={styles.tip}>{INSTALL_FALLBACK}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 8,
  },
  button: {
    alignSelf: "flex-start",
    minHeight: 44,
    paddingHorizontal: 16,
    borderRadius: 999,
    backgroundColor: theme.amber,
    alignItems: "center",
    justifyContent: "center",
  },
  buttonText: {
    color: "#1A1208",
    fontSize: 16,
    fontWeight: "700",
  },
  tip: {
    color: theme.muted,
    fontSize: 13,
    lineHeight: 18,
  },
});
