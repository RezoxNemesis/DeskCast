'use strict';
const { app, BrowserWindow, ipcMain, screen, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');
const { PhoneLink } = require('./link');
const { Discovery } = require('./discovery');
const { Store } = require('./store');
const { PORT } = require('./protocol');

// ---------------------------------------------------------------------------------------------
// command line
// ---------------------------------------------------------------------------------------------

function arg(name) {
  const pre = `--${name}=`;
  const a = process.argv.find((x) => x.startsWith(pre));
  return a ? a.slice(pre.length) : null;
}

const SMOKE = process.argv.includes('--smoke-test'); // CI: launch, check the renderer, exit 0
// Test hooks (used by test/e2e.sh with the fake phone): connect / pair / screenshot automatically.
const AUTO_CONNECT = arg('connect');
const AUTO_CODE = arg('auto-code');
const CAPTURE = arg('capture');
const CAPTURE_AFTER = String(arg('capture-after') || '6').split(',').map(Number);

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
if (process.env.DESKCAST_USER_DATA) app.setPath('userData', process.env.DESKCAST_USER_DATA);

if (!SMOKE && !AUTO_CONNECT && !app.requestSingleInstanceLock()) {
  app.quit();
}

// ---------------------------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------------------------

const HOME_SIZE = { width: 980, height: 660 };
let win = null;
let store = null;
let discovery = null;
let link = null;
let target = null; // { host, port, id, name, via }
let reconnectTimer = null;
let retries = 0;
let pendingPairError = null;
let videoFormat = null;
let rotateLock = false;
let animTimer = null;
const userStopped = new Set();
const pcName = (() => {
  try {
    return `${os.userInfo().username}@${os.hostname()}`;
  } catch (_) {
    return os.hostname();
  }
})();

const ui = { view: 'home', phone: null, error: null, pairError: null, status: '' };

function send(channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, data);
}

function phoneList() {
  const seen = discovery ? discovery.list() : [];
  const out = seen.map((p) => ({ ...p, paired: store.isPaired(p.id), online: true }));
  for (const p of store.pairedPhones()) {
    if (!out.some((x) => x.id === p.id) && p.lastHost) {
      out.push({ id: p.id, name: p.name, host: p.lastHost, port: PORT, paired: true, online: false });
    }
  }
  return out;
}

function publicUi() {
  return {
    ...ui,
    phones: phoneList(),
    alwaysOnTop: win ? win.isAlwaysOnTop() : false,
    fullscreen: win ? win.isFullScreen() : false,
    rotateLock,
    volume: store.get('volume', 1),
    muted: store.get('muted', false),
    pcName,
    smoke: SMOKE,
  };
}

function setUi(patch) {
  Object.assign(ui, patch);
  send('ui', publicUi());
}

// ---------------------------------------------------------------------------------------------
// connection
// ---------------------------------------------------------------------------------------------

function reasonText(r) {
  switch (r) {
    case 'ECONNREFUSED':
      return 'the phone is not mirroring (tap “Mirror to PC” in DeskCast)';
    case 'EHOSTUNREACH':
    case 'ENETUNREACH':
      return 'the phone cannot be reached — same Wi‑Fi?';
    case 'timeout':
      return 'no answer from the phone';
    case 'bye':
      return 'the phone stopped mirroring';
    case 'closed':
    case 'ECONNRESET':
      return 'connection lost';
    default:
      return r;
  }
}

function connectTo(t) {
  clearTimeout(reconnectTimer);
  if (link) {
    link.removeAllListeners();
    link.on('error', () => {});
    link.close('switch');
    link = null;
  }
  target = { port: PORT, via: 'wifi', ...t };
  if (target.id) userStopped.delete(target.id);
  setUi({
    view: ui.view === 'pair' ? 'pair' : 'connecting',
    phone: { name: target.name || target.host, id: target.id, host: target.host, via: target.via },
    error: null,
    status: retries > 0 ? ui.status : `Connecting to ${target.name || target.host}…`,
  });

  const l = new PhoneLink({ host: target.host, port: target.port, pcName, tokens: store.tokens() });
  link = l;
  l.on('hello', (p) => {
    target.id = p.id;
    target.name = p.name;
    setUi({ phone: { ...ui.phone, name: p.name, id: p.id } });
  });
  l.on('need-code', (p, reason) => {
    const err = reason === 'token' ? 'This phone no longer knows this PC. Type the code shown on the phone.' : pendingPairError;
    pendingPairError = null;
    setUi({ view: 'pair', pairError: err, status: '' });
    if (AUTO_CODE) setTimeout(() => l.submitCode(AUTO_CODE), 300);
  });
  l.on('auth-failed', (reason) => {
    pendingPairError =
      reason === 'locked' ? 'Too many wrong codes — the phone now shows a new code.' : 'That code is not right. Try again.';
  });
  l.on('authenticated', (p) => {
    retries = 0;
    store.remember(p.id, { lastHost: target.host, name: p.name });
    setUi({ view: 'mirror', pairError: null, error: null, status: '', phone: { ...ui.phone, name: p.name, id: p.id } });
    send('session', { name: p.name });
  });
  l.on('video-format', (f) => {
    videoFormat = f;
    send('video-format', f);
    fitToVideo(f);
  });
  l.on('video', (p) => send('video', { pts: p.pts, flags: p.flags, data: p.data }));
  l.on('audio-format', (f) => send('audio-format', f));
  l.on('audio', (p) => send('audio', { pts: p.pts, data: p.data }));
  l.on('clock', (c) => send('clock', c));
  l.on('close', (reason) => onLinkClosed(l, reason));
  l.connect();
}

