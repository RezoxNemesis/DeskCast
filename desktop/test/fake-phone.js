#!/usr/bin/env node
'use strict';
// A fake DeskCast phone: speaks the "Mirror to PC" protocol exactly like MirrorLink.kt.
// Used by the unit tests (as a module) and for manual testing of the desktop app:
//
//   node test/fake-phone.js [--code 123456] [--rotate-every 6] [--fps 30] [--no-beacon]
//
// With ffmpeg installed it streams a real H.264 test pattern (portrait ↔ landscape) and a tone.

const net = require('net');
const dgram = require('dgram');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');
const { EventEmitter } = require('events');
const P = require('../src/protocol');

class FakePhone extends EventEmitter {
  constructor({ port = 0, code = '123456', name = 'Fake Phone', id = 'fake-phone-1' } = {}) {
    super();
    this.port = port;
    this.code = code;
    this.name = name;
    this.id = id;
    this.trusted = new Map(); // token -> pcName
    this.clients = new Set();
    this.videoFormat = null;
    this.videoConfig = null;
    this.audioFormat = null;
  }

  start() {
    return new Promise((resolve, reject) => {
      this.server = net.createServer((s) => this._accept(s));
      this.server.on('error', reject);
      this.server.listen(this.port, () => {
        this.port = this.server.address().port;
        resolve(this.port);
      });
    });
  }

  stop() {
    for (const c of this.clients) c.socket.destroy();
    this.clients.clear();
    if (this.server) this.server.close();
    if (this.beacon) {
      clearInterval(this.beaconTimer);
      this.beacon.close();
    }
  }

  get authedCount() {
    return [...this.clients].filter((c) => c.authed).length;
  }

  _accept(socket) {
    socket.setNoDelay(true);
    const c = { socket, authed: false, needKey: true, pcName: 'PC' };
    this.clients.add(c);
    socket.write(P.encodeJson(P.T.HELLO, { app: 'deskcast', proto: P.PROTO_VERSION, name: this.name, id: this.id }));
    const parser = new P.FrameParser((p) => this._onPacket(c, p), P.MAX_PACKET);
    socket.on('data', (d) => {
      try {
        parser.push(d);
      } catch (e) {
        socket.destroy();
      }
    });
    socket.on('error', () => {});
    socket.on('close', () => {
      this.clients.delete(c);
      this.emit('client-closed', c);
    });
  }

  _onPacket(c, p) {
    switch (p.type) {
      case P.T.AUTH: {
        const j = p.json || {};
        c.pcName = j.pcName || 'PC';
        if (j.token && !j.code) {
          if (!this.trusted.has(j.token)) {
            c.socket.write(P.encodeJson(P.T.AUTH_FAIL, { reason: 'token' }));
            return;
          }
          return this._acceptAuth(c, null);
        }
        if (String(j.code) === this.code) {
          const token = crypto.randomBytes(32).toString('hex');
          this.trusted.set(token, c.pcName);
          return this._acceptAuth(c, token);
        }
        c.socket.end(P.encodeJson(P.T.AUTH_FAIL, { reason: 'code' }));
        this.emit('auth-failed', c);
        return;
      }
      case P.T.PING:
        c.socket.write(P.encodeJson(P.T.PONG, { now: P.nowUs() }, p.pts));
        return;
      case P.T.KEYFRAME:
        c.needKey = true;
        this.emit('keyframe-request', c);
        return;
      case P.T.BYE:
        c.socket.end();
        return;
      default:
    }
  }

  _acceptAuth(c, token) {
    const ok = { name: this.name, id: this.id };
    if (token) ok.token = token;
    c.socket.write(P.encodeJson(P.T.AUTH_OK, ok));
    if (this.audioFormat) c.socket.write(this.audioFormat);
    if (this.videoFormat) c.socket.write(this.videoFormat);
    if (this.videoConfig) c.socket.write(this.videoConfig);
    c.authed = true;
    c.needKey = true;
    this.emit('authenticated', c);
  }

