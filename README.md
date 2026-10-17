# Ray-Ban Meta Glasses + Hermes

Connect your Ray-Ban Meta glasses to your own Hermes AI agent instead of
Meta's built in assistant. Ask it something out loud on the glasses, it
thinks with your own Hermes agent, and answers back in your own cloned voice.
Everything between the phone and bridge runs over your private Tailscale
network. The bridge stays on your Mac. Meta still handles the glasses and
speech features it provides, and Hermes uses whatever model provider you
configure.

This is not an official Meta or Nous Research project, I'm not affiliated
with either. I built this because I wanted my glasses to talk to my own
agent, not Meta's.

This is a companion service that connects a phone client to a Hermes Agent
running on your own Mac. It keeps the Meta glasses SDK out of the agent
process and gives mobile clients one stable protocol for:

- persistent Hermes conversations;
- English, Hindi, and French replies;
- local speech-to-text and text-to-speech providers;
- Tailscale-only access when bound to the Mac's Tailscale address.

The bridge service itself stays on your own machines. It accepts requests only
on loopback or a Tailscale address. Local STT and TTS stay local when you use
the included macOS or Chatterbox path. Your Hermes model traffic follows the
provider you selected in Hermes, and Meta's glasses SDK follows Meta's own data
handling.

macOS system voices work out of the box as a fallback. If you want your own
cloned voice answering back instead of a generic robot voice, the bridge also
supports MLX-Audio Chatterbox Multilingual v3, running entirely on your Mac,
in English, Hindi, and French.

The Android companion app is in [`android/`](android/README.md). It uses Meta's
Device Access Toolkit for speech recognition on the glasses, sends final
transcripts to this bridge over Tailscale, and plays authenticated cloned-voice
replies through the phone's active Bluetooth audio route.

An experimental [Visor fast path](docs/VISOR_HERMES_FAST_PATH.md) lets Visor use
the glasses' wake word, first-turn transcript, and built-in glasses TTS while
Hermes continues to provide the answer. It avoids cloned-audio synthesis and
download latency. Ray-Ban Meta Gen 1 compatibility and physical glasses audio
are still unverified, so read the pairing, privacy-light, and rollback notes
before trying it.

## What you need

- **Meta Ray-Ban (or similar Meta AI) glasses**, paired to an Android phone
  with the Meta AI app, Developer Mode enabled on the glasses, and Meta's
  Speech API accepted for your account. Meta currently marks Speech as
  experimental and may gate access, so check the Meta AI app or Meta for
  Developers to see where you stand.
