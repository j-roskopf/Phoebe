#!/usr/bin/env bash
# Starts a user-scoped PulseAudio null sink for Linux CI desktop playback tests.
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux audio CI setup is only needed on Linux."
  exit 0
fi

runtime_dir="${XDG_RUNTIME_DIR:-/tmp/phoebe-runtime-$(id -u)}"
mkdir -p "${runtime_dir}"
chmod 700 "${runtime_dir}"

export XDG_RUNTIME_DIR="${runtime_dir}"
export PULSE_SERVER="unix:${XDG_RUNTIME_DIR}/pulse/native"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR}"
    echo "PULSE_SERVER=${PULSE_SERVER}"
  } >> "${GITHUB_ENV}"
fi

cat > "${HOME}/.asoundrc" <<'EOF'
pcm.!default {
    type pulse
}

ctl.!default {
    type pulse
}
EOF

pulseaudio --check 2>/dev/null || pulseaudio --daemonize=yes --exit-idle-time=-1

for _ in $(seq 1 30); do
  if pactl info >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

pactl info >/dev/null

if ! pactl list short sinks | awk '{print $2}' | grep -qx "phoebe_null"; then
  pactl load-module module-null-sink \
    sink_name=phoebe_null \
    sink_properties=device.description=PhoebeNullSink >/dev/null
fi

pactl set-default-sink phoebe_null

# Null sinks stay idle until a stream plays; monitor capture then times out.
# Prefer a Pulse loopback feed so JavaSound playback is not blocked by ffmpeg.
start_null_sink_feed() {
  if ! pactl list short sources | awk '{print $2}' | grep -qx "phoebe_silent"; then
    pactl load-module module-null-source \
      source_name=phoebe_silent \
      source_properties=device.description=PhoebeSilentSource >/dev/null || return 1
  fi
  if ! pactl list short modules | grep -Fq "source=phoebe_silent.monitor"; then
    pactl load-module module-loopback \
      source=phoebe_silent.monitor \
      sink=phoebe_null \
      latency_msec=50 \
      adjust_time=1 >/dev/null || return 1
  fi
}

if ! start_null_sink_feed; then
  echo "Pulse loopback feed unavailable; falling back to ffmpeg anullsrc"
  if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -nostdin -hide_banner -loglevel error \
      -f lavfi -i anullsrc=r=22050:cl=mono \
      -f pulse phoebe_null &
  fi
fi

echo "PulseAudio default sink:"
pactl info | sed -n '/Default Sink/p'
pactl list short sinks
pactl list short sources
pactl list short modules | grep -E 'null-sink|null-source|loopback' || true
