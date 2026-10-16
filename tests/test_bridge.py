from __future__ import annotations

from pathlib import Path
import http.client
import json
import os
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from hermes_voice_bridge.config import Settings, normalize_language
from hermes_voice_bridge.hermes import HermesClient, HermesError, limit_spoken_reply
from hermes_voice_bridge.server import (
    BridgeApplication,
    make_server,
    openai_messages_to_prompt,
    openai_model_language,
    public_upstream_error,
    validated_text,
)
from hermes_voice_bridge.speech import SpeechError, SpeechService


ROOT = Path(__file__).resolve().parent.parent


def settings(**changes):
    values = dict(
        bridge_host="127.0.0.1",
        bridge_port=8787,
        bridge_api_key="b" * 32,
        hermes_base_url="http://127.0.0.1:1",
        hermes_api_key="h" * 32,
        tts_provider="macos",
        tts_command="",
        tts_reference_audio="",
        stt_command="",
        audio_ttl_seconds=3600,
        max_concurrent_requests=2,
        max_text_chars=2_000,
        voices={"en": "Samantha", "hi": "Lekha", "fr": "Thomas"},
    )
    values.update(changes)
    return Settings(**values)


class ConfigTests(unittest.TestCase):
    def test_language_normalization(self):
        self.assertEqual(normalize_language("fr-CA"), "fr")
        with self.assertRaises(ValueError):
            normalize_language("de")


class HermesClientTests(unittest.TestCase):
    def setUp(self):
        self.client = HermesClient("http://hermes.test", "test-key")
        self.requests = []

        def fake_request(method, path, body=None):
            self.requests.append((method, path, body))
            if path == "/api/sessions":
                return {"session": {"id": body["id"]}}
            session_id = path.split("/")[3]
            return {
                "session_id": session_id,
                "message": {"role": "assistant", "content": "A sourced answer."},
            }

        self.client._request = fake_request

    def test_persistent_turn(self):
        reply, session_id = self.client.turn("What happened?", language="en")
        self.assertEqual(reply, "A sourced answer.")
        self.assertTrue(session_id.startswith("glasses_"))
        second_reply, second_session = self.client.turn(
            "Tell me more", session_id, language="fr"
        )
        self.assertEqual(second_reply, "A sourced answer.")
        self.assertEqual(second_session, session_id)
        self.assertEqual(len(self.requests), 3)
        self.assertIn("natural English", self.requests[1][2]["instructions"])
        self.assertIn("natural French", self.requests[2][2]["instructions"])

    def test_spoken_reply_is_enforced_server_side(self):
        reply = limit_spoken_reply("word " * 80)
        self.assertLessEqual(len(reply), 240)
        self.assertTrue(reply.endswith("..."))


