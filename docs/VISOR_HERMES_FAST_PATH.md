# Fast glasses voice with Visor and Hermes

## What this path changes

The existing Android app uses Meta's public Device Access Toolkit (DAT):

```text
button -> DAT Speech -> final transcript -> Hermes -> cloned TTS -> WAV download -> Bluetooth audio
```

That remains the supported fallback. Its cloned voice is deliberately expensive: the bridge must
finish the Hermes reply, synthesize a complete audio file, download it, and only then start playing.

The experimental fast path uses [Visor](https://github.com/macedonga/visor) as the glasses owner app:

```text
"Hey Meta" -> glasses transcript -> Visor -> Hermes bridge -> reply text -> glasses built-in TTS
```

Visor implements the private encrypted glasses protocol and the `svc15` assistant service. The
Hermes bridge now exposes the small OpenAI-compatible surface Visor expects. No Visor code is copied
into this repository, and the DAT app does not claim to invoke the private TTS protocol.

This removes cloned-voice synthesis and the WAV download from the critical path. It does not make
Hermes itself instantaneous, and Visor currently waits for a complete model response before asking
the glasses to speak.

## Important compatibility boundary

Visor's source recognizes product ID `258`, Ray-Ban Meta (2023), but its README says real-device
testing is limited to Ray-Ban Meta Gen 2 firmware `72387580077200100`. Gen 1 assistant behavior is
therefore **plausible but unverified**.

Visor replaces the Meta AI app's active glasses session; it does not coexist with that session.
Setup can change the phone-side owner keys, and uninstalling removes Visor's keys. Back up/import
important media first and expect that returning to Meta AI may require pairing the glasses again.

A source-built Visor also needs Meta client tokens that its repository intentionally does not ship.
For an initial hardware experiment, use Visor's signed release and verify its published SHA-256.

## Configure the Hermes bridge in Visor

The Mac bridge must be reachable from the phone over Tailscale and must be restarted after updating
this repository.

In Visor's assistant settings choose:

- Provider: **OpenAI compatible**
- Base URL: `http://<mac-tailscale-ip>:8788/v1`
- API key: the existing `BRIDGE_API_KEY`
- Model: `hermes-en`, `hermes-hi`, or `hermes-fr`
- Glasses voice: **on**
- Vision: **off** for this first prototype

The bridge also advertises `hermes`, which is an English alias. Visor sends recent conversation
history on every request; the adapter keeps a contiguous suffix of the newest messages that fit
the bridge's configured text limit and discards an initial assistant answer if its question was
trimmed. It never skips a large recent message to reinsert unrelated older exchanges. Visor's
OpenAI tool declarations are accepted but ignored because Hermes has its own tool
system. Each completion also includes `hermes_latency_ms`, so a direct `curl` test can separate
Hermes/model time from glasses and phone audio time.

This endpoint supports non-streaming requests only. `stream: true` is rejected with HTTP 400
before a Hermes turn is started; configure compatible clients with `stream: false`.

You can verify the adapter before touching the glasses:

```sh
curl -sS \
  -H "Authorization: Bearer $BRIDGE_API_KEY" \
  http://<mac-tailscale-ip>:8788/v1/models

curl -sS \
  -H "Authorization: Bearer $BRIDGE_API_KEY" \
  -H "Content-Type: application/json" \
  http://<mac-tailscale-ip>:8788/v1/chat/completions \
  -d '{"model":"hermes-en","messages":[{"role":"user","content":"Say one short hello."}]}'
```

Do not paste the real key into screenshots, issues, or shell history shared with another person.

## First real-glasses experiment

1. Confirm the existing DAT app and bridge still work, so there is a known rollback path.
2. Back up any glasses media that matters.
3. Install the verified Visor release and complete its ownership/pairing flow.
4. Enter the Hermes settings above with **Glasses voice** on and **Vision** off.
5. Say “Hey Meta” and ask a short question.
6. Record these times: wake acknowledgement, transcript shown, Hermes answer returned, speech starts.
7. If the assistant protocol fails on Gen 1, stop there and return to the Meta AI/DAT pairing. Do not
   flash firmware or change undocumented firmware flags as part of this test.

Physical wake-word delivery, Gen 1 `svc15` compatibility, and built-in glasses TTS are unverified
until this test is performed with the glasses worn.

## Camera light

This first prototype is audio-only. It does not request a photo, so it has no reason to activate the
outward-facing capture/privacy light. Visual questions are intentionally rejected by the bridge.

The capture/privacy light is not a flashlight control. Do not cover, disconnect, or attempt to
disable it: current firmware monitors it, camera operation may stop when it is obstructed or altered,
and it is an important signal to people nearby. The inward status LED is a separate device feature.

## Rollback

The original Android app, typed-question flow, DAT Speech flow, and cloned voice remain unchanged.
If Visor does not work on Gen 1, uninstall it, put the glasses in pairing mode, reconnect them to the
Meta AI app, and continue using the DAT app.

