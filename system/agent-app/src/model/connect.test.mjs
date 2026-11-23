import assert from 'node:assert/strict';
import test from 'node:test';
import {Buffer} from 'node:buffer';
import {
  capabilityChips, connectError, customIcon, dispatchDraft, displayName, effectiveDeviceType, hostPort, isLive, latencyLabel,
  forEachLimited, isTransientProbeError, PROBE_CONCURRENCY, nodeState, normalizeUrl, ORBIT_LABEL_HALF, orbitLayout, resolveIcon, routeKindLabel, routeLabel, routeRows, stateTone, statusLine, validateAddForm,
} from './connect.ts';

const mac = {id: 'taku_macmini', url: 'http://192.168.1.205:7878', host: '192.168.1.205', network: 'lan', enabled: true, hasToken: true, appearance: {}};
const online = {
  status: 'online', online: true, latencyMs: 2.4, network: 'lan',
  capabilities: {
    device: 'HaiProdeMac-mini.local', mode: 'endpoint', appearance: {deviceType: 'mac_mini', displayName: 'HaiPro 的 Mac mini'},
    tools: [{name: 'file_read'}, {name: 'shell_exec'}, {name: 'system_info'}], reservedTools: ['screen_capture', 'tv_remote'],
    exec: {enabled: true, allowedRoots: ['/Users/haipro'], defaultTimeoutMs: 30000, maxTimeoutMs: 300000, maxOutputBytes: 65536},
  },
};
const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1" height="1"/></svg>';
const svgData = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;

test('icon tier 1: the phone override wins over the device icon, in every supported form', () => {
  const node = {...mac, appearance: {icon: 'https://example.com/mini.png', deviceType: 'tv'}};
  const probe = {...online, capabilities: {...online.capabilities, appearance: {icon: 'builtin:server', deviceType: 'server'}}};
  assert.deepEqual(resolveIcon(node, probe), {tier: 'custom', kind: 'image', uri: 'https://example.com/mini.png'});
  assert.deepEqual(resolveIcon({appearance: {}}, probe), {tier: 'builtin', key: 'server'}, 'device-advertised icon next');
  assert.deepEqual(resolveIcon({appearance: {icon: svgData}}), {tier: 'custom', kind: 'svg-data', svg});
  assert.deepEqual(resolveIcon({appearance: {icon: 'https://x.dev/i.svg?v=2'}}), {tier: 'custom', kind: 'svg-url', uri: 'https://x.dev/i.svg?v=2'});
  assert.deepEqual(resolveIcon({appearance: {icon: 'data:image/png;base64,iVBORw0KGgo='}}), {tier: 'custom', kind: 'image', uri: 'data:image/png;base64,iVBORw0KGgo='});
});

test('icon tier 2/3: unusable icons fall through to the built-in type, then generic', () => {
  for (const bad of ['javascript:alert(1)', 'file:///sdcard/a.png', 'builtin:toaster', 'data:text/html;base64,PGI+', '', '   ', 42, null]) {
    assert.equal(customIcon(bad), null, String(bad));
    assert.deepEqual(resolveIcon({appearance: {icon: bad, deviceType: 'laptop'}}), {tier: 'builtin', key: 'laptop'});
  }
  assert.deepEqual(resolveIcon(mac, online), {tier: 'builtin', key: 'mac_mini'}, 'advertised type');
  assert.deepEqual(resolveIcon({appearance: {deviceType: 'toaster'}}), {tier: 'generic', key: 'generic'});
  assert.deepEqual(resolveIcon({appearance: {icon: 'builtin:generic'}}), {tier: 'generic', key: 'generic'});
  assert.deepEqual(resolveIcon(null, null), {tier: 'generic', key: 'generic'});
  assert.equal(effectiveDeviceType(mac, online), 'mac_mini');
  assert.equal(effectiveDeviceType({appearance: {deviceType: 'car'}}, online), 'car', 'phone override first');
});

