#!/usr/bin/env python3
"""Synthesize cloned speech with MLX Chatterbox Multilingual on Apple silicon."""

from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import sys


SUPPORTED_LANGUAGES = frozenset({"en", "hi", "fr"})
DEFAULT_MODEL = "mlx-community/chatterbox-multilingual-v3"
DEFAULT_MODEL_REVISION = "03565773edd72e949572557597af8063bb49a18a"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--text-file")
    parser.add_argument("--output")
    parser.add_argument("--language", choices=sorted(SUPPORTED_LANGUAGES))
    parser.add_argument("--reference", required=True)
    parser.add_argument("--model", default=os.environ.get("CHATTERBOX_MODEL", DEFAULT_MODEL))
    parser.add_argument(
        "--model-revision",
        default=os.environ.get("CHATTERBOX_MODEL_REVISION", DEFAULT_MODEL_REVISION),
    )
    return parser.parse_args()


def synthesize(model, text: str, output_path: Path, language: str, reference_path: Path) -> None:
    import mlx.core as mx
    import numpy as np
    from mlx_audio.audio_io import write as audio_write

    chunks = []
    sample_rate = model.sample_rate
    for result in model.generate(
        text=text,
        ref_audio=str(reference_path),
        lang_code=language,
        exaggeration=0.1,
        cfg_weight=0.5,
        temperature=0.8,
        repetition_penalty=1.2,
        min_p=0.05,
        top_p=1.0,
        max_new_tokens=1200,
        verbose=False,
    ):
        chunks.append(result.audio)
        sample_rate = result.sample_rate

    if not chunks:
        raise RuntimeError("Chatterbox returned no audio")
    audio = chunks[0] if len(chunks) == 1 else mx.concatenate(chunks, axis=0)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    audio_write(str(output_path), np.asarray(audio), sample_rate, format="wav")
    if not output_path.is_file() or output_path.stat().st_size <= 44:
        raise RuntimeError("Chatterbox did not create a valid WAV file")


def main() -> int:
    args = parse_args()
    reference_path = Path(args.reference).expanduser().resolve()

    if not reference_path.is_file():
        raise SystemExit(f"reference audio not found: {reference_path}")
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

    # The worker uses stdout as a JSON-lines protocol. Some MLX dependencies
    # print progress or diagnostics to stdout, so keep those messages away from
    # the protocol stream.
    protocol_stdout = sys.stdout
    with redirect_stdout(sys.stderr):
        # Import only after argument validation so configuration failures remain fast.
        from huggingface_hub import snapshot_download
        from mlx_audio.tts.utils import load_model

        model_path = snapshot_download(
            repo_id=args.model,
            revision=args.model_revision,
        )
        model = load_model(model_path)

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
                    synthesize(model, text, output_path, language, reference_path)
                print(json.dumps({"ok": True}), file=protocol_stdout, flush=True)
            except Exception:
                print(json.dumps({"ok": False}), file=protocol_stdout, flush=True)
        return 0

    synthesize(model, text, output_path, args.language, reference_path)
    print(f"created {output_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