class PublicReleaseTests(unittest.TestCase):
    def test_text_input_is_bounded(self):
        self.assertEqual(validated_text(" hello ", 10), "hello")
        with self.assertRaisesRegex(ValueError, "required"):
            validated_text("   ", 10)
        with self.assertRaisesRegex(ValueError, "too long"):
            validated_text("123456", 5)

    def test_expensive_work_has_a_bounded_concurrency_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            app = BridgeApplication(
                settings(max_concurrent_requests=1), Path(temp) / "audio"
            )
            self.assertTrue(app.try_acquire_work())
            self.assertFalse(app.try_acquire_work())
            app.release_work()
            self.assertTrue(app.try_acquire_work())
            app.release_work()

    def test_upstream_errors_are_not_exposed_to_clients(self):
        for error in (
            HermesError("provider said secret internal detail"),
            SpeechError("command stderr with a private path"),
        ):
            with self.subTest(error=type(error).__name__):
                with self.assertLogs("hermes_voice_bridge.server", level="WARNING"):
                    message = public_upstream_error(error)
                self.assertEqual(message, "upstream service unavailable")

    def test_e2e_script_uses_runtime_bridge_key(self):
        script = (ROOT / "scripts" / "e2e_target_test.sh").read_text(encoding="utf-8")
        self.assertNotIn("Bearer ***", script)
        self.assertIn('auth_header="Authorization: Bearer ${BRIDGE_API_KEY}"', script)
        self.assertIn(' -H "$auth_header" ', script)

    def test_voice_model_revision_is_pinned(self):
        script = (ROOT / "scripts" / "chatterbox_tts.py").read_text(encoding="utf-8")
        self.assertIn("DEFAULT_MODEL_REVISION", script)
        self.assertIn("revision=args.model_revision", script)

    def test_installer_has_preview_and_uninstall_paths(self):
        installer = (ROOT / "scripts" / "install_service.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("--dry-run", installer)
        self.assertTrue((ROOT / "scripts" / "remove_service.sh").is_file())

    def test_configure_script_does_not_read_hermes_private_env(self):
        script = (ROOT / "scripts" / "configure_target.py").read_text(encoding="utf-8")
        self.assertNotIn('Path.home() / ".hermes" / ".env"', script)
        self.assertIn('os.environ.get("API_SERVER_KEY")', script)


class OpenAICompatibilityTests(unittest.TestCase):
    def test_model_name_selects_supported_spoken_language(self):
        self.assertEqual(openai_model_language("hermes"), "en")
        self.assertEqual(openai_model_language("hermes-en"), "en")
        self.assertEqual(openai_model_language("hermes-hi"), "hi")
        self.assertEqual(openai_model_language("hermes-fr"), "fr")

    def test_chat_messages_become_a_bounded_conversation_prompt(self):
        prompt = openai_messages_to_prompt(
            [
                {"role": "system", "content": "Be concise."},
                {"role": "user", "content": "Who is Ada?"},
                {"role": "assistant", "content": "A computing pioneer."},
                {"role": "user", "content": "What did she write?"},
            ],
            max_chars=500,
        )
        self.assertIn("System: Be concise.", prompt)
        self.assertIn("User: Who is Ada?", prompt)
        self.assertIn("Assistant: A computing pioneer.", prompt)
        self.assertTrue(prompt.endswith("User: What did she write?"))

    def test_old_history_is_trimmed_but_latest_question_is_kept(self):
        prompt = openai_messages_to_prompt(
            [
                {"role": "user", "content": "old " * 40},
                {"role": "assistant", "content": "older " * 30},
                {"role": "user", "content": "latest question"},
            ],
            max_chars=80,
        )
        self.assertLessEqual(len(prompt), 80)
        self.assertEqual(prompt, "User: latest question")

    def test_visual_content_is_rejected_instead_of_silently_ignored(self):
        with self.assertRaisesRegex(ValueError, "vision"):
            openai_messages_to_prompt(
                [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "What is this?"},
                            {
                                "type": "image_url",
                                "image_url": {"url": "data:image/jpeg;base64,abc"},
                            },
                        ],
                    }
                ],
                max_chars=500,
            )

    def test_history_trimming_keeps_a_contiguous_suffix_without_orphan_answers(self):
        prompt = openai_messages_to_prompt(
            [
                {"role": "user", "content": "Old question"},
                {"role": "assistant", "content": "Old answer"},
                {"role": "user", "content": "Recent question " * 20},
                {"role": "assistant", "content": "Yes."},
                {"role": "user", "content": "Why?"},
            ],
            max_chars=80,
        )
        self.assertEqual(prompt, "User: Why?")

    def test_latest_question_must_fit_even_without_history(self):
        with self.assertRaisesRegex(ValueError, "latest user message is too long"):
            openai_messages_to_prompt(
                [{"role": "user", "content": "long question"}], max_chars=10
            )

    def test_text_parts_preserve_unicode(self):
        prompt = openai_messages_to_prompt(
            [{"role": "user", "content": [{"type": "text", "text": "नमस्ते"},
                                           {"type": "text", "text": "Bonjour"}]}],
            max_chars=80,
        )
        self.assertEqual(prompt, "User: नमस्ते\nBonjour")

    def test_latest_user_question_is_required(self):
        with self.assertRaisesRegex(ValueError, "user message"):
            openai_messages_to_prompt(
                [{"role": "system", "content": "Be concise."}],
                max_chars=500,
            )


class OpenAICompatibilityHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = make_server(
            settings(bridge_port=0), Path(self.temp.name) / "audio"
        )
        self.turns = []

        class FakeHermes:
            def __init__(fake_self, turns):
                fake_self.turns = turns

            def turn(fake_self, text, session_id=None, language="en"):
                fake_self.turns.append((text, session_id, language))
                return "Fast answer.", "session-test"

        self.server.app.hermes = FakeHermes(self.turns)
        # Text-only paths must never call a real synthesizer or touch model caches.
        self.synthesis = patch.object(
            self.server.app.speech, "synthesize",
            side_effect=AssertionError("unexpected synthesis on a text-only path"),
        )
        self.synthesize = self.synthesis.start()
        self.addCleanup(self.synthesis.stop)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_address[1], timeout=2
        )

    def tearDown(self):
        self.connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method, path, payload=None, authorized=True):
        headers = {"Content-Type": "application/json"}
        if authorized:
            headers["Authorization"] = f"Bearer {'b' * 32}"
        body = json.dumps(payload) if payload is not None else None
        self.connection.request(method, path, body=body, headers=headers)
        response = self.connection.getresponse()
        result = json.loads(response.read().decode("utf-8"))
        return response.status, result

    def test_visors_chat_completion_shape_calls_hermes_without_tts(self):
        status, result = self.request(
            "POST",
            "/v1/chat/completions",
            {
                "model": "hermes-hi",
                "messages": [{"role": "user", "content": "नमस्ते"}],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(result["object"], "chat.completion")
        self.assertEqual(result["choices"][0]["message"]["content"], "Fast answer.")
        self.assertGreaterEqual(result["hermes_latency_ms"], 0)
        self.assertEqual(self.turns, [("User: नमस्ते", None, "hi")])

    def test_model_catalogue_requires_the_bridge_key(self):
        status, _ = self.request("GET", "/v1/models", authorized=False)
        self.assertEqual(status, 401)
        status, result = self.request("GET", "/v1/models")
        self.assertEqual(status, 200)
        self.assertIn("hermes-fr", [model["id"] for model in result["data"]])

    def test_visors_memory_tools_are_ignored_without_an_extra_retry(self):
        status, result = self.request(
            "POST",
            "/v1/chat/completions",
            {
                "model": "hermes",
                "messages": [{"role": "user", "content": "hello"}],
                "tools": [{"type": "function", "function": {"name": "remember"}}],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(result["choices"][0]["message"]["content"], "Fast answer.")
        self.assertEqual(self.turns, [("User: hello", None, "en")])

    def test_android_fast_turn_preserves_session_without_synthesis(self):
        status, first = self.request(
            "POST", "/v1/turn", {"text": "Bonjour", "language": "fr", "speak": False}
        )
        self.assertEqual(status, 200)
        self.assertIsNone(first["audio_url"])
        status, second = self.request(
            "POST", "/v1/turn",
            {"text": "Et ensuite?", "language": "fr", "speak": False,
             "session_id": first["session_id"]},
        )
        self.assertEqual(status, 200)
        self.assertEqual(second["session_id"], first["session_id"])
        self.assertEqual(self.turns, [("Bonjour", None, "fr"),
                                     ("Et ensuite?", "session-test", "fr")])
        self.synthesize.assert_not_called()

    def test_cloned_fallback_synthesizes_existing_reply_without_another_hermes_turn(self):
        self.synthesize.side_effect = None
        self.synthesize.return_value = ("testaudio", Path(self.temp.name) / "test.wav", "audio/wav")
        status, result = self.request(
            "POST", "/v1/synthesize", {"text": "Fast answer.", "language": "hi"}
        )
        self.assertEqual(status, 201)
        self.assertEqual(result["audio_url"], "/v1/audio/testaudio")
        self.synthesize.assert_called_once_with("Fast answer.", "hi")
        self.assertEqual(self.turns, [])

    def test_cloned_turn_still_requests_audio(self):
        self.synthesize.side_effect = None
        self.synthesize.return_value = ("testaudio", Path(self.temp.name) / "test.wav", "audio/wav")
        status, result = self.request(
            "POST", "/v1/turn", {"text": "Hello", "language": "en", "speak": True}
        )
        self.assertEqual(status, 200)
        self.assertEqual(result["audio_url"], "/v1/audio/testaudio")
        self.synthesize.assert_called_once_with("Fast answer.", "en")

    def test_streaming_is_rejected_before_spending_a_hermes_turn(self):
        status, result = self.request(
            "POST", "/v1/chat/completions",
            {"messages": [{"role": "user", "content": "hello"}], "stream": True},
        )
        self.assertEqual(status, 400)
        self.assertIn("stream=false", result["error"])
        self.assertEqual(self.turns, [])

    def test_chat_and_fast_turn_authentication_reject_before_upstream_work(self):
        for path, body in (
            ("/v1/chat/completions", {"messages": [{"role": "user", "content": "hello"}]}),
            ("/v1/turn", {"text": "hello", "speak": False}),
            ("/v1/synthesize", {"text": "hello"}),
        ):
            status, _ = self.request("POST", path, body, authorized=False)
            self.assertEqual(status, 401)
        self.assertEqual(self.turns, [])
        self.synthesize.assert_not_called()

    def test_vision_is_rejected_over_http_before_hermes(self):
        status, result = self.request(
            "POST", "/v1/chat/completions",
            {"messages": [{"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,abc"}}
            ]}]},
        )
        self.assertEqual(status, 400)
        self.assertIn("turn off Vision", result["error"])
        self.assertEqual(self.turns, [])


class SettingsTests(unittest.TestCase):
    def test_wildcard_bind_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "wildcard"):
            settings(bridge_host="0.0.0.0").validate()

    def test_only_loopback_or_tailscale_bind_is_allowed(self):
        settings(bridge_host="127.0.0.1").validate()
        settings(bridge_host="100.64.0.1").validate()
        settings(bridge_host="100.127.255.254").validate()
        for host in ("192.168.1.10", "10.0.0.8", "8.8.8.8", "bridge.local"):
            with self.subTest(host=host):
                with self.assertRaisesRegex(ValueError, "loopback or Tailscale"):
                    settings(bridge_host=host).validate()

    def test_command_provider_requires_reference_audio(self):
        with self.assertRaisesRegex(ValueError, "TTS_REFERENCE_AUDIO"):
            settings(tts_provider="command", tts_command="tts").validate()


