#!/usr/bin/env python3
"""Run warm multilingual cloned-voice samples and save a benchmark."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import wave


SAMPLES = {
    "en": "The glasses bridge is ready. This is an English voice-cloning test.",
    "hi": "चश्मे का ब्रिज तैयार है। यह हिंदी आवाज़ की जाँच है।",
    "fr": "La passerelle des lunettes est prête. Ceci est un test vocal en français.",
    "mixed": "The bridge is ready, चश्मे तैयार हैं, et le test est terminé.",
}


def wav_duration(path: Path) -> float:
    with wave.open(str(path), "rb") as audio:
        return audio.getnframes() / audio.getframerate()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference_audio")
    parser.add_argument("--output-dir", default="work/benchmarks")
    parser.add_argument("--engine", choices=("omnivoice", "chatterbox"), default="omnivoice")
    parser.add_argument("--model")
    parser.add_argument("--reference-text", default="")
    parser.add_argument("--num-steps", type=int, default=16)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    reference = Path(args.reference_audio).expanduser().resolve()
    if not reference.is_file():
        raise SystemExit(f"reference audio not found: {reference}")
    output_dir = (root / args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    records = []

    script_name = "omnivoice_tts.py" if args.engine == "omnivoice" else "chatterbox_tts.py"
    command = [
        sys.executable,
        str(root / "scripts" / script_name),
        "--worker",
        "--reference",
        str(reference),
    ]
    if args.model:
        command.extend(["--model", args.model])
    if args.engine == "omnivoice":
        command.extend(["--num-steps", str(args.num_steps)])
        if args.reference_text:
            command.extend(["--reference-text", args.reference_text])

    worker = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    try:
        if worker.stdout is None or worker.stdin is None:
            raise RuntimeError("could not open TTS worker pipes")
        ready = worker.stdout.readline().strip()
        if json.loads(ready).get("ready") is not True:
            raise RuntimeError("TTS worker did not become ready")
        for name, text in SAMPLES.items():
            language = "en" if name == "mixed" else name
            output_file = output_dir / f"{name}.wav"
            started = time.monotonic()
            worker.stdin.write(
                json.dumps(
                    {"text": text, "language": language, "output_file": str(output_file)}
                )
                + "\n"
            )
            worker.stdin.flush()
            response = json.loads(worker.stdout.readline())
            elapsed = time.monotonic() - started
            record = {
                "sample": name,
                "language": language,
                "elapsed_seconds": round(elapsed, 3),
                "success": response.get("ok") is True and output_file.is_file(),
            }
            if record["success"]:
                duration = wav_duration(output_file)
                record["audio_seconds"] = round(duration, 3)
                record["real_time_factor"] = round(elapsed / duration, 3)
            else:
                record["error"] = "TTS worker failed"
            records.append(record)
            print(json.dumps(record, ensure_ascii=False), flush=True)
            if not record["success"]:
                break
    finally:
        if worker.stdin is not None and worker.poll() is None:
            worker.stdin.write(json.dumps({"shutdown": True}) + "\n")
            worker.stdin.flush()
        worker.wait(timeout=30)

    report = {
        "engine": args.engine,
        "model": args.model
        or (
            "mlx-community/OmniVoice-bfloat16"
            if args.engine == "omnivoice"
            else "mlx-community/chatterbox-multilingual-v3"
        ),
        "reference_audio": str(reference),
        "samples": records,
    }
    (output_dir / "benchmark.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return 0 if len(records) == len(SAMPLES) and all(r["success"] for r in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
