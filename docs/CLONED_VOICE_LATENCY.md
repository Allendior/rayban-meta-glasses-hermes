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

The persistent worker keeps one cloned-voice model process alive and sends it
JSON-line requests. The bridge serializes access to that worker, writes each
reply to a private WAV file, and keeps the existing authenticated download
path. A bridge restart or the first request after startup still includes model
load time; later requests are the useful comparison.

## OmniVoice warm-worker test

After the OmniVoice worker was loaded on the development Mac, the same short
three-language samples completed as follows:

| Language | Warm synthesis |
| --- | ---: |
| English | 5,863 ms |
| Hindi | 4,772 ms |
| French | 5,371 ms |

This is a local software measurement, not a glasses hardware measurement. It
does not yet prove that the cloned voice is natural to a native listener, and
the physical glasses audio route still needs a worn-device test. OmniVoice's
reference-text alignment is currently optional in the bridge; adding the exact
transcript of the reference clip is the next quality experiment.

The next hardware test should use the same short English, Hindi, and French
questions twice after the bridge has been running for at least one minute.
Compare the `cloned voice` number in the app status line and listen for
cross-language accent or pronunciation problems.