  _toAuthed(buf, isVideo = false, key = false) {
    for (const c of this.clients) {
      if (!c.authed) continue;
      if (isVideo) {
        if (c.needKey && !key) continue;
        if (key) c.needKey = false;
      }
      c.socket.write(buf);
    }
  }

  setVideoFormat(width, height) {
    this.videoFormat = P.encodeJson(P.T.VIDEO_FORMAT, {
      codec: 'h264',
      width,
      height,
      rotation: width > height ? 90 : 0,
      orientation: width >= height ? 'landscape' : 'portrait',
    });
    this.videoConfig = null;
    for (const c of this.clients) c.needKey = true;
    this._toAuthed(this.videoFormat);
  }

  sendConfig(buf, pts = P.nowUs()) {
    this.videoConfig = P.encodeFrame(P.T.VIDEO, pts, buf, P.VF_CONFIG);
    this._toAuthed(this.videoConfig);
  }

  sendFrame(buf, pts, key) {
    this._toAuthed(P.encodeFrame(P.T.VIDEO, pts, buf, key ? P.VF_KEY : 0), true, key);
  }

  setAudioFormat(sampleRate = 48000, channels = 2) {
    this.audioFormat = P.encodeJson(P.T.AUDIO_FORMAT, { codec: 'pcm_s16le', sampleRate, channels });
    this._toAuthed(this.audioFormat);
  }

  sendAudio(buf, pts = P.nowUs()) {
    this._toAuthed(P.encodeFrame(P.T.AUDIO, pts, buf));
  }

  startBeacon(targets = ['255.255.255.255', '127.0.0.1']) {
    const s = dgram.createSocket('udp4');
    this.beacon = s;
    s.bind(() => s.setBroadcast(true));
    const msg = Buffer.from(JSON.stringify({ app: 'deskcast', proto: 1, name: this.name, id: this.id, port: this.port }));
    this.beaconTimer = setInterval(() => {
      for (const t of targets) s.send(msg, P.BEACON_PORT, t, () => {});
    }, 1000);
  }
}

/** Splits a live Annex-B byte stream (with AUD NALs) into access units like MediaCodec would. */
class AccessUnitSplitter {
  constructor(onConfig, onFrame) {
    this.onConfig = onConfig;
    this.onFrame = onFrame;
    this.buf = Buffer.alloc(0);
    this.au = [];
    this.sps = null;
    this.pps = null;
    this.lastConfig = null;
  }

  push(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    // Find all start codes; every NAL except the last (maybe incomplete) is processed.
    const starts = [];
    for (let i = 0; i + 3 < this.buf.length; i++) {
      if (this.buf[i] === 0 && this.buf[i + 1] === 0 && this.buf[i + 2] === 1) {
        starts.push(i);
        i += 2;
      }
    }
    if (starts.length < 2) return;
    for (let k = 0; k < starts.length - 1; k++) {
      this._nal(this.buf.subarray(starts[k] + 3, starts[k + 1]));
    }
    this.buf = this.buf.subarray(starts[starts.length - 1]);
  }

  _nal(nal) {
    // Trim a trailing zero that belongs to a 4-byte start code.
    let end = nal.length;
    while (end > 0 && nal[end - 1] === 0) end--;
    nal = nal.subarray(0, end);
    if (!nal.length) return;
    const t = nal[0] & 0x1f;
    const sc = Buffer.from([0, 0, 0, 1]);
    if (t === 9) return this._flush();
    if (t === 7) {
      this.sps = Buffer.concat([sc, nal]);
      return;
    }
    if (t === 8) {
      this.pps = Buffer.concat([sc, nal]);
      const cfg = Buffer.concat([this.sps || Buffer.alloc(0), this.pps]);
      if (!this.lastConfig || !cfg.equals(this.lastConfig)) {
        this.lastConfig = cfg;
        this.onConfig(cfg);
      }
      return;
    }
    this.au.push(Buffer.concat([sc, nal]));
  }

  _flush() {
    if (!this.au.length) return;
    const key = this.au.some((n) => (n[4] & 0x1f) === 5);
    this.onFrame(Buffer.concat(this.au), key);
    this.au = [];
  }
}