class SpeechServiceTests(unittest.TestCase):
    def test_audio_directory_is_private(self):
        with tempfile.TemporaryDirectory() as temp:
            audio_dir = Path(temp) / "audio"
            SpeechService(settings(), audio_dir)
            self.assertEqual(audio_dir.stat().st_mode & 0o777, 0o700)

    def test_expired_audio_is_removed_at_startup(self):
        with tempfile.TemporaryDirectory() as temp:
            audio_dir = Path(temp) / "audio"
            audio_dir.mkdir()
            stale = audio_dir / "stale.wav"
            stale.write_bytes(b"old")
            old = time.time() - 7200
            os.utime(stale, (old, old))
            SpeechService(settings(audio_ttl_seconds=3600), audio_dir)
            self.assertFalse(stale.exists())

    def test_command_provider_receives_language_and_reference(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            script = root / "fake_tts.py"
            script.write_text(
                "import pathlib,sys\n"
                "text=pathlib.Path(sys.argv[1]).read_text()\n"
                "pathlib.Path(sys.argv[2]).write_bytes((sys.argv[3]+':' + text).encode())\n"
            )
            reference = root / "voice.wav"
            reference.write_bytes(b"voice")
            cfg = settings(
                tts_provider="command",
                tts_reference_audio=str(reference),
                tts_command=f"python3 {script} {{text_file}} {{output_file}} {{language}} {{reference_audio}}",
            )
            service = SpeechService(cfg, root / "audio")
            _, output, media_type = service.synthesize("Bonjour", "fr")
            self.assertEqual(output.read_bytes(), b"fr:Bonjour")
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(media_type, "audio/wav")


if __name__ == "__main__":
    unittest.main()