- **A Meta developer app**: register one at
  [developers.meta.com](https://developers.meta.com/) to get your own
  `mwdat_application_id` and `mwdat_client_token` for Device Access Toolkit.
  Meta ships a documented `0`/test credential pair for local development; you
  need your own for anything beyond that.
- **A Mac** (Apple Silicon recommended for Chatterbox voice cloning) already
  running [Hermes Agent](https://github.com/NousResearch/hermes-agent) with
  its API server reachable on loopback.
- **[Tailscale](https://tailscale.com/)** installed and signed into the same
  tailnet on both the Mac and the Android phone. The bridge deliberately
  refuses plain HTTP to anything outside a private/tailnet address, see
  `BridgeEndpoint.kt` and the bridge's own host checks if you want the
  details.
- Python 3.11+ and [`uv`](https://github.com/astral-sh/uv) on the Mac; JDK 17
  and Android SDK 36 to build the Android app.

You don't need an Apple Developer account or a Meta production app review.
Meta's Speech API access is the one thing outside this project's control.
Hermes may still use a cloud model provider if that is how your Hermes install
is configured.

## Data flow and privacy

- **Glasses and Meta AI app:** Meta's SDK captures the glasses interaction and
  provides the final transcript. Meta's terms and privacy policy apply here.
- **Android to Mac:** the companion app sends that transcript to the bridge
  over your tailnet with a bearer key stored by Android Keystore.
- **Bridge to Hermes:** the bridge calls the loopback-only Hermes API. Hermes
  then uses your selected model provider, which may be local or cloud-hosted.
- **Reply audio:** macOS voices and Chatterbox run locally. Chatterbox downloads
  its model files from Hugging Face the first time unless you pre-stage them.
- **Temporary files:** generated audio is mode `0600`, stored in a mode `0700`
  directory, and removed after its configured TTL. Voice references and keys
  stay outside Git.

This project does not turn a cloud-configured Hermes setup into a local-only
system. Check your Hermes model, Meta, and optional model-download choices if
you need a fully local path.

## Quick start

1. Export the same API key that you configured for Hermes as
   `API_SERVER_KEY` or `HERMES_API_KEY`. Load it from your password manager or
   secure shell environment. Do not put it in a command you plan to share.
2. Run `./scripts/configure_target.py` to generate a mode-0600 `.env` with a
   random bridge key and the active Tailscale IPv4 address. The script uses the
   already-exported key and does not read Hermes's private `.env` file.
3. Enable Hermes's API server on loopback port 8642 using that same key.
4. Start Hermes with `hermes gateway`.
5. Run the bridge:

   ```sh
   set -a
   source .env
   set +a
   ./scripts/run_bridge.sh
   ```

6. Check `http://127.0.0.1:8787/health`.

For remote access, set `BRIDGE_HOST` to the Mac's Tailscale IP. Keep Hermes on
`127.0.0.1`; only the bridge needs to be reachable from the phone. This target
uses bridge port 8788 because another local service owns 8787.

## Target Mac service

The editable checkout lives under `Documents`, which macOS does not expose to
background launch agents. Install a small runtime copy and persistent service
under `~/.local/share` with:

```sh
./scripts/install_service.sh --dry-run
./scripts/install_service.sh
./scripts/e2e_target_test.sh
```

The preview does not write files or start a service. To stop and remove the
LaunchAgent later, run `./scripts/remove_service.sh`. It deliberately leaves
the runtime directory in place so uninstalling cannot silently destroy keys or
voice recordings.

The install script preserves the bridge API key across reinstalls. It also
copies a prepared `work/voice-reference/owner-reference.wav`, if present, and
automatically switches the deployed service from the system voice to
OmniVoice. Pass `VOICE_ENGINE=chatterbox ./scripts/install_service.sh` when the
older Chatterbox path is needed as a fallback.

## Voice-cloning setup

Install the pinned MLX runtime and the local audio encoder in the isolated
project environment:

```sh
UV_CACHE_DIR="$TMPDIR/hermes-glasses-uv-cache" uv sync --extra voice --locked
```

Record the owner, benchmark all target languages, then reinstall the service:

```sh
./scripts/record_voice_reference.sh 45
HF_HOME="$PWD/work/voice-models" \
  .venv/bin/python scripts/benchmark_voice.py \
  work/voice-reference/owner-reference.wav \
  --output-dir work/benchmarks/owner-reference
./scripts/install_service.sh
```

If AVFoundation's default audio device is not the microphone, list devices with
`ffmpeg -f avfoundation -list_devices true -i ""` and pass the desired audio
specifier as the second argument to `record_voice_reference.sh`.

## Phone-facing API

All `/v1/*` requests require `Authorization: Bearer <BRIDGE_API_KEY>`.

- `POST /v1/transcribe?language=en` accepts raw WAV/audio bytes and returns
  text. This requires a configured local STT command.
- `POST /v1/turn` accepts JSON such as
  `{"text":"What happened today?","language":"en","speak":true}`.
- `POST /v1/synthesize` accepts JSON such as
  `{"text":"Bonjour","language":"fr"}`.
- `GET /v1/audio/<id>` returns generated audio.
- `GET /v1/models` lists the Hermes aliases available to OpenAI-compatible clients.
- `POST /v1/chat/completions` accepts the non-streaming OpenAI chat-completions
  shape used by Visor. Use `hermes-en`, `hermes-hi`, or `hermes-fr` as the model.

`session_id` returned by `/v1/turn` should be sent back on later turns.

## Local voice command contract

Set `TTS_PROVIDER=command` and define `TTS_COMMAND` as an argument template.
The bridge executes it without a shell. Supported placeholders are:

- `{text_file}`: UTF-8 input text file;
- `{output_file}`: requested output WAV file;
- `{language}`: `en`, `hi`, or `fr`;
- `{reference_audio}`: your voice reference recording.

The process must exit successfully and create `{output_file}`. Example:

```dotenv
TTS_PROVIDER=command
TTS_REFERENCE_AUDIO=/absolute/path/to/my-voice.wav
TTS_COMMAND=/absolute/path/to/python /absolute/path/to/chatterbox_tts.py --text-file {text_file} --output {output_file} --language {language} --reference {reference_audio}
```

For MLX-based cloned voices, `TTS_WORKER_COMMAND` can keep the model loaded
between replies. It receives JSON lines containing `text`, `language`, and
`output_file`, and must print `{"ready": true}` once at startup followed by
`{"ok": true}` after each generated file. The bundled Chatterbox worker is
configured automatically by `scripts/configure_target.py` when a reference is
provided. The first reply after a bridge restart still pays model-load time;
later replies avoid that repeated startup cost. With the default configuration,
the bundled OmniVoice worker is selected; Chatterbox remains available through
the fallback command shown above.

The target install generates this command automatically when the owner reference
exists; keys and voice recordings remain outside version control. OmniVoice uses
the same reference clip for English, Hindi, and French. For best cross-language
cloning, set the private `OMNIVOICE_REFERENCE_TEXT` value in the generated
`.env` to the exact words spoken in the clean reference clip. If it is empty,
the bridge still clones from the preprocessed audio, but the model documentation
warns that alignment can be less stable.

OmniVoice is a local MLX model with a 0.6B Qwen backbone and a 24 kHz HiggsAudio
decoder. The default worker uses 16 diffusion steps to reduce latency and keeps
the encoded reference prompt in memory between replies. The MLX model is
downloaded from Hugging Face on first use; its pinned revision and license are
listed in `THIRD_PARTY_NOTICES.md`.

The STT command uses the same pattern with `{input_file}`, `{output_file}`, and
`{language}`; it must write the transcript as UTF-8 text.

## Validation

Run the dependency-free test suite with:

```sh
python3 -m unittest discover -s tests -v
```

## Troubleshooting

- **App shows "Bridge URL needs a host" or is blank on first launch**: that's
  expected, there is no default bridge URL baked into the app. Enter your
  Mac's Tailscale IPv4 address (`tailscale ip -4` on the Mac) and the port
  from its `.env` (`BRIDGE_PORT`).
- **"Plain HTTP is allowed only for a Tailscale or local address"**: the app
  refuses `http://` to anything outside `100.64.0.0/10`, `127.0.0.1`,
  `localhost`, or the Android emulator's `10.0.2.2`. Use your Tailscale IP, or
  put a TLS terminating proxy in front of the bridge for anything else.
- **Bridge starts but the phone can't reach it**: confirm both devices are on
  the same tailnet (`tailscale status` on the Mac should list the phone), and
  that `BRIDGE_HOST` in `.env` is the Mac's Tailscale IP, not `127.0.0.1`.
- **Meta glasses won't register / "Speech not available"**: your Meta account
  needs Speech API access, which Meta gates and may not grant immediately.
  Typed questions in the app work independently of glasses registration, so
  use those to verify the rest of the pipeline first.
- **Voice cloning sounds off / falls back to a system voice**: check
  `TTS_PROVIDER` in `.env` and that `work/voice-reference/owner-reference.wav`
  exists; `scripts/benchmark_voice.py` helps compare a reference clip across
  languages before committing to it.