function hasFfmpeg() {
  try {
    return spawnSync('ffmpeg', ['-hide_banner', '-version'], { stdio: 'ignore' }).status === 0;
  } catch (_) {
    return false;
  }
}

/** Streams an ffmpeg test pattern of the given size into the fake phone. Returns a stop function. */
function streamTestPattern(phone, { width, height, fps = 30 } = {}) {
  phone.setVideoFormat(width, height);
  const ff = spawn(
    'ffmpeg',
    [
      '-loglevel', 'error',
      '-re',
      '-f', 'lavfi', '-i', `testsrc2=size=${width}x${height}:rate=${fps}`,
      '-vf', `drawtext=text='${width}x${height} %{pts\\:hms}':x=20:y=20:fontsize=${Math.round(Math.min(width, height) / 14)}:fontcolor=white:box=1:boxcolor=black@0.5`,
      '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
      '-g', String(fps * 2), '-bf', '0',
      '-bsf:v', 'h264_metadata=aud=insert',
      '-f', 'h264', '-',
    ],
    { stdio: ['ignore', 'pipe', 'inherit'] },
  );
  const splitter = new AccessUnitSplitter(
    (cfg) => phone.sendConfig(cfg),
    (au, key) => phone.sendFrame(au, P.nowUs(), key),
  );
  ff.stdout.on('data', (d) => splitter.push(d));
  ff.on('error', (e) => console.error('ffmpeg:', e.message));
  return () => ff.kill('SIGKILL');
}

function startTone(phone, freq = 440) {
  phone.setAudioFormat(48000, 2);
  let phase = 0;
  const frames = 480; // 10 ms
  const t0 = Date.now();
  let sent = 0;
  const timer = setInterval(() => {
    // Keep real time: send as many 10 ms chunks as are due.
    const due = Math.floor((Date.now() - t0) / 10);
    while (sent < due) {
      const b = Buffer.alloc(frames * 4);
      for (let i = 0; i < frames; i++) {
        const v = Math.round(Math.sin(phase) * 0.2 * 32767);
        phase += (2 * Math.PI * freq) / 48000;
        b.writeInt16LE(v, i * 4);
        b.writeInt16LE(v, i * 4 + 2);
      }
      phone.sendAudio(b, P.nowUs() - 10000);
      sent++;
    }
  }, 5);
  return () => clearInterval(timer);
}

module.exports = { FakePhone, AccessUnitSplitter, hasFfmpeg, streamTestPattern, startTone };

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------
if (require.main === module) {
  const argv = process.argv.slice(2);
  const opt = (name, def) => {
    const i = argv.indexOf('--' + name);
    return i >= 0 ? argv[i + 1] : def;
  };
  const code = opt('code', '123456');
  const rotateEvery = Number(opt('rotate-every', 6));
  const fps = Number(opt('fps', 30));
  const port = Number(opt('port', P.PORT));
  (async () => {
    const phone = new FakePhone({ port, code, name: 'Fake Phone (test)' });
    await phone.start();
    if (!argv.includes('--no-beacon')) phone.startBeacon();
    console.log(`fake phone on port ${phone.port}, pairing code ${code}`);
    phone.on('authenticated', (c) => console.log(`PC "${c.pcName}" connected`));
    phone.on('auth-failed', () => console.log('wrong code'));
    if (!hasFfmpeg()) {
      console.log('ffmpeg not found: protocol only, no video');
      return;
    }
    startTone(phone);
    const sizes = [
      [540, 960],
      [960, 540],
    ];
    let i = 0;
    let stop = streamTestPattern(phone, { width: sizes[0][0], height: sizes[0][1], fps });
    if (rotateEvery > 0) {
      setInterval(() => {
        stop();
        i = (i + 1) % sizes.length;
        console.log(`rotate → ${sizes[i][0]}x${sizes[i][1]}`);
        stop = streamTestPattern(phone, { width: sizes[i][0], height: sizes[i][1], fps });
      }, rotateEvery * 1000);
    }
  })();
}
