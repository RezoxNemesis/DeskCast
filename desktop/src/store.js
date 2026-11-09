'use strict';
const fs = require('fs');
const path = require('path');

/** Tiny JSON store: paired phones (tokens) and settings, in the app's userData folder. */
class Store {
  constructor(dir) {
    this.file = path.join(dir, 'deskcast-mirror.json');
    this.data = { phones: {}, settings: {} };
    try {
      const d = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      if (d && typeof d === 'object') this.data = { phones: d.phones || {}, settings: d.settings || {} };
    } catch (_) {}
  }

  _save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 });
      fs.renameSync(tmp, this.file);
    } catch (e) {
      console.error('store: could not save', e.message);
    }
  }

  // ---- paired phones ----
  getToken(id) {
    const p = this.data.phones[id];
    return p ? p.token : null;
  }

  saveToken(id, token, name) {
    this.data.phones[id] = { ...(this.data.phones[id] || {}), token, name, pairedAt: Date.now() };
    this._save();
  }

  forgetToken(id) {
    delete this.data.phones[id];
    this._save();
  }

  isPaired(id) {
    return !!this.getToken(id);
  }

  remember(id, fields) {
    if (!this.data.phones[id]) return;
    Object.assign(this.data.phones[id], fields);
    this._save();
  }

  phone(id) {
    return this.data.phones[id] || null;
  }

  pairedPhones() {
    return Object.entries(this.data.phones).map(([id, p]) => ({ id, name: p.name, lastHost: p.lastHost }));
  }

  /** Adapter for PhoneLink. */
  tokens() {
    return {
      get: (id) => this.getToken(id),
      save: (id, token, name) => this.saveToken(id, token, name),
      forget: (id) => this.forgetToken(id),
    };
  }

  // ---- settings ----
  get(key, def) {
    return key in this.data.settings ? this.data.settings[key] : def;
  }

  set(key, value) {
    this.data.settings[key] = value;
    this._save();
  }
}

module.exports = { Store };
