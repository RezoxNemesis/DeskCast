#!/usr/bin/env sh
# DeskCast PC viewer (macOS / Linux). Needs adb and ffplay (ffmpeg) installed.
# Usage:  ./deskcast-view.sh              -> over the USB cable
#         ./deskcast-view.sh 192.168.x.x  -> over Wi-Fi / USB tethering
HOST="$1"
if [ -z "$HOST" ]; then
  adb forward tcp:27183 tcp:27183 || exit 1
  HOST=127.0.0.1
fi
exec ffplay -hide_banner -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 \
  -sync ext -window_title DeskCast -f h264 "tcp://$HOST:27183"
