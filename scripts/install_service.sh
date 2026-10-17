#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
label=ai.hermes.glasses-bridge
plist_template="$project_dir/deploy/$label.plist.template"
target_plist="$HOME/Library/LaunchAgents/$label.plist"
runtime_dir="$HOME/.local/share/hermes-glasses-bridge"

case "${1:-}" in
  --dry-run)
    printf 'Would install runtime files into %s\n' "$runtime_dir"
    printf 'Would write LaunchAgent %s\n' "$target_plist"
    printf 'Would start user service %s\n' "$label"
    printf 'No files changed. No service started.\n'
    exit 0
    ;;
  "") ;;
  *)
    printf 'Usage: %s [--dry-run]\n' "$0" >&2
    exit 2
    ;;
esac

mkdir -p "$runtime_dir/scripts" "$runtime_dir/work/logs" "$HOME/Library/LaunchAgents"

# LaunchAgents cannot traverse macOS-protected Documents folders reliably. Keep
# the editable source here, but run a small deployed copy from Application Data.
/usr/bin/ditto "$project_dir/hermes_voice_bridge" "$runtime_dir/hermes_voice_bridge"
for script in run_bridge.sh chatterbox_tts.py omnivoice_tts.py configure_target.py; do
  install -m 755 "$project_dir/scripts/$script" "$runtime_dir/scripts/$script"
done

if [ ! -d "$runtime_dir/.venv" ]; then
  cp -cR "$project_dir/.venv" "$runtime_dir/.venv"
fi
if [ ! -d "$runtime_dir/work/voice-models" ]; then
  mkdir -p "$runtime_dir/work"
  cp -cR "$project_dir/work/voice-models" "$runtime_dir/work/voice-models"
fi

reference_argument=
if [ -f "$project_dir/work/voice-reference/owner-reference.wav" ]; then
  mkdir -p "$runtime_dir/work/voice-reference"
  install -m 600 "$project_dir/work/voice-reference/owner-reference.wav" \
    "$runtime_dir/work/voice-reference/owner-reference.wav"
  reference_argument="$runtime_dir/work/voice-reference/owner-reference.wav"
fi

if [ -n "$reference_argument" ]; then
  /usr/bin/python3 "$runtime_dir/scripts/configure_target.py" \
    --reference-audio "$reference_argument"
else
  /usr/bin/python3 "$runtime_dir/scripts/configure_target.py"
fi

sed "s|__RUNTIME_DIR__|$runtime_dir|g" "$plist_template" > "$target_plist"
chmod 600 "$target_plist"
launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$target_plist"
launchctl kickstart -k "gui/$(id -u)/$label"
printf 'Installed and started %s from %s\n' "$label" "$runtime_dir"
