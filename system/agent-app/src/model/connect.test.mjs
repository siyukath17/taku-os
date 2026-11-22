import assert from 'node:assert/strict';
import test from 'node:test';
import {Buffer} from 'node:buffer';
import {
  capabilityChips, connectError, customIcon, dispatchDraft, displayName, effectiveDeviceType, isLive, latencyLabel,
  nodeState, normalizeUrl, orbitLayout, resolveIcon, stateTone, statusLine, validateAddForm,
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
  assert.equal(statusLine(mac, online, 'zh'), '在线 · 局域网 2ms');
  assert.equal(statusLine(mac, {status: 'offline', online: false}, 'zh'), '离线');
  assert.equal(statusLine({enabled: false}, online, 'en'), 'Off');
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

test('orbit layout staggers two rings around the centre', () => {
  const {center, radii, slots} = orbitLayout(6, 400, 500);
  assert.deepEqual(center, {x: 200, y: 250});
  assert.equal(slots.length, 6);
  assert.deepEqual(slots.map(s => s.ring), [0, 0, 0, 0, 1, 1]);
  for (const slot of slots) {
    const r = Math.hypot(slot.x - center.x, slot.y - center.y);
    assert.ok(Math.abs(r - radii[slot.ring]) < 1e-6);
  }
  assert.deepEqual(orbitLayout(0, 100, 100).slots, []);
});

test('error codes become readable messages', () => {
  assert.equal(connectError('NODE_UNAUTHORIZED', 'zh'), '密钥不正确');
  assert.equal(connectError('WEIRD', 'en'), 'Failed (WEIRD)');
});