test('names: override, advertised display name, device host, then the id', () => {
  assert.equal(displayName(mac, online), 'HaiPro 的 Mac mini');
  assert.equal(displayName({...mac, appearance: {displayName: '书房 Mac mini'}}, online), '书房 Mac mini');
  assert.equal(displayName(mac, {status: 'online', online: true, capabilities: {device: 'mini.local'}}), 'mini.local');
  assert.equal(displayName(mac, null), 'macmini');
});

test('node state maps switch and probe to one state and tone', () => {
  assert.equal(nodeState(mac, null), 'checking');
  assert.equal(nodeState(mac, online), 'online');
  assert.equal(nodeState({enabled: false}, online), 'disabled', 'a switched-off node is off even when reachable');
  assert.equal(nodeState(mac, {status: 'unauthorized', online: true}), 'unauthorized');
  assert.equal(nodeState(mac, {status: 'offline', online: false}), 'offline');
  assert.deepEqual(['online', 'unauthorized', 'offline', 'disabled', 'checking'].map(stateTone), ['success', 'attention', 'error', 'muted', 'muted']);
  assert.equal(isLive('online'), true);
  assert.equal(isLive('unauthorized'), false);
});

test('latency and status lines', () => {
  assert.equal(latencyLabel(online, 'zh'), '局域网 2ms');
  assert.equal(latencyLabel({...online, network: 'tailscale', latencyMs: 14}, 'en'), 'Tailscale 14ms');
  assert.equal(latencyLabel({status: 'offline', online: false}, 'zh'), '');
  assert.equal(statusLine(mac, online, 'zh'), '已连接 · 局域网 2ms');
  assert.equal(statusLine(mac, {status: 'offline', online: false}, 'zh'), '离线');
  assert.equal(statusLine({enabled: false}, online, 'en'), 'Off');
});

const routes = [
  {label: '书房', kind: 'lan', url: 'http://192.168.1.205:7878'},
  {kind: 'ipv6', url: 'http://[fd7a::1]:7878'},
  {label: 'Mac mini 转发', kind: 'relay', url: 'http://192.168.1.10:17878'},
];
const multi = {...mac, routes};
const routed = {
  ...online, url: routes[2].url, latencyMs: 45,
  route: {...routes[2], status: 'online', latencyMs: 45, selected: true},
  routes: [
    {...routes[0], status: 'offline', error: 'UNREACHABLE', selected: false},
    {...routes[1], status: 'online', latencyMs: 80, selected: false},
    {...routes[2], status: 'online', latencyMs: 45, selected: true},
  ],
};

test('route kind labels in both languages', () => {
  assert.deepEqual(['lan', 'ipv6', 'tailscale', 'relay', 'internet'].map(kind => routeKindLabel(kind, 'zh')), ['局域网', 'IPv6', 'Tailscale', '中转', '公网']);
  assert.deepEqual(['lan', 'ipv6', 'tailscale', 'relay', 'internet'].map(kind => routeKindLabel(kind, 'en')), ['LAN', 'IPv6', 'Tailscale', 'Relay', 'Internet']);
  assert.equal(routeKindLabel('moon', 'en'), 'Internet');
  assert.equal(routeLabel(routes[0], 'en'), '书房', 'the user label names the route in the list');
  assert.equal(routeLabel({label: '  ', kind: 'relay'}, 'zh'), '中转');
});

test('status line shows only the selected route, by its kind', () => {
  assert.equal(statusLine(multi, routed, 'en'), 'Online · Relay 45ms', 'kind, not the user label, for consistency');
  assert.equal(statusLine(multi, routed, 'zh'), '已连接 · 中转 45ms');
  assert.equal(statusLine(multi, {...routed, route: {...routed.route, kind: 'lan', latencyMs: 7.6}}, 'zh'), '已连接 · 局域网 8ms');
  assert.equal(latencyLabel({...online, network: 'ipv6'}, 'en'), 'IPv6 2ms', 'older probes without route use the network');
  assert.equal(statusLine(multi, {status: 'offline', online: false, routes: routed.routes.map(r => ({...r, status: 'offline', selected: false}))}, 'en'), 'Offline');
});

