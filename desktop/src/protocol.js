'use strict';
// DeskCast "Mirror to PC" wire protocol (phone side: app/src/main/java/com/rezox/deskcast/MirrorLink.kt).
//
// Every packet:  type u8 | length u32 BE | ptsUs i64 BE | payload[length]
// VIDEO payload: flags u8 (1 = SPS/PPS config, 2 = keyframe) followed by an Annex-B H.264 access unit.

const PORT = 27184;
const BEACON_PORT = 27185;
const PROTO_VERSION = 1;
const HEADER = 13;
const MAX_PACKET = 16 * 1024 * 1024;

const T = Object.freeze({
  HELLO: 1,
  AUTH: 2,
  AUTH_OK: 3,
  AUTH_FAIL: 4,
  VIDEO_FORMAT: 16,
  VIDEO: 17,
  AUDIO_FORMAT: 18,
  AUDIO: 19,
  PING: 32,
  PONG: 33,
  KEYFRAME: 34,
  BYE: 35,
});

const VF_CONFIG = 1;
const VF_KEY = 2;

const JSON_TYPES = new Set([T.HELLO, T.AUTH, T.AUTH_OK, T.AUTH_FAIL, T.VIDEO_FORMAT, T.AUDIO_FORMAT, T.PONG, T.BYE]);

function encodeFrame(type, ptsUs, payload, flags) {
  const body = !payload ? Buffer.alloc(0) : Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const extra = flags === undefined || flags === null ? 0 : 1;
  const b = Buffer.allocUnsafe(HEADER + extra + body.length);
  b.writeUInt8(type, 0);
  b.writeUInt32BE(body.length + extra, 1);
  b.writeBigInt64BE(BigInt(Math.round(ptsUs || 0)), 5);
  if (extra) b.writeUInt8(flags & 0xff, HEADER);
  body.copy(b, HEADER + extra);
  return b;
}

function encodeJson(type, obj, ptsUs = 0) {
  return encodeFrame(type, ptsUs, Buffer.from(JSON.stringify(obj || {}), 'utf8'));
}

/** Incremental parser: feed it socket chunks, get whole packets back. */
class FrameParser {
  constructor(onPacket, maxLen = MAX_PACKET) {
    this.onPacket = onPacket;
    this.maxLen = maxLen;
    this.chunks = [];
    this.size = 0;
  }

  push(chunk) {
    if (chunk.length === 0) return;
    this.chunks.push(chunk);
    this.size += chunk.length;
    for (;;) {
      if (this.size < HEADER) return;
      const head = this._peek(HEADER);
      const len = head.readUInt32BE(1);
      if (len > this.maxLen) throw new Error(`packet too large (${len} bytes)`);
      if (this.size < HEADER + len) return;
      const all = this._take(HEADER + len);
      const type = all.readUInt8(0);
      const pts = Number(all.readBigInt64BE(5));
      this.onPacket(decodePacket(type, pts, all.subarray(HEADER)));
    }
  }

  _peek(n) {
    if (this.chunks[0].length >= n) return this.chunks[0];
    return Buffer.concat(this.chunks, Math.min(this.size, Math.max(n, 64)));
  }

  _take(n) {
    const first = this.chunks[0];
    let out;
    if (first.length >= n) {
      out = first.subarray(0, n);
      if (first.length === n) this.chunks.shift();
      else this.chunks[0] = first.subarray(n);
    } else {
      out = Buffer.allocUnsafe(n);
      let off = 0;
      while (off < n) {
        const c = this.chunks[0];
        const k = Math.min(c.length, n - off);
        c.copy(out, off, 0, k);
        off += k;
        if (k === c.length) this.chunks.shift();
        else this.chunks[0] = c.subarray(k);
      }
    }
    this.size -= n;
    return out;
  }
}

function decodePacket(type, pts, payload) {
  const p = { type, pts };
  if (type === T.VIDEO) {
    p.flags = payload.length ? payload[0] : 0;
    p.data = payload.subarray(1);
  } else if (type === T.AUDIO) {
    p.data = payload;
  } else if (JSON_TYPES.has(type)) {
    try {
      p.json = payload.length ? JSON.parse(payload.toString('utf8')) : {};
    } catch (e) {
      p.json = {};
      p.error = 'bad json';
    }
  } else {
    p.data = payload;
  }
  return p;
}

/** Splits an Annex-B buffer into NAL units (without start codes). */
function splitNals(buf) {
  const out = [];
  const n = buf.length;
  let i = 0;
  let start = -1;
  while (i + 2 < n) {
    if (buf[i] === 0 && buf[i + 1] === 0 && (buf[i + 2] === 1 || (buf[i + 2] === 0 && i + 3 < n && buf[i + 3] === 1))) {
      const sc = buf[i + 2] === 1 ? 3 : 4;
      if (start >= 0) out.push(buf.subarray(start, i));
      i += sc;
      start = i;
    } else {
      i++;
    }
  }
  if (start >= 0 && start < n) out.push(buf.subarray(start, n));
  return out.filter((x) => x.length > 0);
}

function hex2(v) {
  return v.toString(16).padStart(2, '0').toUpperCase();
}

/** WebCodecs codec string ("avc1.PPCCLL") from an SPS-containing Annex-B buffer, or null. */
function codecStringFromConfig(buf) {
  for (const nal of splitNals(buf)) {
    if ((nal[0] & 0x1f) === 7 && nal.length >= 4) {
      return `avc1.${hex2(nal[1])}${hex2(nal[2])}${hex2(nal[3])}`;
    }
  }
  return null;
}

function nowUs() {
  return Math.round((performance.timeOrigin + performance.now()) * 1000);
}

module.exports = {
  PORT,
  BEACON_PORT,
  PROTO_VERSION,
  HEADER,
  MAX_PACKET,
  T,
  VF_CONFIG,
  VF_KEY,
  encodeFrame,
  encodeJson,
  FrameParser,
  splitNals,
  codecStringFromConfig,
  nowUs,
};