function onLinkClosed(l, reason) {
  if (l !== link) return;
  link = null;
  send('stopped', { reason });
  if (reason === 'user' || !target) return;
  if (pendingPairError) {
    // Wrong code: the phone hung up. Connect again so the user can retype.
    reconnect(500);
    return;
  }
  if (ui.view === 'pair') {
    reconnect(1000);
    return;
  }
  retries++;
  const delay = Math.min(5000, 600 * 2 ** Math.min(retries, 4));
  setUi({ view: 'connecting', status: `Reconnecting — ${reasonText(reason)}` });
  reconnect(delay);
}

function reconnect(delay) {
  clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => {
    if (!target || link) return;
    // The phone may have a new address (DHCP) — the beacon tells us.
    const seen = target.id && discovery ? discovery.phones.get(target.id) : null;
    if (seen && target.via !== 'usb') {
      target.host = seen.host;
      target.port = seen.port;
    }
    connectTo(target);
  }, delay);
}

function disconnect() {
  clearTimeout(reconnectTimer);
  if (target && target.id) userStopped.add(target.id);
  target = null;
  retries = 0;
  pendingPairError = null;
  if (link) {
    const l = link;
    link = null;
    l.close('user');
  }
  videoFormat = null;
  send('stopped', { reason: 'user' });
  setUi({ view: 'home', phone: null, status: '', pairError: null });
  if (win && !win.isDestroyed() && !win.isFullScreen()) {
    win.setAspectRatio(0);
    const b = win.getBounds();
    const cx = b.x + b.width / 2;
    const cy = b.y + b.height / 2;
    animateBounds(clampToWorkArea({ x: Math.round(cx - HOME_SIZE.width / 2), y: Math.round(cy - HOME_SIZE.height / 2), ...HOME_SIZE }));
  }
}

function findAdb() {
  const candidates = ['adb', path.join(os.homedir(), 'Android/Sdk/platform-tools/adb'), '/usr/lib/android-sdk/platform-tools/adb'];
  return candidates.find((c) => c === 'adb' || fs.existsSync(c));
}

function connectUsb() {
  setUi({ error: null, status: 'Setting up USB (adb forward)…' });
  const tryAdb = (bins) => {
    const bin = bins.shift();
    execFile(bin, ['forward', `tcp:${PORT}`, `tcp:${PORT}`], { timeout: 10000 }, (err, _out, stderr) => {
      if (err && err.code === 'ENOENT' && bins.length) return tryAdb(bins);
      if (err) {
        const msg =
          err.code === 'ENOENT'
            ? 'adb is not installed. Install it with “sudo apt install adb”, turn on USB debugging on the phone, then try again.'
            : `adb: ${(stderr || err.message).trim()} — is the phone plugged in with USB debugging allowed?`;
        setUi({ error: msg, status: '' });
        return;
      }
      connectTo({ host: '127.0.0.1', port: PORT, via: 'usb', name: 'Phone over USB' });
    });
  };
  const list = [findAdb(), path.join(os.homedir(), 'Android/Sdk/platform-tools/adb'), '/usr/lib/android-sdk/platform-tools/adb'];
  tryAdb([...new Set(list.filter(Boolean))]);
}

function connectManual(text) {
  const s = String(text || '').trim();
  const m = s.match(/^\[?([0-9a-zA-Z.\-:]+?)\]?(?::(\d{1,5}))?$/);
  if (!m || !m[1]) {
    setUi({ error: 'Type the phone address shown in DeskCast, e.g. 192.168.1.23' });
    return;
  }
  connectTo({ host: m[1], port: m[2] ? Number(m[2]) : PORT, via: 'wifi' });
}

// ---------------------------------------------------------------------------------------------
// window sizing
// ---------------------------------------------------------------------------------------------