test('routes list: selected first with latency, backups in order, offline ones quiet', () => {
  const rows = routeRows(multi, routed, 'zh');
  assert.deepEqual(rows.map(r => [r.label, r.tone, r.latency, r.selected]), [
    ['Mac mini 转发', 'selected', '45ms', true],
    ['书房', 'offline', '', false],
    ['IPv6', 'standby', '80ms', false],
  ]);
  assert.deepEqual(routeRows(multi, null, 'en').map(r => [r.label, r.tone]), [['书房', 'unknown'], ['IPv6', 'unknown'], ['Mac mini 转发', 'unknown']],
    'before a probe: configured order, no status');
  assert.deepEqual(routeRows(mac, null, 'en').map(r => [r.label, r.url]), [['LAN', 'http://192.168.1.205:7878']], 'legacy single-url node');
  assert.equal(routeRows({...multi, enabled: false}, routed, 'en').some(r => r.selected), false, 'a switched-off node has no active route');
  const settled = {...routed, routes: routed.routes.map(r => r.selected ? r : {...r, status: 'standby', latencyMs: undefined, error: undefined})};
  assert.deepEqual(routeRows(multi, settled, 'en').slice(1).map(r => [r.tone, r.latency]), [['standby', ''], ['standby', '']],
    'a backup still answering when the choice settled stays quiet, not offline');
  const rejected = {...routed, status: 'unauthorized', routes: routed.routes.map(r => r.selected ? {...r, status: 'unauthorized'} : r)};
  assert.equal(routeRows(multi, rejected, 'en')[0].tone, 'attention');
  assert.equal(hostPort('http://[fd7a::1]:7878'), '[fd7a::1]:7878');
});

test('capability chips show only what the node offers now', () => {
  assert.deepEqual(capabilityChips(online), ['terminal', 'files', 'system'], 'reserved screen/tv tools are not offered');
  assert.deepEqual(capabilityChips({...online, capabilities: {...online.capabilities, mode: 'autonomous', engine: {name: 'agy', protocol: 'acp'},
    tools: [...online.capabilities.tools, {name: 'screen_capture'}]}}), ['ai', 'terminal', 'files', 'screen', 'system']);
  assert.deepEqual(capabilityChips({status: 'unauthorized', online: true}), []);
});

test('dispatch draft names the device and its server id', () => {
  assert.equal(dispatchDraft(mac, online, 'zh'), '请在「HaiPro 的 Mac mini」（taku_macmini）上：');
  assert.equal(dispatchDraft(mac, online, 'en'), 'On HaiPro 的 Mac mini (taku_macmini), please: ');
});

test('add form validation mirrors the broker', () => {
  assert.equal(normalizeUrl('192.168.1.9'), 'http://192.168.1.9:7878');
  assert.equal(normalizeUrl(' HTTPS://mini.local:8443/ '), 'https://mini.local:8443');
  for (const bad of ['ftp://x', 'http://u:p@x', 'http://x/path', 'http://x:99999', 'http://..', '']) assert.equal(normalizeUrl(bad), null, bad);
  const ok = validateAddForm({name: 'TV', url: '192.168.1.30', tokenMode: 'token', tokenFile: '', tokenTyped: true});
  assert.deepEqual(ok, {ok: true, errors: {}, url: 'http://192.168.1.30:7878'});
  assert.deepEqual(validateAddForm({name: ' ', url: '', tokenMode: 'token', tokenFile: '', tokenTyped: false}).errors,
    {name: 'nameRequired', url: 'urlRequired', token: 'tokenRequired'});
  assert.equal(validateAddForm({name: 'x'.repeat(65), url: 'x', tokenMode: 'token', tokenFile: '', tokenTyped: true}).errors.name, 'nameTooLong');
  assert.equal(validateAddForm({name: 'a', url: 'ftp://x', tokenMode: 'token', tokenFile: '', tokenTyped: true}).errors.url, 'urlInvalid');
  assert.equal(validateAddForm({name: 'a', url: 'x', tokenMode: 'file', tokenFile: '/sdcard/t', tokenTyped: false}).errors.token, 'tokenFileInvalid');
  assert.equal(validateAddForm({name: 'a', url: 'x', tokenMode: 'file', tokenFile: '/data/local/agentos/../x', tokenTyped: false}).errors.token, 'tokenFileInvalid');
  assert.equal(validateAddForm({name: 'a', url: 'x', tokenMode: 'file', tokenFile: '/data/local/agentos/acp/secrets/tv.token', tokenTyped: false}).ok, true);
});

