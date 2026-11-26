import { appFileUrl } from "./paths";

export const INSTALL_FALLBACK =
  "In Chrome, open the menu and choose Install StageMic or Add to Home screen. On iPhone Safari, tap Share, then Add to Home Screen.";

export function isStandaloneDisplay(matchMediaStandalone: boolean, navigatorStandalone: boolean): boolean {
  return matchMediaStandalone || navigatorStandalone;
}

export function registerStageMicServiceWorker(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
    return;
  }
  const register = () => {
    void navigator.serviceWorker.register(appFileUrl("sw.js", window.location.href)).catch(() => undefined);
  };
  if (document.readyState === "complete") {
    register();
    return;
  }
  window.addEventListener("load", register, { once: true });
}
