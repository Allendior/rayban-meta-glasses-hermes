# Cloned voice latency

The first phone measurement of the complete path was:

| Stage | Time |
| --- | ---: |
| Hermes answer | 2,828 ms |
| Cloned-voice synthesis | 16,172 ms |
| Audio download to phone | 578 ms |

This makes the TTS process the clear bottleneck. The bridge used to launch a
new Python process for every reply. That process re-imported MLX and loaded the
Chatterbox model before generating audio, so model startup was repeated on
every question.

The persistent worker keeps one Chatterbox model process alive and sends it
JSON-line requests. The bridge serializes access to that worker, writes each
reply to a private WAV file, and keeps the existing authenticated download
path. A bridge restart or the first request after startup still includes model
load time; later requests are the useful comparison.

The next hardware test should use the same short English question twice after
the bridge has been running for at least one minute. Compare the `cloned voice`
number in the app status line. The goal is to measure the warm-worker path
before changing the model or sacrificing cloned-voice quality.
