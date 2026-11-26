import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import { INSTALL_FALLBACK, isStandaloneDisplay } from "./install";
import { appDirectoryHref, appFileUrl, pagesBasePath } from "./paths";

describe("install from Chrome", () => {
  it("describes the manifest Chrome needs", () => {
    const manifest = JSON.parse(readFileSync("public/manifest.webmanifest", "utf8")) as {
      name: string;
      short_name: string;
      display: string;
      background_color: string;
      theme_color: string;
      start_url: string;
      scope: string;
      icons: { sizes: string; src: string; type: string }[];
    };
    assert.equal(manifest.name, "StageMic");
    assert.equal(manifest.short_name, "StageMic");
    assert.equal(manifest.display, "standalone");
    assert.equal(manifest.background_color, "#0C0B09");
    assert.equal(manifest.theme_color, "#0C0B09");
    const sizes = manifest.icons.map((icon) => icon.sizes);
    assert.ok(sizes.includes("192x192"));
    assert.ok(sizes.includes("512x512"));
    assert.equal(manifest.start_url, "./");
    assert.equal(manifest.scope, "./");
    assert.ok(manifest.icons.every((icon) => icon.type === "image/png" && icon.src.startsWith("icons/")));
  });

  it("ships a service worker that caches the app shell", () => {
    const worker = readFileSync("public/sw.js", "utf8");
    assert.match(worker, /caches\.open/);
    assert.match(worker, /manifest\.webmanifest/);
    assert.match(worker, /addEventListener\("fetch"/);
    assert.match(worker, /self\.registration\.scope/);
    assert.match(worker, /icon-192\.png/);
    assert.match(worker, /icon-512\.png/);
  });

  it("explains the menu when Chrome does not show the install button", () => {
    assert.match(INSTALL_FALLBACK, /Install StageMic/);
    assert.match(INSTALL_FALLBACK, /Add to Home Screen/);
    assert.equal(isStandaloneDisplay(true, false), true);
    assert.equal(isStandaloneDisplay(false, false), false);
  });

  it("resolves files at the site root and under /KaraokeMic/", () => {
    assert.equal(pagesBasePath(undefined), undefined);
    assert.equal(pagesBasePath("/"), undefined);
    assert.equal(pagesBasePath("/KaraokeMic/"), "/KaraokeMic");
    assert.equal(
      appFileUrl("sw.js", "http://127.0.0.1:43123/"),
      "http://127.0.0.1:43123/sw.js",
    );
    assert.equal(
      appFileUrl("gate-processor.js", "https://kruthikb.github.io/KaraokeMic"),
      "https://kruthikb.github.io/KaraokeMic/gate-processor.js",
    );
    assert.equal(
      appFileUrl("manifest.webmanifest", "https://kruthikb.github.io/KaraokeMic/index.html"),
      "https://kruthikb.github.io/KaraokeMic/manifest.webmanifest",
    );
    assert.equal(appDirectoryHref("https://kruthikb.github.io/KaraokeMic/"), "https://kruthikb.github.io/KaraokeMic/");
  });
});