test('orbit layout: 5–6 slots share one ring, bubbles and labels never overlap', () => {
  for (const count of [5, 6]) {
    const {center, radii, slots} = orbitLayout(count, 411, 560);
    assert.ok(slots.every(s => s.ring === 1 && Math.abs(Math.hypot(s.x - center.x, s.y - center.y) - radii[1]) < 1e-6));
    // A node is a 64dp bubble (y −32…32) with a 150-wide one-line label under it (y 38…60).
    for (const s of slots) assert.ok(s.x - ORBIT_LABEL_HALF >= -1e-6 && s.x + ORBIT_LABEL_HALF <= 411 + 1e-6, `${count}: label off-canvas at ${s.x}`);
    for (let i = 0; i < slots.length; i++) for (let j = i + 1; j < slots.length; j++) {
      const [upper, lower] = slots[i].y <= slots[j].y ? [slots[i], slots[j]] : [slots[j], slots[i]];
      const dx = Math.abs(upper.x - lower.x), dy = lower.y - upper.y;
      const bubbles = dx < 64 && dy < 64, labels = dx < 2 * ORBIT_LABEL_HALF && dy < 22;
      const labelOnBubble = dx < ORBIT_LABEL_HALF + 32 && dy < 60 + 32;
      assert.ok(!bubbles && !labels && !labelOnBubble, `${count}: slots ${i} and ${j} overlap (dx ${dx}, dy ${dy})`);
    }
    assert.ok(radii[1] >= 42 + 32 + 40, `${count}: ring clears the orb (${radii[1]})`);
  }
});

test('orbit layout: up to 4 slots on one circular ring around the centre', () => {
  const {center, radii, slots, aspect} = orbitLayout(4, 400, 500);
  assert.deepEqual(center, {x: 200, y: 250});
  assert.equal(slots.length, 4);
  assert.equal(aspect, 1);
  assert.equal(new Set(slots.map(s => s.ring)).size, 1, 'all on the same ring');
  for (const slot of slots) {
    const r = Math.hypot(slot.x - center.x, slot.y - center.y);
    assert.ok(Math.abs(r - radii[slot.ring]) < 1e-6);
  }
  assert.deepEqual(orbitLayout(0, 100, 100).slots, []);
  // Phone-sized canvas: the inner ring clears the 84dp orb plus a 64dp bubble and its pulse, the
  // outer ring keeps bubbles on screen.
  const phone = orbitLayout(2, 411, 560);
  assert.ok(phone.radii[0] >= 42 + 32 + 40, `inner ${phone.radii[0]}`);
  assert.ok(phone.radii[1] + 32 <= 411 / 2, `outer ${phone.radii[1]}`);
});

