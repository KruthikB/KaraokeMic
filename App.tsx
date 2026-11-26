import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";

import { StageScreen } from "./src/screens/StageScreen";

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <StageScreen />
    </SafeAreaProvider>
  );
}
