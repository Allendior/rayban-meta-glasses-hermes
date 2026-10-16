# Fast voice reliability validation — 2026-10-16

Continued from `b4c37f1` (Visor adapter and fast glasses voice mode), with a clean
working tree. Validation ran on the Mac, starting at 07:50 UTC. No Android phone,
emulator, or glasses were attached in `adb devices -l`.

## Changes and evidence

- Android's manifest was missing TTS service visibility. Added the query
  [documented by Android](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
  for apps targeting Android 11 and later; verified it in both merged manifests.
- `setLanguage` selects an engine's default voice, which could require networking.
  Fast mode now selects installed local voices by language, accent, engine-reported
  latency, and quality. Network-required and not-yet-installed voices are excluded.
  See the [Voice API](https://developer.android.com/reference/android/speech/tts/Voice).
  Metadata ranking is not a measured speech-start improvement.
- Fast playback previously waited indefinitely if the engine omitted its terminal
  callback, and did not handle `onStop`. Added a five-second start timeout, a
  45-second completion timeout after `onStart`, and stop/error recovery. Recovery
  invalidates the utterance and stops Android TTS before requesting cloned audio
  for the existing answer. UUIDs prevent collisions between replies. Lifecycle
  cancellation is propagated rather than reported as a failed question. The
  [listener API](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
  documents the start, stop, and completion callbacks.
- Reproduced Visor history corruption: an oversized recent question was skipped,
  while unrelated older exchanges and its orphaned answer were retained. Trimming
  now keeps a contiguous recent suffix and removes a leading orphaned answer.
- Streaming requests now return HTTP 400 before calling Hermes. This adapter
  remains non-streaming; Visor tool declarations are still accepted and ignored.

## Automated checks

- `python3 -m unittest discover -s tests -v`: **33 passed**. HTTP regression
  tests explicitly fail if a text-only request invokes synthesis. They cover
  persistent fast turns, cloned fallback without another Hermes call, cloned
  turns, authentication, tools, Unicode, vision rejection, history, and streaming.
- `cd android && ./run_gradle.sh test lint assembleDebug assembleRelease --offline`:
  **passed**, with **29 Android unit tests**, no failures, and no lint errors.
  Ten lint warnings concern existing target/dependency versions; dependencies
  were not changed in this phase. The final run includes the manifest change.
- `git diff --check`: passed before commit.
- Sandbox restrictions initially blocked HTTP test binds, ADB, and Gradle cache
  writes. The same checks succeeded with approved execution outside the sandbox.

## Live private-service checks

Used the existing runtime environment to authenticate, without printing keys or
response text. The probe used text-only requests against the already-deployed
implementation; these source changes have **not** been installed into the service.

| Request | Status | Mac-observed elapsed time | Result |
| --- | --- | --- | --- |
| Health | 200 | 21 ms | Bridge OK; Hermes reachable |
| Authenticated model catalogue | 200 | 1 ms | English, Hindi, French aliases present |
| English `/v1/turn`, `speak:false` | 200 | 2,317 ms | Text, session, no audio |
| French follow-up, same session | 200 | 1,592 ms | Session preserved, no audio |
| Hindi Visor-shaped completion, `stream:false`, tools declared | 200 | 1,655 ms | Assistant text; reported Hermes time 1,652 ms |

These are single short greeting requests, not a benchmark or a latency regression
comparison. No speech synthesis was requested. No command accessed or modified
`work/`, and the legacy E2E script was deliberately not run because it writes
there and invokes voice synthesis.

## APK preservation

The previous debug APK was copied and SHA-256 verified before any build. Its
durable, ignored archive is outside Gradle build directories:

- `android/apk-backups/20261016T075044Z/app-debug.apk`
- SHA-256: `94770c9f99d2232f34ddf3b8a1434d6e6d8341a9a65d56993351e5f5e74b2809`

The rebuilt artifacts remain ignored and are not committed:

- Debug: `android/app/build/outputs/apk/debug/app-debug.apk`
  — SHA-256 `392ba59ba510048fb909425cac45b3d62f410645a567dfb81b86f0f9e9501a99`.
- Unsigned release: `android/app/build/outputs/apk/release/app-release-unsigned.apk`
  — SHA-256 `7d78cd4fe79a84c49f134557fc4a5469a4298e72239b2240a6c766574a96f76b`.

## Remaining acceptance work

1. Install the rebuilt debug APK on the actual phone; test local English, Hindi,
   and French voices, text-only requests, and repeated turns with Wi-Fi disabled
   and Tailscale connected. Measure transcript-to-answer and answer-to-speech time.
2. Physically select glasses as the Bluetooth media route and verify replies,
   cues, action-button capture, DAT transcription, interruptions, disconnects,
   screen-off behavior, and recovery. No physical behavior was tested here.
3. Exercise missing language data, engine errors/stops, absent callbacks, and
   cloned fallback on the phone. Timeout thresholds are initial bounds, not
   hardware-tuned values; unit tests cover voice policy and bridge behavior,
   not the actual Android TTS engine or watchdog execution.
4. Deploy the updated bridge deliberately before expecting the history and
   streaming fixes in the running service. Installation was not run because its
   existing workflow copies voice assets from `work/`.
5. Follow the Visor pairing/rollback checklist for wake word and built-in glasses
   TTS. [Visor's current README](https://github.com/macedonga/visor) still limits
   tested hardware to Gen 2 firmware `72387580077200100`; Gen 1 remains unverified.
   No Visor APK was installed and no glasses ownership/pairing was changed.
