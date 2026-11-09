'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { spawn } = require('child_process');
const P = require('../src/protocol');
const { PhoneLink } = require('../src/link');
const { FakePhone, AccessUnitSplitter, hasFfmpeg } = require('./fake-phone');

function memTokens() {
  const m = new Map();
  return {
    map: m,
    get: (id) => (m.has(id) ? m.get(id).token : null),
    save: (id, token, name) => m.set(id, { token, name }),
    forget: (id) => m.delete(id),
  };
}

function once(em, ev, ms = 4000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${ev}`)), ms);
    em.once(ev, (...a) => {
      clearTimeout(t);
      resolve(a);
    });
  });
}

// ---------------------------------------------------------------------------------------------
// framing
// ---------------------------------------------------------------------------------------------

test('frames survive arbitrary TCP chunking', () => {
  const frames = [
    P.encodeJson(P.T.HELLO, { app: 'deskcast', name: 'Pixel', id: 'x' }),
    P.encodeFrame(P.T.VIDEO, 123456789012, Buffer.from([0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1f]), P.VF_CONFIG),
    P.encodeFrame(P.T.VIDEO, 123456789999, Buffer.alloc(70000, 7), P.VF_KEY),
    P.encodeFrame(P.T.AUDIO, 5, Buffer.alloc(1920, 3)),
    P.encodeFrame(P.T.PING, 42, Buffer.alloc(0)),
  ];
  const all = Buffer.concat(frames);
  for (const step of [1, 3, 13, 14, 1000, all.length]) {
    const got = [];
    const parser = new P.FrameParser((p) => got.push(p));
    for (let i = 0; i < all.length; i += step) parser.push(all.subarray(i, i + step));
    assert.strictEqual(got.length, 5, `step ${step}`);
    assert.deepStrictEqual(got[0].json, { app: 'deskcast', name: 'Pixel', id: 'x' });
    assert.strictEqual(got[1].type, P.T.VIDEO);
    assert.strictEqual(got[1].flags, P.VF_CONFIG);
    assert.strictEqual(got[1].pts, 123456789012);
    assert.strictEqual(got[1].data.length, 8);
    assert.strictEqual(got[2].flags, P.VF_KEY);
    assert.strictEqual(got[2].data.length, 70000);
    assert.ok(got[2].data.every((b) => b === 7));
    assert.strictEqual(got[3].data.length, 1920);
    assert.strictEqual(got[4].pts, 42);
  }
});

test('header layout matches the phone (type u8, len u32 BE, pts i64 BE)', () => {
  const f = P.encodeFrame(P.T.VIDEO, 0x0102030405, Buffer.from([0xaa, 0xbb]), P.VF_KEY);
  assert.deepStrictEqual([...f], [17, 0, 0, 0, 3, 0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05, 2, 0xaa, 0xbb]);
});

test('oversized packets are rejected', () => {
  const parser = new P.FrameParser(() => {}, 1024);
  const bad = Buffer.alloc(13);
  bad.writeUInt8(17, 0);
  bad.writeUInt32BE(5000, 1);
  assert.throws(() => parser.push(bad), /too large/);
});

test('codec string from SPS and NAL splitting', () => {
  const cfg = Buffer.from([0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xac, 0, 0, 1, 0x68, 0xee, 0x3c, 0x80]);
  assert.strictEqual(P.codecStringFromConfig(cfg), 'avc1.640028');
  const nals = P.splitNals(cfg);
  assert.strictEqual(nals.length, 2);
  assert.strictEqual(nals[0][0] & 0x1f, 7);
  assert.strictEqual(nals[1][0] & 0x1f, 8);
  assert.strictEqual(P.codecStringFromConfig(Buffer.from([0, 0, 1, 0x65, 1, 2])), null);
});

// ---------------------------------------------------------------------------------------------
// pairing + streaming against a fake phone
// ---------------------------------------------------------------------------------------------

test('pairing: code once, then token; wrong code and forgotten token', async () => {
  const phone = new FakePhone({ code: '246810', id: 'phone-A', name: 'Test Phone' });
  const port = await phone.start();
  const tokens = memTokens();
  try {
    // 1) First time: phone asks for the code, PC types it, gets a token.
    let l = new PhoneLink({ host: '127.0.0.1', port, pcName: 'vikas@orez', tokens }).connect();
    const [hello] = await once(l, 'need-code');
    assert.strictEqual(hello.name, 'Test Phone');
    l.submitCode('246 810');
    await once(l, 'authenticated');
    assert.ok(/^[0-9a-f]{64}$/.test(tokens.get('phone-A')), 'token saved');
    assert.strictEqual(phone.trusted.get(tokens.get('phone-A')), 'vikas@orez');
    l.close('user');

    // 2) Next time: connects by itself with the token, no code asked.
    l = new PhoneLink({ host: '127.0.0.1', port, pcName: 'vikas@orez', tokens }).connect();
    let asked = false;
    l.on('need-code', () => (asked = true));
    await once(l, 'authenticated');
    assert.strictEqual(asked, false);
    l.close('user');

    // 3) Wrong code: auth-failed, then the phone hangs up.
    const other = memTokens();
    l = new PhoneLink({ host: '127.0.0.1', port, pcName: 'intruder', tokens: other }).connect();
    await once(l, 'need-code');
    const failed = once(l, 'auth-failed');
    const closed = once(l, 'close');
    l.submitCode('000000');
    assert.deepStrictEqual(await failed, ['code']);
    await closed;
    assert.strictEqual(other.get('phone-A'), null);

    // 4) Phone forgot the PC ("Forget paired PCs"): token rejected, code asked again on the same connection.
    phone.trusted.clear();
    l = new PhoneLink({ host: '127.0.0.1', port, pcName: 'vikas@orez', tokens }).connect();
    const [, reason] = await once(l, 'need-code');
    assert.strictEqual(reason, 'token');
    assert.strictEqual(tokens.get('phone-A'), null, 'stale token dropped');
    l.submitCode('246810');
    await once(l, 'authenticated');
    assert.ok(tokens.get('phone-A'));
    l.close('user');
  } finally {
    phone.stop();
  }
});

test('streaming: format, config, keyframe gating, audio, clock, keyframe request', async () => {
  const phone = new FakePhone({ code: '111111', id: 'phone-B' });
  const port = await phone.start();
  const tokens = memTokens();
  try {
    phone.setAudioFormat(48000, 2);
    phone.setVideoFormat(1080, 2400);
    phone.sendConfig(Buffer.from([0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x29, 0, 0, 0, 1, 0x68, 0xce]));

    const l = new PhoneLink({ host: '127.0.0.1', port, pcName: 'pc', tokens }).connect();
    await once(l, 'need-code');
    const af = once(l, 'audio-format');
    const vf = once(l, 'video-format');
    const cfg = once(l, 'video');
    l.submitCode('111111');
    await once(l, 'authenticated');
    assert.strictEqual((await af)[0].sampleRate, 48000);
    assert.deepStrictEqual([(await vf)[0].width, (await vf)[0].height, (await vf)[0].orientation], [1080, 2400, 'portrait']);
    const [c] = await cfg;
    assert.strictEqual(c.flags, P.VF_CONFIG);

    // Delta frames before the first keyframe are not sent to a new viewer.
    const videos = [];
    l.on('video', (p) => videos.push(p));
    phone.sendFrame(Buffer.from([0, 0, 0, 1, 0x41, 1]), 1000, false);
    phone.sendFrame(Buffer.from([0, 0, 0, 1, 0x65, 2]), 2000, true);
    phone.sendFrame(Buffer.from([0, 0, 0, 1, 0x41, 3]), 3000, false);
    const audio = once(l, 'audio');
    phone.sendAudio(Buffer.alloc(1920), 4000);
    const [a] = await audio;
    assert.strictEqual(a.data.length, 1920);
    assert.deepStrictEqual(
      videos.map((v) => [v.pts, v.flags]),
      [
        [2000, P.VF_KEY],
        [3000, 0],
      ],
    );

    // Rotation: a new format arrives and the next picture must be a keyframe again.
    const vf2 = once(l, 'video-format');
    phone.setVideoFormat(2400, 1080);
    assert.strictEqual((await vf2)[0].orientation, 'landscape');

    const [clock] = await once(l, 'clock', 3000);
    assert.ok(clock.rttUs >= 0 && clock.rttUs < 1e6);
    assert.ok(Math.abs(clock.offsetUs) < 1e6, 'same machine → offset near 0');

    const kr = once(phone, 'keyframe-request');
    l.requestKeyframe();
    await kr;
    l.close('user');
  } finally {
    phone.stop();
  }
});

test('ffmpeg H.264 stream splits into config + access units like MediaCodec', { skip: !hasFfmpeg() && 'ffmpeg not installed' }, async () => {
  const configs = [];
  const frames = [];
  const sp = new AccessUnitSplitter(
    (c) => configs.push(c),
    (au, key) => frames.push({ au, key }),
  );
  const ff = spawn('ffmpeg', [
    '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=320x568:rate=30', '-frames:v', '20',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-g', '10',
    '-bsf:v', 'h264_metadata=aud=insert', '-f', 'h264', '-',
  ]);
  ff.stdout.on('data', (d) => sp.push(d));
  await once(ff, 'close', 15000);
  sp.push(Buffer.from([0, 0, 0, 1, 0x09, 0xf0])); // flush the last access unit
  assert.ok(configs.length >= 1);
  assert.ok(P.codecStringFromConfig(configs[0]).startsWith('avc1.42'), 'baseline profile');
  assert.ok(frames.length >= 19, `frames: ${frames.length}`);
  assert.strictEqual(frames[0].key, true);
  assert.ok(frames.filter((f) => f.key).length >= 2, 'keyframe every 10');
});
