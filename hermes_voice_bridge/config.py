from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import os
from pathlib import Path


SUPPORTED_LANGUAGES = frozenset({"en", "hi", "fr"})


def _positive_int(name: str, default: int) -> int:
    raw = os.environ.get(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


@dataclass(frozen=True)
class Settings:
    bridge_host: str
    bridge_port: int
    bridge_api_key: str
    hermes_base_url: str
    hermes_api_key: str
    tts_provider: str
    tts_command: str
    tts_worker_command: str
    tts_reference_audio: str
    stt_command: str
    audio_ttl_seconds: int
    max_concurrent_requests: int
    max_text_chars: int
    voices: dict[str, str]

    @classmethod
    def from_env(cls) -> "Settings":
        provider = os.environ.get("TTS_PROVIDER", "macos").strip().lower()
        if provider not in {"macos", "command"}:
            raise ValueError("TTS_PROVIDER must be 'macos' or 'command'")
        return cls(
            bridge_host=os.environ.get("BRIDGE_HOST", "127.0.0.1").strip(),
            bridge_port=_positive_int("BRIDGE_PORT", 8787),
            bridge_api_key=os.environ.get("BRIDGE_API_KEY", "").strip(),
            hermes_base_url=os.environ.get(
                "HERMES_BASE_URL", "http://127.0.0.1:8642"
            ).rstrip("/"),
            hermes_api_key=os.environ.get("HERMES_API_KEY", "").strip(),
            tts_provider=provider,
            tts_command=os.environ.get("TTS_COMMAND", "").strip(),
            tts_worker_command=os.environ.get("TTS_WORKER_COMMAND", "").strip(),
            tts_reference_audio=os.environ.get("TTS_REFERENCE_AUDIO", "").strip(),
            stt_command=os.environ.get("STT_COMMAND", "").strip(),
            audio_ttl_seconds=_positive_int("AUDIO_TTL_SECONDS", 3600),
            max_concurrent_requests=_positive_int("MAX_CONCURRENT_REQUESTS", 2),
            max_text_chars=_positive_int("MAX_TEXT_CHARS", 2_000),
            voices={
                "en": os.environ.get("TTS_VOICE_EN", "Samantha").strip(),
                "hi": os.environ.get("TTS_VOICE_HI", "Lekha").strip(),
                "fr": os.environ.get("TTS_VOICE_FR", "Thomas").strip(),
            },
        )

    def validate(self) -> None:
        if not self.bridge_host:
            raise ValueError("BRIDGE_HOST must not be empty")
        try:
            bridge_address = ipaddress.ip_address(self.bridge_host)
        except ValueError as exc:
            raise ValueError(
                "BRIDGE_HOST must be a loopback or Tailscale IPv4 address"
            ) from exc
        if bridge_address.is_unspecified:
            raise ValueError(
                "BRIDGE_HOST must be loopback or a Tailscale address, not a wildcard"
            )
        tailscale_network = ipaddress.ip_network("100.64.0.0/10")
        if not bridge_address.is_loopback and bridge_address not in tailscale_network:
            raise ValueError(
                "BRIDGE_HOST must be a loopback or Tailscale IPv4 address"
            )
        if len(self.bridge_api_key) < 16:
            raise ValueError("BRIDGE_API_KEY must contain at least 16 characters")
        if len(self.hermes_api_key) < 8:
            raise ValueError("HERMES_API_KEY must contain at least 8 characters")
        if self.tts_provider == "command" and not self.tts_command:
            raise ValueError("TTS_COMMAND is required when TTS_PROVIDER=command")
        if self.tts_provider == "command":
            if not self.tts_reference_audio:
                raise ValueError(
                    "TTS_REFERENCE_AUDIO is required when TTS_PROVIDER=command"
                )
            if not Path(self.tts_reference_audio).is_file():
                raise ValueError("TTS_REFERENCE_AUDIO must point to a readable file")


def normalize_language(value: object) -> str:
    language = str(value or "en").strip().lower().split("-", 1)[0]
    if language not in SUPPORTED_LANGUAGES:
        raise ValueError("language must be one of: en, hi, fr")
    return language
