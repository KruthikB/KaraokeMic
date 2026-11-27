# StageMic

## Install from Chrome

The hosted app is [https://kruthikb.github.io/KaraokeMic/](https://kruthikb.github.io/KaraokeMic/).

1. Open that link in **Chrome** on a computer or Android phone.
2. Tap **Start** and allow the microphone. Your voice plays through the speaker Chrome is using.
3. Install it:
   - Tap **Install app** inside StageMic when Chrome offers it.
   - Or open Chrome's menu (⋮) and choose **Install StageMic** or **Add to Home screen**.
   - On iPhone Safari, tap Share, then **Add to Home Screen**.

Chrome only allows the microphone on **https://** or on **localhost**. The GitHub Pages address is https, so the mic works there. While StageMic is open on the screen, the phone stays awake and Spotify or YouTube can keep playing in the background. Locking the phone or leaving Chrome can stop the mic.

## Install the Android app from Chrome

Pushes to `main` build a debug APK and attach it to the rolling release [latest-android](https://github.com/KruthikB/KaraokeMic/releases/tag/latest-android). You do not need Android Studio or a signing key.

1. On the Android phone, open this link in **Chrome**: [stagemic-android.apk](https://github.com/KruthikB/KaraokeMic/releases/download/latest-android/stagemic-android.apk).
2. Download `stagemic-android.apk`. If Chrome warns you, choose to download it anyway.
3. Open the download. If Android asks, allow Chrome to install unknown apps, then tap Install.
4. Open StageMic. Allow the microphone and notifications.

The same file is also saved as a GitHub Actions artifact named `stagemic-android` on each build.

Pushes to `main` build this site. In the GitHub repo, set **Settings → Pages → Source** to **GitHub Actions** if the first deploy asks you to. The workflow uses the `/KaraokeMic` base path so the files load under that folder.

To try the same app on this computer, at the site root `/`:

```bash
npm install
npx expo export --platform web
npx serve dist -p 43123
```

Open `http://localhost:43123` in Chrome. Leave `PAGES_BASE_PATH` unset for this local build. The dev server (`npm start`, then `w`) is also at `http://localhost:43123`. The Android and iPhone apps, built with the commands further down, keep the mic running after you leave the screen.

StageMic turns your phone into a karaoke microphone.

You play music in Spotify, YouTube Music, Apple Music, YouTube, or another app. StageMic stays on in the background and sends your voice from the phone's microphone to the same speaker. The speaker can be Bluetooth, a wired AUX cable, or USB-C.

The music app keeps playing. StageMic does not pause it and does not turn it down.

## What you need

- A real Android phone or iPhone. Simulators do not behave like a mic and a speaker.
- A speaker: Bluetooth, a headphone-jack cable (AUX), or USB-C.
- Node.js 22 or newer on your computer.
- For Android: Android Studio, with a phone that has USB debugging turned on.
- For iPhone: a Mac with a current Xcode, and an Apple developer account if you want it on your own phone.

StageMic uses its own audio code. **Expo Go cannot run it.** The commands below build and install a real app.

## Install on Android

To install a build that is already compiled, use [Install the Android app from Chrome](#install-the-android-app-from-chrome). The steps below compile it on this computer and install it over USB.

1. In this folder, install the JavaScript packages:

   ```bash
   npm install
   ```

2. Plug in the phone, unlock it, and accept the USB debugging prompt.

3. Build and install:

   ```bash
   npm run android
   ```

   The first build takes a while. It compiles the Kotlin audio engine and installs StageMic.

4. Open StageMic. Allow the microphone, notifications, and nearby Bluetooth devices when Android asks.

5. Connect your speaker, tap **Start**, then switch to your music app and press play.

Android keeps a **StageMic** notification while the mic is on. Use **Mute** or **Stop** there if the phone is locked. Swiping the notification away is not how you stop it; tap **Stop**. The mic does not turn itself back on if Android kills the app.

## Install on iPhone

You need a Mac for this.

1. Install the packages:

   ```bash
   npm install
   ```

2. Build and install on the phone (or the simulator, which is only useful for looking at the screen):

   ```bash
   npm run ios
   ```

   You can also generate the Xcode project and open it yourself:

   ```bash
   npx expo prebuild --platform ios
   open ios/*.xcworkspace
   ```

   Pick your iPhone as the run destination, set your signing team, and press Run.

3. Allow the microphone when iOS asks.

4. Connect the speaker, tap **Start**, then open your music app.

While the mic is on, iOS shows an orange dot or an orange status bar. That means StageMic is still listening. It is supposed to stay on when you lock the screen or switch apps. Tap **Stop** in StageMic when you are done.

## Direct Speaker and Wi-Fi Mic

The Android app has a switch at the top of the screen.

**Direct Speaker** is one phone. The microphone and the speaker are the same device. StageMic opens a C++ Oboe stream in low-latency exclusive mode when the phone allows it, and falls back to the Kotlin `AudioTrack` engine if Oboe does not open. The screen shows the backend that is actually running (`AAudio Low-Latency`, `OpenSL ES`, or `AudioTrack Fallback`) and the latency in milliseconds, from the stream's buffer size. A cable is still the shortest path. Bluetooth stereo is still late, and StageMic does not switch it into phone-call mode.

**Wi-Fi Mic (2 Phones)** keeps Bluetooth out of the vocal path. Both phones join the same Wi-Fi. Latency on a normal home network is about 15–25 ms.

- **Speaker Receiver**, on the phone plugged into the speaker. Tap it, then Start. StageMic listens on UDP port **49152**, advertises itself as `StageMic` on the local network, and shows its Wi-Fi IP address in large type. Incoming 48 kHz mono frames sit in a jitter buffer of about 10–20 ms and play through the same low-latency output. Spotify or YouTube can keep playing on this phone. The screen shows connection, latency, jitter, and the buffer.
- **Handheld Mic**, on the phone you hold. Nearby receivers appear for a one-tap connect. If discovery does not find the other phone, type the IP address shown on the receiver. This phone captures the built-in mic, runs the noise gate, echo, reverb, and limiter, and sends small raw PCM frames. It keeps running from the StageMic notification after you leave the app.

The receiver does not need the microphone permission. The handheld phone does. Mute and Stop on the notification still work for both roles.

## How to sing with it

1. Connect the speaker first, so StageMic can show you which output it will use.
2. Tap the big mic button. Leave StageMic running.
3. Open Spotify, YouTube, Apple Music, or another player and start the song.
4. Sing. Move **Mic volume**, **Reverb**, and **Echo** while you sing. **Mute** cuts your voice without closing the mic. **Stop** closes the mic.
5. Presets: **Clean Voice**, **Studio Reverb**, and **Concert Hall**. Moving a slider makes the sound **Custom**. The room you picked stays until you pick another preset.

If the banner says **Phone speaker**, you are not on an external speaker yet.

## Feedback (howling)

Howling happens when the microphone hears the speaker.

- Use a Bluetooth or wired speaker. Do not monitor on the phone's own speaker.
- Put the phone away from the speaker, and don't point the mic at it.
- Turn the mic volume down.
- Leave **Noise gate / anti-feedback** on. The gate turns the mic down when you are quiet, which helps between phrases.

The gate cannot cancel a howl that is already louder than your voice. If it howls, turn the mic off, lower the volume, and move the phone.

## Bluetooth delay

Bluetooth stereo is late, often by 150–250 milliseconds. Your voice can feel behind the music. That delay is in the speaker, not something StageMic can remove while keeping stereo quality.

A wired AUX or USB-C cable is much closer to real time. Use a cable if the delay bothers you. The two-phone Wi-Fi mic also skips Bluetooth: the voice goes over Wi-Fi to the phone that is plugged into the speaker.

StageMic stays on stereo Bluetooth on purpose. The phone-call Bluetooth mode is sometimes a little faster, but it makes the music mono and muffled. StageMic does not switch the speaker into that mode. If the banner says **Bluetooth call audio**, disconnect and reconnect the speaker, or use a cable.

## If the music app pauses or gets quiet

StageMic does not take audio focus, and it does not duck other apps.

- Start the mic, then start the song. On iPhone, starting the microphone can interrupt music once. Press play again.
- On Android, end any phone call first. A call forces the speaker into call audio.
- If a music app has its own "pause when other audio starts" setting, turn that off.

## Checks

```bash
npm run typecheck
npm test
npm run test:dsp
npm run test:native
```

`npm run test:dsp` needs a JDK (17 or 21). The first run downloads Gradle. It tests the noise gate, echo, reverb, limiter, the speaker-routing rules, and the Wi-Fi packet and jitter buffer. It does not need a phone.

`npm run test:native` compiles the C++ DSP and lock-free ring on this computer and runs those tests. It does not need the Android SDK.

`npm run lint` checks the TypeScript.

## Project layout

- `src/` — the screen, the presets, and the speaker-banner text
- `modules/karaoke-audio/ios/` — Swift `AVAudioEngine` graph (gate, echo, reverb, mic fader)
- `modules/karaoke-audio/android/` — Oboe / AAudio low-latency engine, the AudioTrack fallback, the Wi-Fi mic, and the foreground service
- `modules/karaoke-audio/core/` — the vocal effects and routing rules, plus their tests

The `ios/` and `android/` folders at the repo root are generated by `expo prebuild` or `expo run`. They are not committed. Change native behavior in `app.json` or in `modules/karaoke-audio`, not by editing those generated folders by hand.

## Privacy

Audio is processed on the phone and played to the speaker. StageMic does not save a recording and does not send audio to a server. Mute silences the speaker but leaves the mic open. Stop is what releases the microphone.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Expo Go opens and the mic does nothing | Don't use Expo Go. Run `npm run android` or `npm run ios`. |
| Android says a foreground service is missing | Reinstall with `npm run android` so the microphone service is in the app. |
| No sound from the mic, music is fine | Check the mic permission in system settings. Tap Start again. On Android, confirm the StageMic notification is present. |
| You hear yourself twice | The phone speaker and the external speaker are both on. Pick the external speaker in the system output picker. |
| Voice is late on Bluetooth | Expected. Use a cable for less delay. |
| Howling | External speaker, more distance, less mic volume, gate on. |
| Music became quiet and mono | The speaker dropped into call mode. Reconnect it. StageMic will try to move back to stereo. |
| iPhone build fails signing | In Xcode, select your team under Signing & Capabilities. |
| Android build can't find the SDK | Install Android Studio, then open it once so it installs the SDK. |