test('probe rounds stay under the gateway limit; BUSY is not offline', async () => {
  let inFlight = 0, peak = 0;
  const seen = [];
  await forEachLimited([1, 2, 3, 4, 5, 6], PROBE_CONCURRENCY, async n => {
    inFlight++; peak = Math.max(peak, inFlight);
    await new Promise(resolve => setTimeout(resolve, 5));
    seen.push(n); inFlight--;
  });
  assert.equal(peak, PROBE_CONCURRENCY);
  assert.ok(PROBE_CONCURRENCY < 4, 'leaves a gateway slot for the page');
  assert.deepEqual(seen.sort(), [1, 2, 3, 4, 5, 6]);
  await forEachLimited([], 3, async () => { throw new Error('never'); });
  assert.equal(isTransientProbeError('BUSY'), true);
  assert.equal(isTransientProbeError('NODE_NOT_FOUND'), false);
});

test('error codes become readable messages', () => {
  assert.equal(connectError('NODE_UNAUTHORIZED', 'zh'), '密钥不正确');
  assert.equal(connectError('WEIRD', 'en'), 'Failed (WEIRD)');
});

// ---- demo nodes, device cards and the 7-node orbit ----

const tesla = {id: 'demo_tesla', url: '', host: '', network: 'internet', routes: [], enabled: true, hasToken: false, demo: true, demoStatus: '82% · 410 km',
  appearance: {displayName: 'Tesla Model Y', shortName: 'Tesla', deviceType: 'car'}};
const xiaomi = {...tesla, id: 'demo_xiaomi_tv', demoStatus: '4K HDR', appearance: {displayName: 'Xiaomi TV', shortName: 'Xiaomi TV', deviceType: 'tv'}};

test('demo nodes: answered locally, short orbit names, status detail from connect.json', async () => {
  const {demoProbe, orbitName, probeFor} = await import('./connect.ts');
  assert.equal(orbitName(tesla), 'Tesla');
  assert.equal(displayName(tesla), 'Tesla Model Y');
  assert.equal(orbitName(mac, online), 'HaiPro 的 Mac mini', 'no shortName: the display name');
  assert.deepEqual(demoProbe(tesla), {id: 'demo_tesla', status: 'online', online: true, enabled: true});
  assert.equal(probeFor({...tesla, enabled: false}, {}).status, 'offline');
  assert.equal(probeFor(mac, {taku_macmini: online}), online, 'real nodes keep their gateway probe');
  assert.equal(probeFor(mac, {}), undefined);
  assert.equal(statusLine(tesla, demoProbe(tesla), 'en'), 'Online · 82% · 410 km');
  assert.equal(statusLine(xiaomi, demoProbe(xiaomi), 'en'), 'Online · 4K HDR');
  assert.equal(statusLine({...tesla, enabled: false}, demoProbe({...tesla, enabled: false}), 'en'), 'Off');
});

test('device cards: only demo car/TV nodes get them; real nodes keep the standard card', async () => {
  const {cardKind} = await import('./connect.ts');
  assert.equal(cardKind(tesla), 'car');
  assert.equal(cardKind(xiaomi), 'tv');
  assert.equal(cardKind({...xiaomi, demo: false}), 'standard', 'a real TV box keeps its live capabilities and routes');
  assert.equal(cardKind({...tesla, appearance: {deviceType: 'mac_mini'}}), 'standard');
  assert.equal(cardKind(mac, online), 'standard');
});

