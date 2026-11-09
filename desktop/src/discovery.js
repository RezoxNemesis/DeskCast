'use strict';
const dgram = require('dgram');
const { EventEmitter } = require('events');
const { BEACON_PORT, PORT } = require('./protocol');

/** Listens for the phones' UDP beacons ({app:"deskcast", name, id, port}) on port 27185. */
class Discovery extends EventEmitter {
  constructor({ port = BEACON_PORT, expireMs = 4000 } = {}) {
    super();
    this.port = port;
    this.expireMs = expireMs;
    this.phones = new Map();
  }

  start() {
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sock = sock;
    sock.on('message', (msg, rinfo) => this._onMessage(msg, rinfo));
    sock.on('error', (e) => {
      this.emit('error', e);
    });
    sock.bind(this.port, () => {
      try {
        sock.setBroadcast(true);
      } catch (_) {}
    });
    this.timer = setInterval(() => this._expire(), 1000);
    return this;
  }

  stop() {
    clearInterval(this.timer);
    try {
      this.sock && this.sock.close();
    } catch (_) {}
  }

  list() {
    return [...this.phones.values()];
  }

  _onMessage(msg, rinfo) {
    let j;
    try {
      j = JSON.parse(msg.toString('utf8'));
    } catch (_) {
      return;
    }
    if (!j || j.app !== 'deskcast' || !j.id) return;
    const prev = this.phones.get(j.id);
    const phone = {
      id: String(j.id),
      name: String(j.name || 'Phone').slice(0, 80),
      host: rinfo.address,
      port: Number(j.port) || PORT,
      lastSeen: Date.now(),
    };
    this.phones.set(phone.id, phone);
    if (!prev || prev.host !== phone.host || prev.name !== phone.name) this.emit('change', this.list());
    this.emit('beacon', phone);
  }

  _expire() {
    const now = Date.now();
    let changed = false;
    for (const [id, p] of this.phones) {
      if (now - p.lastSeen > this.expireMs) {
        this.phones.delete(id);
        changed = true;
      }
    }
    if (changed) this.emit('change', this.list());
  }
}

module.exports = { Discovery };
