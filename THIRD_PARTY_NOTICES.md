# Third-Party Notices

This repository's original source code is licensed under the root MIT license.
The project also depends on software and model files governed by their own
terms. Those terms continue to apply.

## Direct dependencies

- **Meta Wearables Device Access Toolkit for Android**
  (`mwdat-core`, `mwdat-speech`, `mwdat-inputs` 1.0.0): Meta Wearables
  Developer Terms and Acceptable Use Policy. This is not an open-source license.
  The artifacts are fetched from Maven and are not copied into this repository.
  <https://github.com/facebook/meta-wearables-dat-android/blob/main/LICENSE>
  <https://wearables.developer.meta.com/terms>
- **AndroidX Activity, Core, and Lifecycle**: Apache License 2.0.
  <https://www.apache.org/licenses/LICENSE-2.0>
- **Kotlin Coroutines**: Apache License 2.0.
  <https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt>
- **JUnit 4.13.2**: Eclipse Public License 1.0.
  <https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt>
- **Gradle Wrapper 9.5.0**: Apache License 2.0. Its distribution checksum is
  pinned in `android/gradle/wrapper/gradle-wrapper.properties`.
  <https://github.com/gradle/gradle/blob/master/LICENSE>
- **MLX-Audio 0.5.9**: MIT License. Python dependencies are pinned in
  `uv.lock`.
  <https://pypi.org/project/mlx-audio/0.5.9/>
- **PyTorch and torchaudio**: BSD-style licenses. They are used by OmniVoice's
  local reference-audio encoder; Python dependencies are pinned in `uv.lock`.
  <https://pytorch.org/>
- **Chatterbox Multilingual v3 MLX model**: MIT License. The default model
  revision is pinned to commit
  `03565773edd72e949572557597af8063bb49a18a`.
  <https://huggingface.co/mlx-community/chatterbox-multilingual-v3>
- **OmniVoice MLX model**: Apache License 2.0. The default model revision is
  pinned to commit `6119f7072580cc30c3cbdf496269216b03ad9742`.
  <https://huggingface.co/mlx-community/OmniVoice-bfloat16>

## Runtime choices

Hermes Agent, Tailscale, Android, macOS, model providers, and any STT/TTS command
you configure are separate products. Review their licenses and privacy terms
for your deployment. This file is a practical inventory, not legal advice.
