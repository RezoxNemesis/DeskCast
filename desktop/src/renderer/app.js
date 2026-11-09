'use strict';
/* global deskcast, VideoDecoder, EncodedVideoChunk */

const $ = (id) => document.getElementById(id);
const app = $('app');
const canvas = $('screen');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });

const VF_CONFIG = 1;
const VF_KEY = 2;

let ui = { view: 'home', phones: [] };

function nowUs() {
  return Math.round((performance.timeOrigin + performance.now()) * 1000);
}

// =============================================================================================
// views
// =============================================================================================

function setView(view) {
  app.classList.remove('view-home', 'view-connecting', 'view-pair', 'view-mirror');
  app.classList.add('view-' + view);
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function renderPhones() {
  const list = ui.phones || [];
  $('empty').style.display = list.some((p) => p.online) ? 'none' : 'flex';
  $('phones').innerHTML = list
    .map(
      (p, i) => `
      <div class="phone-card glass ${p.online ? '' : 'offline'}" data-i="${i}" tabindex="0">
        <div class="phone-glyph"></div>
        <div class="phone-info">
          <div class="name">${esc(p.name)}</div>
          <div class="host">${esc(p.host)}${p.port && p.port !== 27184 ? ':' + p.port : ''}</div>
          <div class="badges">
            ${p.online ? '<span class="badge online">On Wi‑Fi</span>' : '<span class="badge">Last seen</span>'}
            ${p.paired ? '<span class="badge paired">Paired</span>' : '<span class="badge">New</span>'}
          </div>
        </div>
        ${p.paired ? `<button class="forget" data-forget="${esc(p.id)}" title="Forget this phone">Forget</button>` : ''}
      </div>`,
    )
    .join('');
}

$('phones').addEventListener('click', (e) => {
  const f = e.target.closest('[data-forget]');
  if (f) {
    e.stopPropagation();
    deskcast.send('forget-phone', f.dataset.forget);
    return;
  }
  const card = e.target.closest('.phone-card');
  if (!card) return;
  const p = ui.phones[Number(card.dataset.i)];
  if (p) deskcast.send('connect', { host: p.host, port: p.port, id: p.id, name: p.name });
});

$('phones').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') e.target.click();
});

$('manualGo').onclick = () => deskcast.send('connect-manual', $('manualHost').value);
$('manualHost').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') deskcast.send('connect-manual', $('manualHost').value);
});
$('usbGo').onclick = () => deskcast.send('connect-usb');
$('connCancel').onclick = () => deskcast.send('cancel');
$('pairCancel').onclick = () => deskcast.send('cancel');
$('pairForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const code = $('code').value.replace(/\D/g, '');
  if (code.length !== 6) {
    $('pairError').textContent = 'The code has 6 digits.';
    return;
  }
  $('pairGo').disabled = true;
  deskcast.send('submit-code', code);
});
$('code').addEventListener('input', () => {
  const d = $('code').value.replace(/\D/g, '').slice(0, 6);
  $('code').value = d;
  $('pairError').textContent = '';
  if (d.length === 6) $('pairForm').requestSubmit();
});

document.querySelectorAll('[data-act]').forEach((b) => {
  b.addEventListener('click', () => {
    const a = b.dataset.act;
    deskcast.send(a === 'min' ? 'win-minimize' : a === 'max' ? 'win-maximize' : 'win-close');
  });
});

let lastView = null;
deskcast.on('ui', (u) => {
  ui = u;
  setView(u.view);
  renderPhones();
  $('homeError').textContent = u.error || '';
  $('pcName').textContent = u.pcName ? ` This PC appears on the phone as “${u.pcName}”.` : '';
  const name = (u.phone && u.phone.name) || 'phone';
  $('connTitle').textContent = `Connecting to ${name}…`;
  $('connStatus').textContent = u.status || '';
  $('pairName').textContent = name;
  $('phoneName').textContent = name;
  if (u.view === 'pair') {
    $('pairError').textContent = u.pairError || '';
    $('pairGo').disabled = false;
    if (lastView !== 'pair' || u.pairError) {
      $('code').value = '';
      setTimeout(() => $('code').focus(), 50);
    }
  }
  $('bTop').classList.toggle('active', !!u.alwaysOnTop);
  $('bRotate').classList.toggle('active', !!u.rotateLock);
  $('bFull').classList.toggle('active', !!u.fullscreen);
  app.classList.toggle('fullscreen', !!u.fullscreen);
  if (lastView === null) {
    audio.volume = typeof u.volume === 'number' ? u.volume : 1;
    audio.muted = !!u.muted;
    $('volume').value = audio.volume;
    applyVolume();
  }
  if (u.view !== 'mirror' && lastView === 'mirror') resetVideo();
  lastView = u.view;
  if (u.smoke && !smokeSent) runSmoke();
});

