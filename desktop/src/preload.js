'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const IN = new Set(['ui', 'session', 'video-format', 'video', 'audio-format', 'audio', 'clock', 'stopped']);
const OUT = new Set([
  'ready',
  'connect',
  'connect-manual',
  'connect-usb',
  'submit-code',
  'cancel',
  'disconnect',
  'keyframe',
  'toggle-fullscreen',
  'exit-fullscreen',
  'set-always-on-top',
  'set-rotate-lock',
  'set-audio',
  'forget-phone',
  'win-minimize',
  'win-maximize',
  'win-close',
  'show-file',
  'smoke',
  'stats',
]);

contextBridge.exposeInMainWorld('deskcast', {
  on(channel, fn) {
    if (!IN.has(channel)) throw new Error('unknown channel ' + channel);
    ipcRenderer.on(channel, (_e, data) => fn(data));
  },
  send(channel, data) {
    if (!OUT.has(channel)) throw new Error('unknown channel ' + channel);
    ipcRenderer.send(channel, data);
  },
  saveScreenshot(bytes) {
    return ipcRenderer.invoke('save-screenshot', bytes);
  },
});
