#!/usr/bin/env python3
"""Synthesize multilingual cloned speech with OmniVoice on Apple silicon.

The worker keeps the model and the encoded voice reference in memory. This is
important for the glasses path: encoding the reference voice for every Hermes
reply would add avoidable latency.
"""

from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import sys


SUPPORTED_LANGUAGES = frozenset({"en", "hi", "fr"})
LANGUAGE_NAMES = {"en": "english", "hi": "hindi", "fr": "french"}
DEFAULT_MODEL = "mlx-community/OmniVoice-bfloat16"
DEFAULT_MODEL_REVISION = "6119f7072580cc30c3cbdf496269216b03ad9742"
DEFAULT_NUM_STEPS = 16
DEFAULT_MAX_DURATION_SECONDS = 18.0


def language_name(language: str) -> str:
    try:
        return LANGUAGE_NAMES[language]
    except KeyError as exc:
        raise ValueError(f"unsupported language: {language}") from exc


def duration_for_text(text: str, maximum: float = DEFAULT_MAX_DURATION_SECONDS) -> float:
    """Give short replies a small generation budget without truncating normal replies."""
    estimated = len(text.strip()) / 14.0 + 1.5
    return round(max(4.0, min(maximum, estimated)), 1)


def build_generation_kwargs(
    *,
    text: str,
    language: str,
    ref_tokens,
    ref_text: str,
    duration_s: float,
    num_steps: int,
) -> dict[str, object]:
    kwargs: dict[str, object] = {
        "text": text,
        "language": language_name(language),
        "ref_tokens": ref_tokens,
        "duration_s": duration_s,
        "num_steps": num_steps,
    }
    if ref_text:
        kwargs["ref_text"] = ref_text
    return kwargs


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--text-file")
    parser.add_argument("--output")
    parser.add_argument("--language", choices=sorted(SUPPORTED_LANGUAGES))
    parser.add_argument("--reference", required=True)
    parser.add_argument(
        "--reference-text",
        default=os.environ.get("OMNIVOICE_REFERENCE_TEXT", "").strip(),
        help="Optional transcript of the reference clip; improves cross-language cloning.",
    )
    parser.add_argument("--model", default=os.environ.get("OMNIVOICE_MODEL", DEFAULT_MODEL))
    parser.add_argument(
        "--model-revision",
        default=os.environ.get("OMNIVOICE_MODEL_REVISION", DEFAULT_MODEL_REVISION),
    )
    parser.add_argument(
        "--num-steps",
        type=int,
        default=int(os.environ.get("OMNIVOICE_NUM_STEPS", str(DEFAULT_NUM_STEPS))),
    )
    parser.add_argument(
        "--max-duration-s",
        type=float,
        default=float(
            os.environ.get(
                "OMNIVOICE_MAX_DURATION_SECONDS", str(DEFAULT_MAX_DURATION_SECONDS)
            )
        ),
    )
    return parser.parse_args()


def load_model(model_path: Path):
    """Load OmniVoice explicitly because its tokenizer pair is model-specific."""
    import mlx.core as mx
    from transformers import AutoTokenizer
    from mlx_audio.codec.models.higgs_audio.higgs_audio import HiggsAudioTokenizer
    from mlx_audio.tts.models.omnivoice.omnivoice import Model, OmniVoiceConfig

    raw_config = json.loads((model_path / "config.json").read_text(encoding="utf-8"))
    model = Model(OmniVoiceConfig.from_dict(raw_config))
    weights = model.sanitize(dict(mx.load(str(model_path / "model.safetensors"))))
    model.load_weights(list(weights.items()))
    mx.eval(model.parameters())
    model.audio_tokenizer = HiggsAudioTokenizer.from_pretrained(str(model_path))
    model.text_tokenizer = AutoTokenizer.from_pretrained(str(model_path))
    return model


def prepare_reference(model, reference_path: Path):
    import mlx.core as mx
    from mlx_audio.tts.models.omnivoice.utils import create_voice_clone_prompt

    tokens = create_voice_clone_prompt(
        str(reference_path),
        tokenizer=model.audio_tokenizer,
        max_duration_s=15.0,
    )
    mx.eval(tokens)
    return tokens


def synthesize(
    model,
    reference_tokens,
    reference_text: str,
    text: str,
    output_path: Path,
    language: str,
    num_steps: int,
    max_duration_seconds: float,
) -> None:
    import mlx.core as mx
    import numpy as np
    from mlx_audio.audio_io import write as audio_write

    results = list(
        model.generate(
            **build_generation_kwargs(
                text=text,
                language=language,
                ref_tokens=reference_tokens,
                ref_text=reference_text,
                duration_s=duration_for_text(text, max_duration_seconds),
                num_steps=num_steps,
            ),
            tokenizer=model.audio_tokenizer,
            text_tokenizer=model.text_tokenizer,
        )
    )
    if not results:
        raise RuntimeError("OmniVoice returned no audio")
    audio = results[0].audio
    if len(results) > 1:
        audio = mx.concatenate([result.audio for result in results], axis=0)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    audio_write(str(output_path), np.asarray(audio), results[0].sample_rate, format="wav")
    if not output_path.is_file() or output_path.stat().st_size <= 44:
        raise RuntimeError("OmniVoice did not create a valid WAV file")


def main() -> int:
    args = parse_args()
    reference_path = Path(args.reference).expanduser().resolve()
    if not reference_path.is_file():
        raise SystemExit(f"reference audio not found: {reference_path}")
    if not 4 <= args.num_steps <= 64:
        raise SystemExit("--num-steps must be between 4 and 64")
    if args.max_duration_s <= 0:
        raise SystemExit("--max-duration-s must be positive")
    if not args.worker:
        if not args.text_file or not args.output or not args.language:
            raise SystemExit("--text-file, --output, and --language are required")
        text_path = Path(args.text_file).expanduser().resolve()
        output_path = Path(args.output).expanduser().resolve()
        if not text_path.is_file():
            raise SystemExit(f"text file not found: {text_path}")
        text = text_path.read_text(encoding="utf-8").strip()
        if not text:
            raise SystemExit("text file is empty")

    protocol_stdout = sys.stdout
    with redirect_stdout(sys.stderr):
        from huggingface_hub import snapshot_download

        model_path = Path(
            snapshot_download(
                repo_id=args.model,
                revision=args.model_revision,
            )
        )
        model = load_model(model_path)
        reference_tokens = prepare_reference(model, reference_path)

    if args.worker:
        print(json.dumps({"ready": True}), file=protocol_stdout, flush=True)
        for raw in sys.stdin:
            try:
                request = json.loads(raw)
                if request.get("shutdown") is True:
                    break
                text = str(request.get("text") or "").strip()
                language = str(request.get("language") or "")
                output_raw = str(request.get("output_file") or "").strip()
                output_path = Path(output_raw).expanduser().resolve()
                if not text or language not in SUPPORTED_LANGUAGES or not output_raw:
                    raise ValueError("invalid TTS worker request")
                with redirect_stdout(sys.stderr):
                    synthesize(
                        model,
                        reference_tokens,
                        args.reference_text,
                        text,
                        output_path,
                        language,
                        args.num_steps,
                        args.max_duration_s,
                    )
                print(json.dumps({"ok": True}), file=protocol_stdout, flush=True)
            except Exception:
                print(json.dumps({"ok": False}), file=protocol_stdout, flush=True)
        return 0

    with redirect_stdout(sys.stderr):
        synthesize(
            model,
            reference_tokens,
            args.reference_text,
            text,
            output_path,
            args.language,
            args.num_steps,
            args.max_duration_s,
        )
    print(f"created {output_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