test('orbit layout: 7–8 slots (5 real + 2 demo nodes + add) keep every bubble and label apart', async () => {
  const {orbitClearance, ORBIT_DENSE_LABEL_HALF} = await import('./connect.ts');
  // 410: the orbit area above the car card on a Pixel 9 (411 dp wide); 560: a short card.
  for (const [count, height] of [[7, 410], [8, 410], [8, 470], [8, 560], [7, 560]]) {
    const layout = orbitLayout(count, 411, height);
    assert.equal(layout.slots.length, count);
    assert.equal(layout.labelHalf, ORBIT_DENSE_LABEL_HALF);
    assert.ok(orbitClearance(layout) > 4, `${count}@${height}: clearance ${orbitClearance(layout)}`);
    for (const s of layout.slots) {
      assert.ok(s.x - layout.labelHalf >= 0 && s.x + layout.labelHalf <= 411, `${count}@${height}: label off-canvas at x ${s.x}`);
      assert.ok(s.y - 32 >= 0 && s.y + 60 <= height, `${count}@${height}: off-canvas at y ${s.y}`);
      const dx = (s.x - layout.center.x) / layout.aspect, dy = s.y - layout.center.y;
      assert.ok(Math.abs(Math.hypot(dx, dy) - layout.radii[1]) < 1e-6, 'slots sit on the drawn ellipse');
    }
  }
  // Short names fit the dense label (112 dp at 14 sp): the longest fleet name is 13 characters.
  assert.ok(['Mac mini', 'MacBook Air', 'Redmi Note 12', 'Cloud VPS', 'Relay Node', 'Tesla', 'Xiaomi TV', 'Add device'].every(n => n.length * 7 + 16 <= ORBIT_DENSE_LABEL_HALF * 2));
});

// ---- boundary hardening: orbit fit, dispatch draft, names, mixed states, routes ----

test('orbit regression: 1–4 slots never put a label across the phone orb (3 nodes + add overlapped it by 15 dp)', async () => {
  const {ORBIT_MIN_GAP, orbitClearance, orbitMargin} = await import('./connect.ts');
  // Pixel 9 orbit areas above the node card (560) and the taller car card (410), and a 360 dp phone.
  for (const [width, height] of [[411, 560], [411, 410], [360, 640], [400, 500]]) {
    for (let count = 1; count <= 4; count++) {
      const layout = orbitLayout(count, width, height);
      assert.equal(layout.slots.length, count);
      assert.ok(orbitClearance(layout) >= ORBIT_MIN_GAP, `${count}@${width}x${height}: clearance ${orbitClearance(layout)}`);
      assert.ok(orbitMargin(layout, width, height) >= 0, `${count}@${width}x${height}: off-canvas by ${-orbitMargin(layout, width, height)}`);
      // A 411 dp Pixel 9 keeps the circle; a 360 dp phone may take the ellipse with narrower labels instead.
      if (width === 411) assert.equal(layout.aspect, 1, `${count}@${width}x${height}: few nodes keep a circle`);
    }
  }
});

test('orbit regression: 5–6 slots keep the lowest label above the sheet on a short orbit area', async () => {
  const {ORBIT_MIN_GAP, orbitMargin, orbitClearance} = await import('./connect.ts');
  // 6 slots at 411x410 used to put the bottom label 6 dp behind the card.
  for (const count of [5, 6]) for (const height of [410, 470, 560]) {
    const layout = orbitLayout(count, 411, height);
    assert.ok(orbitMargin(layout, 411, height) >= 0, `${count}@${height}: off-canvas by ${-orbitMargin(layout, 411, height)}`);
    assert.ok(orbitClearance(layout) >= ORBIT_MIN_GAP, `${count}@${height}: clearance ${orbitClearance(layout)}`);
  }
});

test('orbit: an area too short for the circle falls back to the roomier ellipse, still on canvas', async () => {
  const {orbitMargin} = await import('./connect.ts');
  // The add sheet is tall: the orbit area shrinks to ~300 dp.
  for (let count = 1; count <= 8; count++) {
    const layout = orbitLayout(count, 411, 300);
    assert.ok(orbitMargin(layout, 411, 300) >= 0, `${count}: off-canvas by ${-orbitMargin(layout, 411, 300)}`);
  }
});

