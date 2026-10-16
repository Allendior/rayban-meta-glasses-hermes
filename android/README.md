# Hermes Glasses for Android

This companion app connects Meta Ray-Ban glasses to the private Hermes voice
bridge running on the Mac mini.

## What it does

1. Registers with the Meta AI app through Meta's Device Access Toolkit (DAT).
2. Requests the glasses microphone permission and starts on-device speech
   recognition. Only transcript text crosses from the glasses to the phone.
3. Sends each final transcript to Hermes over the Mac mini's Tailscale address.
4. Downloads the authenticated cloned-voice WAV and plays it through Android's
   current audio route. With the glasses selected for Bluetooth media, the
   response plays through the glasses.

The **Reply voice** selector has two modes:

- **Fast Android voice** asks Hermes for text only and uses warmed Android
  TextToSpeech over the active media route. If Android TTS is unavailable for
  the selected language, it automatically falls back to cloned speech.
- **Cloned voice** preserves the original bridge-generated WAV path. It sounds
  like the configured owner but starts later because synthesis must finish first.

Fast Android voice is not the glasses' private built-in TTS. Bluetooth routing
still depends on the phone's active media output and needs a physical test.

Typed questions are also supported. Use them to test Tailscale and the bridge
before completing Meta registration.

## Build

Requirements: JDK 17 and Android SDK 36.

```sh
cd android
./gradlew test assembleDebug
```

The debug APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`.

## Phone setup

1. Install Tailscale and sign into the same tailnet as the Mac mini.
2. Install the Meta AI app, pair the glasses, and enable Developer Mode for the
   glasses.
3. Install the debug APK with `./gradlew installDebug` or Android Studio.
4. In Hermes Glasses, set the bridge URL to your own Mac's Tailscale address
   and the port from its `.env` (`BRIDGE_PORT`, default `8788`), e.g.
   `http://100.x.y.z:8788`. Find your Mac's address by running
   `tailscale ip -4` on it. Enter the bridge API key from the Mac's `.env`
   (`BRIDGE_API_KEY`). The app encrypts this key with Android Keystore and does
   not include it in the APK or repository. There is no built-in default URL,
   so this field must be filled in on first run.
5. Tap **Test Mac bridge**, then **Connect Meta glasses**. Complete the Meta AI
   registration and microphone grant.
6. Choose **Fast Android voice** for lowest latency or **Cloned voice** for the
   configured owner voice. Tap **Start glasses listening**, speak, and wait for
   the reply.

For the separate experimental path that uses the glasses' actual built-in voice
through Visor, read [`../docs/VISOR_HERMES_FAST_PATH.md`](../docs/VISOR_HERMES_FAST_PATH.md).

For the milestone acceptance test, disable Wi-Fi before step 5. A successful
typed question proves the cellular/tailnet/bridge/audio path. A spoken question
then proves the glasses transcription path.

Developer builds use Meta's documented `0` application ID and client token.
Production credentials can be placed in ignored `android/local.properties`:

```properties
mwdat_application_id=your_application_id
mwdat_client_token=your_client_token
```

Meta currently marks Speech as experimental, so this app is suitable for local
development and beta testing, not production release channels.