// =============================================================================================
// video: WebCodecs H.264 (Annex B) → canvas
// =============================================================================================

const video = {
  decoder: null,
  codec: null,
  config: null, // SPS/PPS (Annex B)
  waitKey: true,
  format: null,
  pending: null, // newest decoded frame not drawn yet
  shown: null, // frame on screen (kept for screenshots)
  drawScheduled: false,
  lastKeyRequest: 0,
};

const stats = { frames: 0, bytes: 0, dropped: 0, lat: null, rtt: null, offsetUs: null, decodeErrors: 0 };

function codecFromConfig(buf) {
  // Find the SPS NAL (type 7) and read profile / constraints / level.
  for (let i = 0; i + 4 < buf.length; i++) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
      const h = buf[i + 3];
      if ((h & 0x1f) === 7 && i + 6 < buf.length) {
        const hex = (v) => v.toString(16).padStart(2, '0').toUpperCase();
        return `avc1.${hex(buf[i + 4])}${hex(buf[i + 5])}${hex(buf[i + 6])}`;
      }
      i += 2;
    }
  }
  return null;
}

function requestKeyframe() {
  const t = performance.now();
  if (t - video.lastKeyRequest < 300) return;
  video.lastKeyRequest = t;
  deskcast.send('keyframe');
}

function closeDecoder() {
  if (video.decoder) {
    try {
      if (video.decoder.state !== 'closed') video.decoder.close();
    } catch (_) {}
  }
  video.decoder = null;
  video.codec = null;
}

function resetVideo() {
  closeDecoder();
  video.config = null;
  video.waitKey = true;
  if (video.pending) video.pending.close();
  video.pending = null;
  if (video.shown) video.shown.close();
  video.shown = null;
  app.classList.remove('has-picture');
  ctx.fillStyle = '#07090d';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function makeDecoder(codec) {
  closeDecoder();
  const d = new VideoDecoder({
    output: onFrame,
    error: (e) => {
      console.warn('decoder error', e && e.message);
      stats.decodeErrors++;
      // Start over from the next keyframe.
      if (video.decoder === d) {
        video.decoder = null;
        video.codec = null;
      }
      video.waitKey = true;
      requestKeyframe();
    },
  });
  const cfg = { codec, optimizeForLatency: true, hardwareAcceleration: 'no-preference' };
  if (video.format && video.format.width) {
    cfg.codedWidth = video.format.width;
    cfg.codedHeight = video.format.height;
  }
  d.configure(cfg);
  video.decoder = d;
  video.codec = codec;
  video.waitKey = true;
}

function onVideo(p) {
  const data = p.data;
  stats.bytes += data.byteLength;
  if (p.flags & VF_CONFIG) {
    video.config = new Uint8Array(data);
    const codec = codecFromConfig(video.config) || 'avc1.42E01F';
    if (!video.decoder || video.codec !== codec || video.decoder.state === 'closed') makeDecoder(codec);
    video.waitKey = true;
    return;
  }
  if (!video.config) {
    requestKeyframe();
    return;
  }
  if (!video.decoder || video.decoder.state !== 'configured') {
    makeDecoder(video.codec || codecFromConfig(video.config) || 'avc1.42E01F');
  }
  const key = (p.flags & VF_KEY) !== 0;
  if (video.waitKey && !key) {
    requestKeyframe();
    return;
  }
  if (video.decoder.decodeQueueSize > 2 && !key) {
    // Decoder is behind: skip ahead to the next keyframe instead of building up lag.
    stats.dropped++;
    video.waitKey = true;
    requestKeyframe();
    return;
  }
  let chunkData = data;
  if (key) {
    // Annex B without a description: SPS/PPS must be in-band, in front of the IDR.
    chunkData = new Uint8Array(video.config.byteLength + data.byteLength);
    chunkData.set(video.config, 0);
    chunkData.set(data, video.config.byteLength);
  }
  try {
    video.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: p.pts, data: chunkData }));
    video.waitKey = false;
  } catch (e) {
    console.warn('decode failed', e.message);
    video.waitKey = true;
    requestKeyframe();
  }
}

