'use strict';
const net = require('net');
const { EventEmitter } = require('events');
const { T, PORT, FrameParser, encodeJson, encodeFrame, nowUs } = require('./protocol');

/**
 * One connection to a phone running DeskCast "Mirror to PC".
 *
 * Events:
 *   hello(phone)                 phone said who it is ({name, id})
 *   need-code(phone, reason)     ask the user for the 6-digit code (reason: null | 'token')
 *   authenticated(phone)         streaming starts
 *   auth-failed(reason)          wrong code ('code') or too many tries ('locked'); the phone hangs up
 *   video-format(fmt)            {width, height, rotation, orientation}
 *   video(pkt)                   {pts, flags, data}
 *   audio-format(fmt)            {sampleRate, channels}
 *   audio(pkt)                   {pts, data}
 *   clock({rttUs, offsetUs})     offsetUs = phoneClock - pcClock
 *   close(reason)
 */
class PhoneLink extends EventEmitter {
  constructor(opts) {
    super();
    this.host = opts.host;
    this.port = opts.port || PORT;
    this.pcName = opts.pcName || 'PC';
    this.tokens = opts.tokens; // { get(id), save(id, token, name), forget(id) }
    this.timeoutMs = opts.timeoutMs || 6000;
    this.state = 'idle';
    this.phone = null;
    this.bytesIn = 0;
    this.closed = false;
    this.clockSamples = [];
  }

  connect() {
    this.state = 'connecting';
    const s = net.connect({ host: this.host, port: this.port });
    this.socket = s;
    s.setNoDelay(true);
    s.setKeepAlive(true, 2000);
    this.lastData = Date.now();
    const parser = new FrameParser((p) => this._onPacket(p));
    s.on('connect', () => {
      this.state = 'hello';
      s.setTimeout(0);
      this.emit('connected');
      this._ping();
      this.pingTimer = setInterval(() => this._ping(), 1000);
    });
    s.on('data', (chunk) => {
      this.bytesIn += chunk.length;
      this.lastData = Date.now();
      try {
        parser.push(chunk);
      } catch (e) {
        this.close('protocol: ' + e.message);
      }
    });
    s.on('error', (e) => this.close(e.code || e.message));
    s.on('close', () => this.close(this.state === 'closing' ? 'bye' : 'closed'));
    this.watchdog = setInterval(() => {
      if (Date.now() - this.lastData > this.timeoutMs) this.close('timeout');
    }, 1000);
    // Connecting itself must not take forever.
    s.setTimeout(this.timeoutMs, () => {
      if (this.state === 'connecting') this.close('timeout');
    });
    return this;
  }

  _send(buf) {
    if (this.closed || !this.socket || this.socket.destroyed) return;
    this.socket.write(buf);
  }

  _ping() {
    this._send(encodeFrame(T.PING, nowUs(), Buffer.alloc(0)));
  }

  submitCode(code) {
    const digits = String(code || '').replace(/\D/g, '');
    this.state = 'auth';
    this._send(encodeJson(T.AUTH, { code: digits, pcName: this.pcName }));
  }

  requestKeyframe() {
    this._send(encodeFrame(T.KEYFRAME, 0, Buffer.alloc(0)));
  }

  close(reason = 'user') {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pingTimer);
    clearInterval(this.watchdog);
    if (this.socket && !this.socket.destroyed) {
      if (reason === 'user') {
        try {
          this.socket.end(encodeJson(T.BYE, {}));
        } catch (_) {}
        setTimeout(() => this.socket.destroy(), 200);
      } else {
        this.socket.destroy();
      }
    }
    this.state = 'closed';
    this.emit('close', reason);
  }

  _onPacket(p) {
    switch (p.type) {
      case T.HELLO: {
        const j = p.json || {};
        if (j.app !== 'deskcast') return this.close('not a DeskCast phone');
        this.phone = { name: j.name || 'Phone', id: j.id || this.host, proto: j.proto };
        this.emit('hello', this.phone);
        const token = this.tokens && this.tokens.get(this.phone.id);
        if (token) {
          this.state = 'auth';
          this._send(encodeJson(T.AUTH, { token, pcName: this.pcName }));
        } else {
          this.state = 'need-code';
          this.emit('need-code', this.phone, null);
        }
        break;
      }
      case T.AUTH_FAIL: {
        const reason = (p.json && p.json.reason) || 'code';
        if (reason === 'token') {
          // The phone forgot us (Forget paired PCs). Pair again on this connection.
          if (this.tokens && this.phone) this.tokens.forget(this.phone.id);
          this.state = 'need-code';
          this.emit('need-code', this.phone, 'token');
        } else {
          this.emit('auth-failed', reason);
        }
        break;
      }
      case T.AUTH_OK: {
        const j = p.json || {};
        if (this.phone && j.name) this.phone.name = j.name;
        if (j.token && this.tokens && this.phone) this.tokens.save(this.phone.id, j.token, this.phone.name);
        this.state = 'streaming';
        this.emit('authenticated', this.phone);
        break;
      }
      case T.VIDEO_FORMAT:
        this.videoFormat = p.json;
        this.emit('video-format', p.json);
        break;
      case T.VIDEO:
        this.emit('video', p);
        break;
      case T.AUDIO_FORMAT:
        this.audioFormat = p.json;
        this.emit('audio-format', p.json);
        break;
      case T.AUDIO:
        this.emit('audio', p);
        break;
      case T.PONG: {
        const now = nowUs();
        const rtt = now - p.pts;
        const phoneNow = p.json && Number(p.json.now);
        if (!Number.isFinite(phoneNow) || rtt < 0) break;
        this.clockSamples.push({ rtt, offset: phoneNow - (p.pts + rtt / 2) });
        if (this.clockSamples.length > 12) this.clockSamples.shift();
        // The sample with the smallest round trip is the most accurate one.
        const best = this.clockSamples.reduce((a, b) => (b.rtt < a.rtt ? b : a));
        this.emit('clock', { rttUs: rtt, offsetUs: best.offset });
        break;
      }
      case T.BYE:
        this.state = 'closing';
        this.close('bye');
        break;
      default:
        break;
    }
  }
}

module.exports = { PhoneLink };