test('orbit: 0 nodes, degenerate canvases and the broker maximum stay finite and on canvas', async () => {
  const {orbitMargin} = await import('./connect.ts');
  assert.deepEqual(orbitLayout(0, 411, 560).slots, []);
  // The page always shows the add bubble: 0 nodes is one slot.
  assert.equal(orbitLayout(1, 411, 560).slots.length, 1);
  for (const [width, height] of [[0, 0], [1, 1], [120, 0], [120, 120], [411, 0], [0, 560]]) {
    for (let count = 0; count <= 34; count++) {
      const layout = orbitLayout(count, width, height);
      assert.equal(layout.slots.length, count);
      const values = [layout.center.x, layout.center.y, ...layout.radii, layout.aspect, ...layout.slots.flatMap(s => [s.x, s.y])];
      assert.ok(values.every(Number.isFinite), `${count}@${width}x${height}: non-finite geometry`);
      assert.ok(layout.radii.every(r => r >= 0));
    }
  }
  // 32 nodes (TakuConnect.MAX_NODES) + 2 demos + add: crowded, but every bubble and label stays on screen.
  for (let count = 9; count <= 35; count++) {
    const layout = orbitLayout(count, 411, 560);
    assert.ok(orbitMargin(layout, 411, 560) >= 0, `${count}: off-canvas by ${-orbitMargin(layout, 411, 560)}`);
  }
});

test('orbit: the same inputs always give the same layout', () => {
  for (const count of [1, 3, 4, 6, 8]) assert.deepEqual(orbitLayout(count, 411, 470), orbitLayout(count, 411, 470));
});

test('dispatch draft regression: `$` patterns and `{id}` in a device name stay literal', () => {
  const named = displayNameValue => ({id: 'taku_box', appearance: {displayName: displayNameValue}});
  // String.prototype.replace reads `$&` as the match and `$'` as the text after it.
  assert.equal(dispatchDraft(named('Box $& $$ $\''), null, 'en'), 'On Box $& $$ $\' (taku_box), please: ');
  assert.equal(dispatchDraft(named('价格 $` 盒子'), null, 'zh'), '请在「价格 $` 盒子」（taku_box）上：');
  // The second replace used to fill a `{id}` inside the name and leave the template's own `{id}`.
  assert.equal(dispatchDraft(named('{id} box'), null, 'en'), 'On {id} box (taku_box), please: ');
  assert.equal(dispatchDraft(named('{name}'), null, 'zh'), '请在「{name}」（taku_box）上：');
});

test('orbit name falls back from shortName to the display name, the device, then the bare id', async () => {
  const {orbitName} = await import('./connect.ts');
  assert.equal(orbitName({id: 'demo_tesla', appearance: {shortName: '  Tesla  ', displayName: 'Tesla Model Y'}}), 'Tesla');
  assert.equal(orbitName({id: 'demo_tesla', appearance: {shortName: '   ', displayName: 'Tesla Model Y'}}), 'Tesla Model Y');
  assert.equal(orbitName({id: 'x', appearance: {shortName: 42, displayName: 'Mini'}}), 'Mini', 'a non-string shortName from JSON is ignored');
  assert.equal(orbitName({id: 'taku_mac_mini', appearance: {}}, {status: 'offline', online: false}), 'mac mini');
  assert.equal(orbitName({id: 'taku_mac_mini', appearance: {}}, online), 'HaiPro 的 Mac mini', 'advertised display name');
  assert.equal(orbitName({id: 'taku_', appearance: {}}), 'taku_', 'never an empty label');
  assert.equal(orbitName({id: 'taku_air', appearance: {shortName: 'Air', displayName: 'MacBook Air'}}, online), 'Air', 'the phone shortName beats the advertised name');
});