function onFrame(frame) {
  if (video.pending) {
    video.pending.close();
    stats.dropped++;
  }
  video.pending = frame;
  if (!video.drawScheduled) {
    video.drawScheduled = true;
    requestAnimationFrame(draw);
  }
}

function fitCanvas() {
  const dpr = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(canvas.clientWidth * dpr));
  const h = Math.max(1, Math.round(canvas.clientHeight * dpr));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
    return true;
  }
  return false;
}

function paint(frame) {
  fitCanvas();
  const cw = canvas.width;
  const ch = canvas.height;
  const fw = frame.displayWidth;
  const fh = frame.displayHeight;
  const k = Math.min(cw / fw, ch / fh);
  const dw = Math.round(fw * k);
  const dh = Math.round(fh * k);
  const dx = Math.floor((cw - dw) / 2);
  const dy = Math.floor((ch - dh) / 2);
  ctx.fillStyle = '#07090d';
  if (dx > 0) {
    ctx.fillRect(0, 0, dx, ch);
    ctx.fillRect(dx + dw, 0, cw - dx - dw, ch);
  }
  if (dy > 0) {
    ctx.fillRect(0, 0, cw, dy);
    ctx.fillRect(0, dy + dh, cw, ch - dy - dh);
  }
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(frame, dx, dy, dw, dh);
}

function draw() {
  video.drawScheduled = false;
  const f = video.pending;
  if (!f) return;
  video.pending = null;
  paint(f);
  if (video.shown) video.shown.close();
  video.shown = f;
  stats.frames++;
  if (stats.offsetUs != null) {
    // Capture on the phone → on this screen, using the clock offset measured with pings.
    const lat = (nowUs() - (f.timestamp - stats.offsetUs)) / 1000;
    if (lat > -50 && lat < 5000) stats.lat = stats.lat == null ? lat : stats.lat * 0.9 + lat * 0.1;
  }
  if (!app.classList.contains('has-picture')) app.classList.add('has-picture');
}

window.addEventListener('resize', () => {
  if (video.shown && fitCanvas()) paint(video.shown);
});

deskcast.on('video-format', (f) => {
  const changed = !video.format || video.format.width !== f.width || video.format.height !== f.height;
  video.format = f;
  if (changed) {
    // New encoder on the phone (rotation): a fresh config + keyframe follow.
    closeDecoder();
    video.config = null;
    video.waitKey = true;
  }
});

deskcast.on('video', onVideo);
deskcast.on('clock', (c) => {
  stats.rtt = c.rttUs / 1000;
  stats.offsetUs = c.offsetUs;
});
deskcast.on('session', () => {
  resetVideo();
  stats.lat = null;
  showToolbar();
});
deskcast.on('stopped', () => {
  resetVideo();
  audio.reset();
});

// =============================================================================================
// audio: PCM s16le → AudioWorklet ring buffer with a small jitter buffer
// =============================================================================================

