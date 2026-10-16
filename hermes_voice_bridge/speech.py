from __future__ import annotations

import os
import json
from pathlib import Path
import select
import shlex
import subprocess
import tempfile
import threading
import uuid

from .config import Settings


class SpeechError(RuntimeError):
    pass


def _render_command(template: str, values: dict[str, str]) -> list[str]:
    args = shlex.split(template)
    rendered: list[str] = []
    for arg in args:
        for name, value in values.items():
            arg = arg.replace("{" + name + "}", value)
        rendered.append(arg)
    return rendered


class SpeechService:
    def __init__(self, settings: Settings, audio_dir: Path):
        self.settings = settings
        self.audio_dir = audio_dir
        self.audio_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.audio_dir, 0o700)
        self._worker = None
        self._worker_lock = threading.Lock()
        self.cleanup()

    def close(self) -> None:
        with self._worker_lock:
            self._stop_worker()

    def cleanup(self) -> None:
        cutoff = __import__("time").time() - self.settings.audio_ttl_seconds
        for path in self.audio_dir.glob("*"):
            try:
                if path.is_file() and path.stat().st_mtime < cutoff:
                    path.unlink()
            except OSError:
                continue

    def _worker_command(self) -> list[str]:
        return _render_command(
            self.settings.tts_worker_command,
            {"reference_audio": self.settings.tts_reference_audio},
        )

    def _read_worker_line(self) -> dict[str, object]:
        worker = self._worker
        if worker is None or worker.stdout is None:
            raise SpeechError("TTS worker is not running")
        ready, _, _ = select.select([worker.stdout], [], [], 300)
        if not ready:
            raise SpeechError("TTS worker timed out")
        raw = worker.stdout.readline()
        if not raw:
            raise SpeechError("TTS worker stopped")
        try:
            response = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SpeechError("TTS worker returned invalid output") from exc
        if not isinstance(response, dict):
            raise SpeechError("TTS worker returned an invalid response")
        return response

    def _start_worker(self) -> None:
        if self._worker is not None and self._worker.poll() is None:
            return
        self._stop_worker()
        try:
            self._worker = subprocess.Popen(
                self._worker_command(),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            response = self._read_worker_line()
        except (OSError, SpeechError) as exc:
            self._stop_worker()
            raise SpeechError("TTS worker could not start") from exc
        if response.get("ready") is not True:
            self._stop_worker()
            raise SpeechError("TTS worker did not become ready")

    def _stop_worker(self) -> None:
        worker = self._worker
        self._worker = None
        if worker is None:
            return
        if worker.stdin is not None and worker.poll() is None:
            try:
                worker.stdin.write(json.dumps({"shutdown": True}) + "\n")
                worker.stdin.flush()
            except OSError:
                pass
        try:
            worker.wait(timeout=2)
        except subprocess.TimeoutExpired:
            worker.kill()
            worker.wait()
        for stream in (worker.stdin, worker.stdout):
            if stream is not None:
                stream.close()

    def _synthesize_with_worker(
        self, text: str, language: str, output: Path
    ) -> None:
        with self._worker_lock:
            self._start_worker()
            worker = self._worker
            if worker is None or worker.stdin is None:
                raise SpeechError("TTS worker is not running")
            request = {
                "text": text,
                "language": language,
                "output_file": str(output),
            }
            try:
                worker.stdin.write(json.dumps(request) + "\n")
                worker.stdin.flush()
                response = self._read_worker_line()
            except (BrokenPipeError, OSError, SpeechError):
                self._stop_worker()
                raise
            if response.get("ok") is not True:
                self._stop_worker()
                raise SpeechError("TTS worker failed")

    def synthesize(self, text: str, language: str) -> tuple[str, Path, str]:
        self.cleanup()
        audio_id = uuid.uuid4().hex
        if self.settings.tts_provider == "macos":
            output = self.audio_dir / f"{audio_id}.aiff"
            command = [
                "/usr/bin/say",
                "-v",
                self.settings.voices[language],
                "-o",
                str(output),
                text,
            ]
            media_type = "audio/aiff"
            completed = subprocess.run(
                command, capture_output=True, text=True, timeout=300, check=False
            )
            if completed.returncode != 0:
                raise SpeechError(completed.stderr.strip() or "TTS command failed")
        elif self.settings.tts_worker_command:
            output = self.audio_dir / f"{audio_id}.wav"
            self._synthesize_with_worker(text, language, output)
            media_type = "audio/wav"
        else:
            output = self.audio_dir / f"{audio_id}.wav"
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", suffix=".txt", delete=False
            ) as handle:
                handle.write(text)
                text_file = handle.name
            command = _render_command(
                self.settings.tts_command,
                {
                    "text_file": text_file,
                    "output_file": str(output),
                    "language": language,
                    "reference_audio": self.settings.tts_reference_audio,
                },
            )
            media_type = "audio/wav"
            try:
                completed = subprocess.run(
                    command, capture_output=True, text=True, timeout=300, check=False
                )
            finally:
                try:
                    os.unlink(text_file)
                except OSError:
                    pass
            if completed.returncode != 0:
                raise SpeechError(completed.stderr.strip() or "TTS command failed")
        if not output.is_file() or output.stat().st_size == 0:
            raise SpeechError("TTS command did not create audio")
        os.chmod(output, 0o600)
        return audio_id, output, media_type

    def transcribe(self, audio: bytes, language: str, content_type: str) -> str:
        if not self.settings.stt_command:
            raise SpeechError("Local transcription is not configured")
        suffix = ".wav" if "wav" in content_type.lower() else ".audio"
        with tempfile.TemporaryDirectory(prefix="hermes-stt-") as temp_dir:
            input_file = Path(temp_dir) / f"input{suffix}"
            output_file = Path(temp_dir) / "transcript.txt"
            input_file.write_bytes(audio)
            command = _render_command(
                self.settings.stt_command,
                {
                    "input_file": str(input_file),
                    "output_file": str(output_file),
                    "language": language,
                },
            )
            completed = subprocess.run(
                command, capture_output=True, text=True, timeout=300, check=False
            )
            if completed.returncode != 0:
                raise SpeechError(completed.stderr.strip() or "STT command failed")
            if not output_file.is_file():
                raise SpeechError("STT command did not create a transcript")
            transcript = output_file.read_text(encoding="utf-8").strip()
            if not transcript:
                raise SpeechError("STT returned an empty transcript")
            return transcript