function clampToWorkArea(b) {
  const disp = screen.getDisplayMatching(win.getBounds());
  const wa = disp.workArea;
  const width = Math.min(b.width, wa.width);
  const height = Math.min(b.height, wa.height);
  const x = Math.min(Math.max(b.x, wa.x), wa.x + wa.width - width);
  const y = Math.min(Math.max(b.y, wa.y), wa.y + wa.height - height);
  return { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) };
}

function animateBounds(to) {
  clearInterval(animTimer);
  const from = win.getBounds();
  const steps = 12;
  let i = 0;
  animTimer = setInterval(() => {
    if (!win || win.isDestroyed()) return clearInterval(animTimer);
    i++;
    const t = i / steps;
    const e = 1 - Math.pow(1 - t, 3);
    const lerp = (a, b) => Math.round(a + (b - a) * e);
    win.setBounds({ x: lerp(from.x, to.x), y: lerp(from.y, to.y), width: lerp(from.width, to.width), height: lerp(from.height, to.height) });
    if (i >= steps) {
      clearInterval(animTimer);
      win.setBounds(to);
    }
  }, 15);
}

let fittedOnce = false;
function fitToVideo(f) {
  if (!win || win.isDestroyed() || !f || !f.width || !f.height) return;
  if (rotateLock || win.isFullScreen() || win.isMaximized()) return;
  const aspect = f.width / f.height;
  const wa = screen.getDisplayMatching(win.getBounds()).workArea;
  const cur = win.getBounds();
  let long;
  if (!fittedOnce || ui.view !== 'mirror') {
    // First picture: a comfortable default size.
    long = aspect < 1 ? wa.height * 0.86 : Math.min(wa.width * 0.72, wa.height * 0.86 * aspect);
  } else {
    // Rotation: keep the size the user chose, just turn the window.
    long = Math.max(cur.width, cur.height);
  }
  fittedOnce = true;
  let w = aspect < 1 ? long * aspect : long;
  let h = aspect < 1 ? long : long / aspect;
  const k = Math.min(1, (wa.width * 0.96) / w, (wa.height * 0.96) / h);
  w *= k;
  h *= k;
  const cx = cur.x + cur.width / 2;
  const cy = cur.y + cur.height / 2;
  win.setAspectRatio(0);
  animateBounds(clampToWorkArea({ x: cx - w / 2, y: cy - h / 2, width: w, height: h }));
  setTimeout(() => {
    if (win && !win.isDestroyed() && !rotateLock) win.setAspectRatio(aspect);
  }, 260);
}

// ---------------------------------------------------------------------------------------------
// window + ipc
// ---------------------------------------------------------------------------------------------