const WORKLET = `
class PcmPlayer extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const o = options.processorOptions || {};
    this.channels = o.channels || 2;
    this.target = o.target || 4000;   // start / restart once this many frames are buffered
    this.max = o.max || 12000;        // above this, skip ahead to keep latency low
    this.size = sampleRate * 2;
    this.L = new Float32Array(this.size);
    this.R = new Float32Array(this.size);
    this.r = 0; this.w = 0; this.count = 0;
    this.priming = true;
    this.underruns = 0; this.skips = 0; this.tick = 0;
    this.port.onmessage = (e) => {
      if (e.data === 'reset') { this.r = this.w = this.count = 0; this.priming = true; return; }
      this.push(new Int16Array(e.data));
    };
  }
  push(s) {
    const ch = this.channels;
    const n = Math.floor(s.length / ch);
    for (let i = 0; i < n; i++) {
      const l = s[i * ch] / 32768;
      this.L[this.w] = l;
      this.R[this.w] = ch > 1 ? s[i * ch + 1] / 32768 : l;
      this.w = (this.w + 1) % this.size;
    }
    this.count += n;
    if (this.count > this.size) { this.r = (this.w + 1) % this.size; this.count = this.size - 1; }
    if (this.count > this.max) {
      const drop = this.count - this.target;
      this.r = (this.r + drop) % this.size;
      this.count -= drop;
      this.skips++;
    }
  }
  process(inputs, outputs) {
    const out = outputs[0];
    const a = out[0], b = out[1] || out[0];
    const n = a.length;
    if (this.priming) {
      if (this.count >= this.target) this.priming = false;
      else { a.fill(0); b.fill(0); return true; }
    }
    for (let i = 0; i < n; i++) {
      if (this.count > 0) {
        a[i] = this.L[this.r]; b[i] = this.R[this.r];
        this.r = (this.r + 1) % this.size; this.count--;
      } else {
        a[i] = 0; b[i] = 0;
        if (!this.priming) { this.priming = true; this.underruns++; }
      }
    }
    if (++this.tick % 200 === 0) this.port.postMessage({ buffered: this.count, underruns: this.underruns, skips: this.skips });
    return true;
  }
}
registerProcessor('pcm-player', PcmPlayer);
`;

const audio = {
  ctx: null,
  node: null,
  gain: null,
  format: null,
  volume: 1,
  muted: false,
  starting: null,
  bufferedMs: 0,
  async init(fmt) {
    const rate = fmt.sampleRate || 48000;
    const channels = fmt.channels || 2;
    if (this.ctx && this.format && this.format.sampleRate === rate && this.format.channels === channels) return;
    this.format = { sampleRate: rate, channels };
    if (this.ctx) {
      try {
        await this.ctx.close();
      } catch (_) {}
    }
    const c = new AudioContext({ sampleRate: rate, latencyHint: 'interactive' });
    const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }));
    await c.audioWorklet.addModule(url);
    URL.revokeObjectURL(url);
    const node = new AudioWorkletNode(c, 'pcm-player', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [2],
      processorOptions: { channels, target: Math.round(rate * 0.08), max: Math.round(rate * 0.2) },
    });
    node.port.onmessage = (e) => {
      this.bufferedMs = (e.data.buffered / rate) * 1000;
    };
    const gain = c.createGain();
    node.connect(gain).connect(c.destination);
    this.ctx = c;
    this.node = node;
    this.gain = gain;
    applyVolume();
    if (c.state !== 'running') c.resume().catch(() => {});
  },
  push(data) {
    if (!this.node) return;
    const ab = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
    this.node.port.postMessage(ab, [ab]);
  },
  reset() {
    if (this.node) this.node.port.postMessage('reset');
  },
};

function applyVolume() {
  app.classList.toggle('muted-audio', audio.muted || audio.volume === 0);
  if (audio.gain) audio.gain.gain.setTargetAtTime(audio.muted ? 0 : audio.volume, audio.ctx.currentTime, 0.02);
}

deskcast.on('audio-format', (f) => {
  audio.starting = audio.init(f).catch((e) => console.warn('audio init failed', e.message));
});
deskcast.on('audio', (p) => {
  stats.bytes += p.data.byteLength;
  audio.push(p.data);
});

$('bMute').onclick = () => {
  audio.muted = !audio.muted;
  if (!audio.muted && audio.volume === 0) {
    audio.volume = 0.8;
    $('volume').value = 0.8;
  }
  applyVolume();
  deskcast.send('set-audio', { muted: audio.muted, volume: audio.volume });
};
$('volume').addEventListener('input', () => {
  audio.volume = Number($('volume').value);
  audio.muted = false;
  applyVolume();
  deskcast.send('set-audio', { muted: audio.muted, volume: audio.volume });
});

// =============================================================================================
// toolbar & controls
// =============================================================================================

let hideTimer = null;
function showToolbar() {
  $('toolbar').classList.remove('hidden');
  app.classList.remove('hide-cursor');
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => {
    if ($('toolbar').matches(':hover')) return showToolbar();
    $('toolbar').classList.add('hidden');
    app.classList.add('hide-cursor');
  }, 2500);
}
document.addEventListener('mousemove', () => {
  if (ui.view === 'mirror') showToolbar();
});
$('toolbar').addEventListener('mouseenter', showToolbar);

