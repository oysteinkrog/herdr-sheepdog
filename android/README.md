# Sheepdog for Android

The Android app for sheepdog: a voice call to your computer's sheepdog that
keeps running with the screen off, at full media volume, with the assistant's
questions on screen.

## What it does

- **Pairing.** Scan the QR code from `sheepdog pair` (or the setup page), or
  paste the pairing link. The app makes a P-256 key in the Android Keystore,
  enrolls it as device kind `android` and signs in like the iPhone app
  (docs/dev/ws-contract.md "Pairing" and "Auth"). `sheepdog://pair#…` links
  open the app too.
- **Calls.** `/ws` with the same handshake, mute, cancel, playback reports and
  pings as the web page. The call runs in a foreground service with a wake lock
  and a Wi-Fi lock, so it carries on with the screen off. The notification has
  Mute and Hang up.
- **Speaker modes** (Settings):
  - *Loud, talk over it* (default): media volume, and the app's own echo
    canceller (`EchoCanceller.kt`, a port of `web/voice/echo.js`) removes the
    assistant from the mic, so you can interrupt by talking.
  - *Loud, mic pauses while it talks*: media volume, the mic sends silence
    while the voice plays.
  - *Call audio*: the phone's own call echo cancelling on the speakerphone.
    Quieter.
- **Questions.** Open questions from `/api/state`, refreshed on `/events`:
  one tap answers a single-choice question; a multi-select question has tick
  boxes, Submit and Dismiss.
- Typing to the assistant, New conversation, volume boost, text size, keep the
  screen on.

## Build

Needs JDK 21 and the Android SDK (platform 36, build-tools 36.1).

```sh
echo "sdk.dir=$HOME/.local/share/android-sdk" > local.properties
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

A release build is signed when `SHEEPDOG_SIGNING` names a properties file
(`storeFile`, `keyAlias`, `storePassword`) outside the repository. Keep the
same key for every release, or Android refuses the update and the app must
be removed and paired again.

`EchoCancellerTest` runs the same four cases as `web/voice/echo.test.mjs` on
the same recordings in `testdata/audio`.
