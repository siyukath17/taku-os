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

test('orbit layout staggers two rings around the centre', () => {
  const {center, radii, slots} = orbitLayout(8, 400, 500);
  assert.deepEqual(center, {x: 200, y: 250});
  assert.equal(slots.length, 8);
  assert.deepEqual(slots.map(s => s.ring), [0, 0, 0, 0, 1, 1, 1, 1]);
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