canvas.addEventListener('dblclick', () => deskcast.send('toggle-fullscreen'));
$('bFull').onclick = () => deskcast.send('toggle-fullscreen');
$('bTop').onclick = () => deskcast.send('set-always-on-top', !ui.alwaysOnTop);
$('bRotate').onclick = () => {
  deskcast.send('set-rotate-lock', !ui.rotateLock);
  toast(ui.rotateLock ? 'Window follows the phone rotation again' : 'Window shape locked');
};
$('bStop').onclick = () => deskcast.send('disconnect');
$('bShot').onclick = screenshot;

async function screenshot() {
  const f = video.shown;
  if (!f) return toast('No picture yet');
  try {
    const oc = new OffscreenCanvas(f.displayWidth, f.displayHeight);
    oc.getContext('2d').drawImage(f, 0, 0);
    const blob = await oc.convertToBlob({ type: 'image/png' });
    const file = await deskcast.saveScreenshot(new Uint8Array(await blob.arrayBuffer()));
    toast(`Saved ${file.replace(/^.*\/(Pictures\/)/, '~/$1')}`, () => deskcast.send('show-file', file));
  } catch (e) {
    toast('Screenshot failed: ' + e.message);
  }
}

window.addEventListener('keydown', (e) => {
  if (e.key === 'F11') {
    e.preventDefault();
    deskcast.send('toggle-fullscreen');
  } else if (e.key === 'Escape') {
    deskcast.send('exit-fullscreen');
  } else if (ui.view === 'mirror' && e.ctrlKey && e.key.toLowerCase() === 's') {
    e.preventDefault();
    screenshot();
  } else if (ui.view === 'mirror' && !e.ctrlKey && e.key.toLowerCase() === 'm') {
    $('bMute').click();
  }
});

let toastTimer = null;
function toast(msg, onClick) {
  const t = $('toast');
  t.textContent = msg;
  t.onclick = onClick || null;
  t.style.cursor = onClick ? 'pointer' : 'default';
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
}

// =============================================================================================
// stats
// =============================================================================================

let lastStatsAt = performance.now();
setInterval(() => {
  const t = performance.now();
  const sec = (t - lastStatsAt) / 1000;
  lastStatsAt = t;
  const fps = stats.frames / sec;
  const mbps = (stats.bytes * 8) / sec / 1e6;
  const parts = [];
  if (video.format) parts.push(`${video.format.width}×${video.format.height}`);
  parts.push(`${Math.round(fps)} fps`);
  parts.push(`${mbps.toFixed(1)} Mbps`);
  if (stats.lat != null) parts.push(`${Math.max(0, Math.round(stats.lat))} ms`);
  else if (stats.rtt != null) parts.push(`ping ${Math.round(stats.rtt)} ms`);
  if (audio.node) parts.push(`audio ${Math.round(audio.bufferedMs)} ms`);
  $('stats').textContent = parts.join('  ·  ');
  if (ui.view === 'mirror') {
    deskcast.send('stats', {
      fps: Math.round(fps),
      mbps: Number(mbps.toFixed(2)),
      latencyMs: stats.lat == null ? null : Math.round(stats.lat),
      rttMs: stats.rtt == null ? null : Math.round(stats.rtt),
      dropped: stats.dropped,
      decodeErrors: stats.decodeErrors,
      format: video.format,
      codec: video.codec,
      audioMs: audio.node ? Math.round(audio.bufferedMs) : null,
    });
  }
  stats.frames = 0;
  stats.bytes = 0;
}, 1000);

// =============================================================================================
// CI smoke test: prove the renderer, WebCodecs and audio worklets work in the packaged app.
// =============================================================================================

let smokeSent = false;
async function runSmoke() {
  smokeSent = true;
  const info = { webcodecs: typeof VideoDecoder === 'function', h264: null, audioWorklet: false };
  try {
    const s = await VideoDecoder.isConfigSupported({ codec: 'avc1.42E01F', optimizeForLatency: true });
    info.h264 = !!s.supported;
  } catch (e) {
    info.h264 = 'error: ' + e.message;
  }
  try {
    const c = new AudioContext({ sampleRate: 48000 });
    const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }));
    await c.audioWorklet.addModule(url);
    info.audioWorklet = true;
    await c.close();
  } catch (e) {
    info.audioWorklet = 'error: ' + e.message;
  }
  deskcast.send('smoke', info);
}

deskcast.send('ready');