test('mixed fleet: online, offline, rejected, unprobed and demo nodes resolve to one state each', async () => {
  const {probeFor} = await import('./connect.ts');
  const nodes = [
    {...mac, id: 'taku_on'}, {...mac, id: 'taku_off'}, {...mac, id: 'taku_bad'}, {...mac, id: 'taku_new'},
    {...mac, id: 'taku_disabled', enabled: false}, tesla, {...xiaomi, enabled: false},
  ];
  const probes = {taku_on: online, taku_off: {status: 'offline', online: false}, taku_bad: {status: 'unauthorized', online: true}, taku_disabled: online};
  const states = nodes.map(n => nodeState(n, probeFor(n, probes)));
  assert.deepEqual(states, ['online', 'offline', 'unauthorized', 'checking', 'disabled', 'online', 'disabled']);
  assert.deepEqual(states.map(isLive), [true, false, false, false, false, true, false]);
  // The phone orb pulses while any node is live; a fleet of offline/off nodes leaves it still.
  assert.equal(nodes.slice(1, 5).some(n => isLive(nodeState(n, probeFor(n, probes)))), false);
  assert.deepEqual(nodes.map(n => statusLine(n, probeFor(n, probes), 'zh')),
    ['已连接 · 局域网 2ms', '离线', '密钥无效', '正在检测…', '已关闭', '已连接 · 82% · 410 km', '已关闭']);
  assert.equal(statusLine({...tesla, demoStatus: '   '}, probeFor(tesla, {}), 'en'), 'Online', 'blank demo detail: no dangling separator');
});

test('routes: demo nodes, unknown kinds, unselected probes and empty probe routes', () => {
  assert.deepEqual(routeRows({...tesla, url: ''}, null, 'en').map(r => [r.kind, r.url, r.tone]), [['internet', '', 'unknown']],
    'a demo node with routes [] keeps one quiet row');
  assert.deepEqual(routeRows({...mac, network: 'loopback'}, null, 'zh').map(r => r.label), ['局域网']);
  const odd = {...routed, routes: [{kind: 'moon', url: 'http://m:1', status: 'online', latencyMs: 3, selected: false}]};
  assert.deepEqual(routeRows(multi, odd, 'en').map(r => [r.kind, r.label, r.tone, r.selected]), [['internet', 'Internet', 'standby', false]]);
  assert.deepEqual(routeRows(multi, {...routed, routes: []}, 'en').map(r => r.tone), ['unknown', 'unknown', 'unknown'],
    'a probe without routes falls back to the configured list');
  const none = {...routed, routes: routed.routes.map(r => ({...r, selected: false}))};
  assert.equal(routeRows(multi, none, 'en').some(r => r.selected), false);
  assert.deepEqual(routeRows(multi, none, 'en').map(r => r.label), ['书房', 'IPv6', 'Mac mini 转发'], 'no selection: configured order');
  // Switching the selected route reorders the list without losing a row.
  const switched = {...routed, routes: routed.routes.map((r, i) => ({...r, status: 'online', latencyMs: 5 + i, selected: i === 0}))};
  assert.deepEqual(routeRows(multi, switched, 'zh').map(r => [r.label, r.tone, r.latency]),
    [['书房', 'selected', '5ms'], ['IPv6', 'standby', '6ms'], ['Mac mini 转发', 'standby', '7ms']]);
});

test('latency regression: only known networks name the line; bad numbers give no latency', () => {
  assert.equal(latencyLabel({...online, network: 'toString'}, 'en'), 'Internet 2ms', '`in` accepted Object.prototype keys');
  assert.equal(latencyLabel({...online, network: 'online'}, 'en'), 'Internet 2ms', '`in` accepted label keys');
  assert.equal(latencyLabel({...online, network: 'loopback'}, 'zh'), '本机 2ms');
  assert.equal(latencyLabel({...online, latencyMs: Number.NaN}, 'en'), '');
  assert.equal(latencyLabel({...online, latencyMs: -3}, 'en'), 'LAN 0ms');
  assert.equal(latencyLabel({...online, latencyMs: undefined}, 'en'), '');
});

test('error code regression: prototype keys are unknown codes, not functions', () => {
  for (const code of ['toString', 'constructor', '__proto__', 'hasOwnProperty']) {
    assert.equal(connectError(code, 'en'), `Failed (${code})`);
    assert.equal(connectError(code, 'zh'), `操作失败（${code}）`);
  }
});