function createWindow() {
  win = new BrowserWindow({
    ...HOME_SIZE,
    minWidth: 240,
    minHeight: 240,
    frame: false,
    show: false,
    backgroundColor: '#07090d',
    title: 'DeskCast Mirror',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => {
    win.show();
    if (store.get('alwaysOnTop', false)) win.setAlwaysOnTop(true, 'floating');
  });
  for (const ev of ['enter-full-screen', 'leave-full-screen', 'maximize', 'unmaximize']) {
    win.on(ev, () => send('ui', publicUi()));
  }
  win.on('leave-full-screen', () => videoFormat && fitToVideo(videoFormat));
  win.webContents.on('render-process-gone', (_e, d) => {
    console.error('renderer gone', d.reason);
    if (SMOKE) {
      console.log('DESKCAST_SMOKE_FAIL renderer ' + d.reason);
      app.exit(2);
    }
  });
  win.webContents.on('console-message', (e, _level, message) => {
    const msg = e && e.message !== undefined ? e.message : message;
    if (SMOKE || CAPTURE || process.env.DESKCAST_DEBUG) console.log('[renderer]', msg);
  });
  win.on('closed', () => {
    win = null;
  });
}

function setupIpc() {
  ipcMain.on('ready', () => {
    send('ui', publicUi());
    if (videoFormat) send('video-format', videoFormat);
  });
  ipcMain.on('connect', (_e, t) => {
    if (!t || !t.host) return;
    retries = 0;
    connectTo({ host: String(t.host), port: Number(t.port) || PORT, id: t.id, name: t.name, via: 'wifi' });
  });
  ipcMain.on('connect-manual', (_e, text) => {
    retries = 0;
    connectManual(text);
  });
  ipcMain.on('connect-usb', () => {
    retries = 0;
    connectUsb();
  });
  ipcMain.on('submit-code', (_e, code) => {
    if (link) {
      setUi({ pairError: null, status: 'Checking the code…' });
      link.submitCode(code);
    }
  });
  ipcMain.on('cancel', () => disconnect());
  ipcMain.on('disconnect', () => disconnect());
  ipcMain.on('keyframe', () => link && link.requestKeyframe());
  ipcMain.on('toggle-fullscreen', () => win && win.setFullScreen(!win.isFullScreen()));
  ipcMain.on('exit-fullscreen', () => win && win.isFullScreen() && win.setFullScreen(false));
  ipcMain.on('set-always-on-top', (_e, v) => {
    if (!win) return;
    win.setAlwaysOnTop(!!v, 'floating');
    store.set('alwaysOnTop', !!v);
    send('ui', publicUi());
  });
  ipcMain.on('set-rotate-lock', (_e, v) => {
    rotateLock = !!v;
    if (rotateLock) win && win.setAspectRatio(0);
    else if (videoFormat) fitToVideo(videoFormat);
    send('ui', publicUi());
  });
  ipcMain.on('set-audio', (_e, a) => {
    if (a && typeof a.volume === 'number') store.set('volume', Math.max(0, Math.min(1, a.volume)));
    if (a && typeof a.muted === 'boolean') store.set('muted', a.muted);
  });
  ipcMain.on('forget-phone', (_e, id) => {
    store.forgetToken(String(id));
    send('ui', publicUi());
  });
  ipcMain.on('win-minimize', () => win && win.minimize());
  ipcMain.on('win-maximize', () => win && (win.isMaximized() ? win.unmaximize() : win.maximize()));
  ipcMain.on('win-close', () => win && win.close());
  ipcMain.handle('save-screenshot', async (_e, bytes) => {
    const dir = path.join(app.getPath('pictures'), 'DeskCast');
    fs.mkdirSync(dir, { recursive: true });
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const name = `DeskCast-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}.png`;
    const file = path.join(dir, name);
    fs.writeFileSync(file, Buffer.from(bytes));
    return file;
  });
  ipcMain.on('show-file', (_e, file) => shell.showItemInFolder(String(file)));
  ipcMain.on('smoke', (_e, info) => {
    if (!SMOKE) return;
    console.log('DESKCAST_SMOKE_OK ' + JSON.stringify(info));
    // Keep running a few seconds to prove it doesn't crash, then quit cleanly.
    setTimeout(() => app.exit(0), 4000);
  });
  ipcMain.on('stats', (_e, s) => {
    if (CAPTURE || process.env.DESKCAST_DEBUG) console.log('[stats]', JSON.stringify(s));
  });
}

function startDiscovery() {
  discovery = new Discovery().start();
  discovery.on('error', (e) => console.error('discovery:', e.message));
  discovery.on('change', () => send('ui', publicUi()));
  discovery.on('beacon', (p) => {
    // Paired phone shows up: connect by itself (unless the user disconnected it).
    if (!link && !target && ui.view === 'home' && store.isPaired(p.id) && !userStopped.has(p.id) && !SMOKE) {
      connectTo({ host: p.host, port: p.port, id: p.id, name: p.name, via: 'wifi' });
    } else if (!link && target && target.id === p.id && ui.view === 'connecting') {
      // We were waiting for this phone: try now instead of waiting for the backoff.
      reconnect(100);
    }
  });
}

app.whenReady().then(() => {
  store = new Store(app.getPath('userData'));
  setupIpc();
  createWindow();
  startDiscovery();
  if (SMOKE) {
    setTimeout(() => {
      console.log('DESKCAST_SMOKE_FAIL timeout');
      app.exit(1);
    }, 40000);
  }
  if (AUTO_CONNECT) {
    win.webContents.once('did-finish-load', () => setTimeout(() => connectManual(AUTO_CONNECT), 500));
  }
  if (CAPTURE) {
    const files = CAPTURE.split(',');
    files.forEach((file, i) => {
      setTimeout(async () => {
        try {
          if (process.argv.includes('--show-toolbar')) {
            await win.webContents.executeJavaScript("document.getElementById('toolbar').classList.remove('hidden')");
            await new Promise((r) => setTimeout(r, 400));
          }
          const img = await win.webContents.capturePage();
          fs.writeFileSync(file, img.toPNG());
          const b = win.getBounds();
          console.log(`[capture] saved ${file} window=${b.width}x${b.height} view=${ui.view}`);
        } catch (e) {
          console.error('[capture] failed', e);
        }
        if (i === files.length - 1) setTimeout(() => app.quit(), 300);
      }, (CAPTURE_AFTER[i] || CAPTURE_AFTER[0] * (i + 1)) * 1000);
    });
  }
});

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.focus();
  }
});

app.on('before-quit', () => {
  if (link) link.close('user');
  if (discovery) discovery.stop();
});

app.on('window-all-closed', () => app.quit());
