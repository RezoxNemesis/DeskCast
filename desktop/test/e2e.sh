#!/usr/bin/env bash
# End-to-end test: fake phone (ffmpeg H.264 + tone, rotating) → DeskCast Mirror under Xvfb.
# Checks pairing, decoding (fps), rotation following, audio buffering and token storage.
#   test/e2e.sh                                  # dev: uses node_modules electron
#   test/e2e.sh dist/linux-unpacked/deskcast-mirror   # packaged app
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${E2E_OUT:-/tmp/deskcast-e2e}
rm -rf "$OUT" && mkdir -p "$OUT"
if [ $# -gt 0 ]; then APP=("$1"); else APP=(./node_modules/.bin/electron .); fi

node test/fake-phone.js --code 314159 --rotate-every 8 --port 27184 > "$OUT/phone.log" 2>&1 &
PHONE=$!
trap 'kill $PHONE 2>/dev/null || true' EXIT
sleep 1

DESKCAST_USER_DATA="$OUT/userdata" timeout 90 xvfb-run -a -s "-screen 0 1920x1080x24" \
  "${APP[@]}" --no-sandbox --connect=127.0.0.1 --auto-code=314159 \
  --capture="$OUT/shot1.png,$OUT/shot2.png" --capture-after=5,14 > "$OUT/app.log" 2>&1 || true

node - "$OUT" <<'JS'
const fs = require('fs');
const out = process.argv[2];
const log = fs.readFileSync(out + '/app.log', 'utf8');
const stats = log.split('\n').filter((l) => l.startsWith('[stats] ')).map((l) => JSON.parse(l.slice(8)));
const fail = (m) => { console.log(log.split('\n').slice(-60).join('\n')); console.error('E2E FAIL: ' + m); process.exit(1); };
if (!stats.length) fail('no stats — never reached the mirror view');
const maxFps = Math.max(...stats.map((s) => s.fps));
const orient = new Set(stats.filter((s) => s.fps > 0 && s.format).map((s) => s.format.orientation));
const audio = Math.max(...stats.map((s) => s.audioMs || 0));
const errors = Math.max(...stats.map((s) => s.decodeErrors));
const store = JSON.parse(fs.readFileSync(out + '/userdata/deskcast-mirror.json', 'utf8'));
const token = Object.values(store.phones)[0]?.token || '';
console.log(`max fps ${maxFps}, orientations ${[...orient].join('+')}, audio buffer ${audio} ms, decode errors ${errors}, token ${token.length} hex chars`);
if (maxFps < 15) fail('video too slow / not decoding');
if (!orient.has('portrait') || !orient.has('landscape')) fail('did not follow rotation');
if (audio < 20) fail('no audio buffered');
if (errors > 0) fail('decoder errors');
if (!/^[0-9a-f]{64}$/.test(token)) fail('pairing token not saved');
for (const f of ['shot1.png', 'shot2.png']) if (!fs.existsSync(out + '/' + f)) fail('missing ' + f);
const sizes = [...log.matchAll(/\[capture\] saved \S+ window=(\d+)x(\d+)/g)].map((m) => [+m[1], +m[2]]);
console.log('window sizes at captures:', JSON.stringify(sizes));
console.log('E2E OK');
JS
