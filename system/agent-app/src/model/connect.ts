/**
 * Taku Connect: the phone's taku-runtime nodes (gateway `connect/*`, broker `TakuConnect`).
 * Pure presentation logic — icon resolution, node state, labels, the add form and the orbit layout —
 * so the screen stays a thin view and every rule here is unit-tested.
 */
import type {Language} from '../i18n';

export const deviceTypes = ['mac_mini', 'laptop', 'desktop', 'tv', 'server', 'phone', 'tablet', 'speaker', 'car', 'generic'] as const;
export type DeviceType = typeof deviceTypes[number];

/** Phone-side (connect.json) or device-advertised (`capabilities.appearance`) presentation. */
export interface ConnectAppearance {deviceType?: string; displayName?: string; shortName?: string; icon?: string}

/** `connect/list` → `nodes[]`. Tokens never reach the app: only `hasToken`. `url` is the first route. */
export interface ConnectNode {
  id: string; url: string; host: string; network: Network; enabled: boolean; hasToken: boolean; appearance: ConnectAppearance;
  routes?: ConnectRoute[];
  /** A showcase node from connect.json (`demo_*`): no runtime entry, so the agent has no tools for it. */
  demo?: boolean;
  /** Its status detail, e.g. `82% · 410 km`. */
  demoStatus?: string;
}
export type Network = 'lan' | 'ipv6' | 'tailscale' | 'loopback' | 'internet';

/** A candidate route, in preference order (lan → ipv6 → tailscale → relay). `relay`: frp or a LAN forwarder. */
export const routeKinds = ['lan', 'ipv6', 'tailscale', 'relay', 'internet'] as const;
export type RouteKind = typeof routeKinds[number];
export interface ConnectRoute {label?: string; kind: RouteKind; url: string}
/** One route in a probe: the broker picks the route the taku client will use (`selected`). */
/** `standby`: a backup still answering when the choice settled (25 ms after the first live route); not known dead. */
export interface RouteProbe extends ConnectRoute {status: 'online' | 'offline' | 'unauthorized' | 'standby'; latencyMs?: number; error?: string; selected: boolean}

export interface ConnectCapabilities {
  runtime?: string; version?: string; device?: string; os?: string; arch?: string; mode?: string;
  engine?: {name: string; protocol: string};
  tools?: {name: string; description?: string}[];
  reservedTools?: string[]; protocols?: string[];
  appearance?: ConnectAppearance;
  exec?: {enabled: boolean; allowedRoots: string[]; defaultTimeoutMs: number; maxTimeoutMs: number; maxOutputBytes: number};
}

/** `connect/probe` / `connect/test`. `url`/`network`/`latencyMs` describe the selected `route`. */
export interface ConnectProbe {
  id?: string; url?: string; status: 'online' | 'unauthorized' | 'offline'; online: boolean; latencyMs?: number;
  network?: Network; error?: string; checkedAt?: number; enabled?: boolean; capabilities?: ConnectCapabilities;
  route?: RouteProbe; routes?: RouteProbe[];
}

// ---- three-tier icon resolution ----

export type IconSource =
  | {tier: 'custom'; kind: 'svg-data'; svg: string}
  | {tier: 'custom'; kind: 'svg-url' | 'image'; uri: string}
  | {tier: 'builtin'; key: Exclude<DeviceType, 'generic'>}
  | {tier: 'generic'; key: 'generic'};

const isDeviceType = (value: unknown): value is DeviceType => typeof value === 'string' && (deviceTypes as readonly string[]).includes(value);

function decodeBase64(text: string): string | null {
  try {
    if (typeof atob === 'function') return decodeURIComponent(Array.from(atob(text), c => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`).join(''));
  } catch {
    return null;
  }
  return null;
}

/** One `icon` value → a source, or null when it is unusable (then the next tier applies). */
export function customIcon(icon: unknown): IconSource | null {
  if (typeof icon !== 'string') return null;
  const value = icon.trim();
  if (!value) return null;
  if (value.startsWith('builtin:')) {
    const key = value.slice(8);
    return isDeviceType(key) ? (key === 'generic' ? {tier: 'generic', key} : {tier: 'builtin', key}) : null;
  }
  if (/^data:image\/svg\+xml;base64,[A-Za-z0-9+/=]+$/.test(value)) {
    const svg = decodeBase64(value.slice(value.indexOf(',') + 1));
    return svg && svg.includes('<svg') ? {tier: 'custom', kind: 'svg-data', svg} : null;
  }
  if (/^data:image\/svg\+xml;utf8,/.test(value)) {
    const svg = decodeURIComponent(value.slice(value.indexOf(',') + 1));
    return svg.includes('<svg') ? {tier: 'custom', kind: 'svg-data', svg} : null;
  }
  if (/^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+$/.test(value)) return {tier: 'custom', kind: 'image', uri: value};
  if (/^https?:\/\/[^\s]+$/.test(value)) return {tier: 'custom', kind: /\.svg(\?|#|$)/i.test(value) ? 'svg-url' : 'image', uri: value};
  return null;
}

/**
 * Tier 1: a custom icon — the phone's override first (set by the user or an Agent through
 * `connect/appearance`), then the device's own `capabilities.appearance.icon`.
 * Tier 2: the built-in drawing for the device type (phone override, then advertised).
 * Tier 3: the generic hardware module.
 */
export function resolveIcon(node: Pick<ConnectNode, 'appearance'> | null | undefined, probe?: ConnectProbe | null): IconSource {
  const local = node?.appearance ?? {};
  const remote = probe?.capabilities?.appearance ?? {};
  for (const icon of [local.icon, remote.icon]) {
    const source = customIcon(icon);
    if (source) return source;
  }
  for (const type of [local.deviceType, remote.deviceType]) {
    if (isDeviceType(type)) return type === 'generic' ? {tier: 'generic', key: 'generic'} : {tier: 'builtin', key: type};
  }
  return {tier: 'generic', key: 'generic'};
}

/** The device type the page treats the node as (for labels and the add form). */
export function effectiveDeviceType(node: Pick<ConnectNode, 'appearance'>, probe?: ConnectProbe | null): DeviceType {
  for (const type of [node.appearance?.deviceType, probe?.capabilities?.appearance?.deviceType]) if (isDeviceType(type)) return type;
  return 'generic';
}

export function displayName(node: Pick<ConnectNode, 'id' | 'appearance'>, probe?: ConnectProbe | null): string {
  const names = [node.appearance?.displayName, probe?.capabilities?.appearance?.displayName, probe?.capabilities?.device];
  for (const name of names) if (typeof name === 'string' && name.trim()) return name.trim();
  const bare = node.id.replace(/^taku_/, '').replace(/_/g, ' ');
  return bare || node.id;
}

/** The name under a bubble on the orbit: the short name when set (`Tesla` for `Tesla Model Y`). */
export function orbitName(node: Pick<ConnectNode, 'id' | 'appearance'>, probe?: ConnectProbe | null): string {
  const short = node.appearance?.shortName;
  return typeof short === 'string' && short.trim() ? short.trim() : displayName(node, probe);
}

// ---- demo nodes and device cards ----

/** A demo node never touches the network: it is online exactly while switched on (as the broker answers). */
export function demoProbe(node: Pick<ConnectNode, 'id' | 'enabled'>): ConnectProbe {
  return {id: node.id, status: node.enabled ? 'online' : 'offline', online: node.enabled, enabled: node.enabled};
}

/** The probe the page uses for a node: demo nodes are answered locally, real ones by `connect/probe`. */
export const probeFor = (node: ConnectNode, probes: Record<string, ConnectProbe>): ConnectProbe | undefined =>
  node.demo ? demoProbe(node) : probes[node.id];

export type CardKind = 'car' | 'tv' | 'standard';

/**
 * Which bottom-sheet card a node gets. Device cards (car, TV) carry controls the agent cannot
 * perform on a real node yet, so only demo nodes get them; every real node keeps the standard card
 * with its live capabilities and routes.
 */
export function cardKind(node: Pick<ConnectNode, 'demo' | 'appearance'>, probe?: ConnectProbe | null): CardKind {
  if (!node.demo) return 'standard';
  const type = effectiveDeviceType(node, probe);
  return type === 'car' ? 'car' : type === 'tv' ? 'tv' : 'standard';
}

// ---- node state ----

export type NodeState = 'checking' | 'online' | 'unauthorized' | 'offline' | 'disabled';
export type StateTone = 'success' | 'attention' | 'error' | 'muted';

/** A switched-off node is `disabled` whatever its probe says; no probe yet is `checking`. */
export function nodeState(node: Pick<ConnectNode, 'enabled'>, probe?: ConnectProbe | null): NodeState {
  if (!node.enabled) return 'disabled';
  if (!probe) return 'checking';
  return probe.status;
}

export function stateTone(state: NodeState): StateTone {
  return state === 'online' ? 'success' : state === 'unauthorized' ? 'attention' : state === 'offline' ? 'error' : 'muted';
}

/** The connection line and pulse run only to a node the agent can actually use right now. */
export const isLive = (state: NodeState) => state === 'online';

// ---- labels ----

const text = {
  en: {
    lan: 'LAN', ipv6: 'IPv6', tailscale: 'Tailscale', relay: 'Relay', loopback: 'This device', internet: 'Internet',
    routes: 'Routes', routesAuto: 'Automatically picks the fastest route',
    online: 'Online', checking: 'Checking…', unauthorized: 'Token rejected', offline: 'Offline', disabled: 'Off',
    ai: 'Local AI', terminal: 'Terminal', files: 'Files', screen: 'Screen', remote: 'Remote control', system: 'System info',
    dispatch: 'On {name} ({id}), please: ',
  },
  zh: {
    lan: '局域网', ipv6: 'IPv6', tailscale: 'Tailscale', relay: '中转', loopback: '本机', internet: '公网',
    routes: '路线', routesAuto: '会自动匹配最优路线',
    online: '已连接', checking: '正在检测…', unauthorized: '密钥无效', offline: '离线', disabled: '已关闭',
    ai: '本地 AI', terminal: '终端', files: '文件', screen: '屏幕', remote: '遥控', system: '系统信息',
    dispatch: '请在「{name}」（{id}）上：',
  },
} as const;
type Key = keyof typeof text.en;
export const connectLabel = (language: Language, key: Key) => text[language][key];

const isRouteKind = (value: unknown): value is RouteKind => typeof value === 'string' && (routeKinds as readonly string[]).includes(value);

/** `局域网` / `LAN`, `中转` / `Relay`, …; unknown kinds read as Internet. */
export const routeKindLabel = (kind: unknown, language: Language) => connectLabel(language, isRouteKind(kind) ? kind : 'internet');

/** A route's name in the routes list: the user's label when set, else its kind. */
export function routeLabel(route: Pick<ConnectRoute, 'label' | 'kind'>, language: Language): string {
  return route.label?.trim() || routeKindLabel(route.kind, language);
}

const networks: readonly string[] = ['lan', 'ipv6', 'tailscale', 'loopback', 'internet'] satisfies Network[];
/** Only a known network names the line: `in` would also accept label keys (`online`) and prototype keys (`toString`). */
const isNetwork = (value: unknown): value is Network => typeof value === 'string' && networks.includes(value);

const ms = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? `${Math.max(0, Math.round(value))}ms` : '';

/** `局域网 2ms` / `Relay 45ms` for the selected route (its kind, for a consistent status line); '' without a latency. */
export function latencyLabel(probe: ConnectProbe | null | undefined, language: Language): string {
  if (!probe?.online) return '';
  const latency = ms(probe.route?.latencyMs ?? probe.latencyMs);
  if (!latency) return '';
  const name = probe.route ? routeKindLabel(probe.route.kind, language)
    : connectLabel(language, isNetwork(probe.network) ? probe.network : 'internet');
  return `${name} ${latency}`;
}

/** `已连接 · 局域网 2ms`, `离线`, … */
export function statusLine(node: Pick<ConnectNode, 'enabled' | 'demoStatus'>, probe: ConnectProbe | null | undefined, language: Language): string {
  const state = nodeState(node, probe);
  const latency = state === 'online' ? (node.demoStatus?.trim() || latencyLabel(probe, language)) : '';
  return latency ? `${connectLabel(language, state)} · ${latency}` : connectLabel(language, state);
}

export type RouteTone = 'selected' | 'standby' | 'offline' | 'attention' | 'unknown';
export interface RouteRow {url: string; label: string; kind: RouteKind; latency: string; selected: boolean; tone: RouteTone}

/**
 * The routes list of a node: the selected route first, the backups after it in their configured
 * order. Rows come from the last probe when it carries routes, otherwise from the node's routes
 * (not yet probed: tone `unknown`), else its single url.
 */
export function routeRows(node: Pick<ConnectNode, 'url' | 'network' | 'routes' | 'enabled'>, probe: ConnectProbe | null | undefined, language: Language): RouteRow[] {
  const fallback: RouteKind = node.network === 'loopback' ? 'lan' : isRouteKind(node.network) ? node.network : 'internet';
  const configured: ConnectRoute[] = node.routes?.length ? node.routes : [{kind: fallback, url: node.url}];
  const probed = probe?.routes?.length ? probe.routes : null;
  const rows = (probed ?? configured).map((route): RouteRow => {
    const result = probed ? route as RouteProbe : null;
    const selected = Boolean(result?.selected) && node.enabled;
    const tone: RouteTone = !result ? 'unknown' : result.status === 'offline' ? 'offline'
      : selected ? (result.status === 'unauthorized' ? 'attention' : 'selected') : 'standby';
    return {url: route.url, label: routeLabel(route, language), kind: isRouteKind(route.kind) ? route.kind : 'internet',
      latency: result && result.status !== 'offline' ? ms(result.latencyMs) : '', selected, tone};
  });
  return [...rows.filter(row => row.selected), ...rows.filter(row => !row.selected)];
}

/** `192.168.1.205:7878`, `[fd7a::1]:7878`: a url without its scheme. */
export const hostPort = (url: string) => url.replace(/^[a-z]+:\/\//i, '');

export type CapabilityChip = 'ai' | 'terminal' | 'files' | 'screen' | 'remote' | 'system';

/** Read-only chips for what the node really offers now (reserved tools are not offered). */
export function capabilityChips(probe: ConnectProbe | null | undefined): CapabilityChip[] {
  const caps = probe?.capabilities;
  if (!caps || probe?.status !== 'online') return [];
  const tools = new Set((caps.tools ?? []).map(tool => tool.name));
  const chips: CapabilityChip[] = [];
  if (caps.mode === 'autonomous' && caps.engine) chips.push('ai');
  if (caps.exec?.enabled || tools.has('shell_exec')) chips.push('terminal');
  if (tools.has('file_read') || tools.has('file_write')) chips.push('files');
  if (tools.has('screen_capture') || tools.has('computer_use')) chips.push('screen');
  if (tools.has('tv_remote')) chips.push('remote');
  if (tools.has('system_info')) chips.push('system');
  return chips;
}

/** The composer text for "dispatch a task": names the device so the agent picks its tools. */
export function dispatchDraft(node: Pick<ConnectNode, 'id' | 'appearance'>, probe: ConnectProbe | null | undefined, language: Language): string {
  // One pass with a replacer function: the name is user- or device-provided, so `$&`/`$'` in it must stay literal
  // and a `{id}` inside it must not be filled in.
  const values = {name: displayName(node, probe), id: node.id};
  return connectLabel(language, 'dispatch').replace(/\{(name|id)\}/g, (_match, key: 'name' | 'id') => values[key]);
}

// ---- add form ----

export interface AddForm {name: string; url: string; tokenMode: 'token' | 'file'; tokenFile: string; tokenTyped: boolean}
export type AddError = 'nameRequired' | 'nameTooLong' | 'urlRequired' | 'urlInvalid' | 'tokenRequired' | 'tokenFileInvalid';
export interface AddValidation {ok: boolean; errors: Partial<Record<'name' | 'url' | 'token', AddError>>; url: string}

/** Mirrors the broker's URL rules: http(s), host, optional port (default 7878), nothing else. */
export function normalizeUrl(raw: string): string | null {
  let value = raw.trim().replace(/\/+$/, '');
  if (!value) return null;
  if (!value.includes('://')) value = `http://${value}`;
  const match = /^(https?):\/\/(\[[0-9a-fA-F:]+\]|[A-Za-z0-9.-]+)(?::(\d{1,5}))?$/i.exec(value);
  if (!match) return null;
  const port = match[3] ? Number(match[3]) : 7878;
  if (port < 1 || port > 65535) return null;
  const host = match[2];
  if (host.startsWith('.') || host.endsWith('.') || host.includes('..')) return null;
  return `${match[1].toLowerCase()}://${host}:${port}`;
}

export const TOKEN_FILE_PREFIX = '/data/local/agentos/';

export function validateAddForm(form: AddForm): AddValidation {
  const errors: AddValidation['errors'] = {};
  const name = form.name.trim();
  if (!name) errors.name = 'nameRequired';
  else if (name.length > 64) errors.name = 'nameTooLong';
  const url = normalizeUrl(form.url);
  if (!form.url.trim()) errors.url = 'urlRequired';
  else if (!url) errors.url = 'urlInvalid';
  if (form.tokenMode === 'token' && !form.tokenTyped) errors.token = 'tokenRequired';
  if (form.tokenMode === 'file') {
    const file = form.tokenFile.trim();
    if (!file) errors.token = 'tokenRequired';
    else if (!file.startsWith(TOKEN_FILE_PREFIX) || file.includes('/../') || file.includes('/./')) errors.token = 'tokenFileInvalid';
  }
  return {ok: Object.keys(errors).length === 0, errors, url: url ?? ''};
}

// ---- probing ----

/**
 * Concurrent probes per page round. The Agent UI gateway admits 4 calls at once and answers the rest
 * `BUSY` immediately, so a round must leave room for the page's other calls (list, toggles).
 */
export const PROBE_CONCURRENCY = 3;

/** Runs [fn] over [items] with at most [limit] in flight, preserving nothing but completion. */
export async function forEachLimited<T>(items: readonly T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  const lane = async () => { while (next < items.length) await fn(items[next++]); };
  await Promise.all(Array.from({length: Math.max(1, Math.min(limit, items.length))}, lane));
}

/** A gateway refusal for load (`BUSY`) says nothing about the device: retry, never show it offline. */
export const isTransientProbeError = (code: unknown) => code === 'BUSY';

// ---- orbit layout ----

export interface OrbitSlot {x: number; y: number; ring: 0 | 1}

/** Half the width of a bubble's name label (ConnectScreen `bubbleLabel`). */
export const ORBIT_LABEL_HALF = 75;

/**
 * Positions around the centre (Taku Phone); the add bubble is always last. Up to 4 slots sit on the
 * inner ring (the outer one when the inner cannot keep their labels off the orb). 5–6 slots share one evenly spaced ring: the two rings are only ~1.3× apart, so a bubble
 * on each (plus its name label below) would overlap. The single ring's radius keeps the side labels
 * on the canvas. 7+ slots use one elliptical ring ({@link denseOrbit}) with narrower labels.
 */
export interface OrbitLayout {
  center: {x: number; y: number}; radii: [number, number]; slots: OrbitSlot[];
  /** Horizontal stretch of the rings (rx = r × aspect); 1 = circles. */
  aspect: number;
  /** Half width a bubble's name label may take (the label's max width / 2). */
  labelHalf: number;
}

/** Room every bubble and label keeps from the others and the centre orb when the canvas allows it. */
export const ORBIT_MIN_GAP = 4;

/** Labels on a dense orbit (7+ slots) are capped narrower so neighbours never touch. */
export const ORBIT_DENSE_LABEL_HALF = 56;
const BUBBLE_R = 32, LABEL_TOP = 38, LABEL_BOTTOM = 60, ORB_R = 42, ORB_LABEL = {half: 56, top: 50, bottom: 74};

interface Box {x0: number; x1: number; y0: number; y1: number}
/** A node's footprint: its bubble and the one-line label under it. */
export function slotBoxes(x: number, y: number, labelHalf: number): Box[] {
  return [{x0: x - BUBBLE_R, x1: x + BUBBLE_R, y0: y - BUBBLE_R, y1: y + BUBBLE_R}, {x0: x - labelHalf, x1: x + labelHalf, y0: y + LABEL_TOP, y1: y + LABEL_BOTTOM}];
}
/** The centre orb and its "Taku Phone" label (~96 dp wide). */
export function orbBoxes(x: number, y: number): Box[] {
  return [{x0: x - ORB_R, x1: x + ORB_R, y0: y - ORB_R, y1: y + ORB_R}, {x0: x - ORB_LABEL.half, x1: x + ORB_LABEL.half, y0: y + ORB_LABEL.top, y1: y + ORB_LABEL.bottom}];
}
/** Clearance between two boxes along the most separating axis; ≤ 0 means they overlap. */
export const boxGap = (a: Box, b: Box) => Math.max(a.x0 - b.x1, b.x0 - a.x1, a.y0 - b.y1, b.y0 - a.y1);
/** The smallest clearance among all slots, the centre orb and each other. */
export function orbitClearance(layout: Pick<OrbitLayout, 'center' | 'slots' | 'labelHalf'>): number {
  const groups = [orbBoxes(layout.center.x, layout.center.y), ...layout.slots.map(s => slotBoxes(s.x, s.y, layout.labelHalf))];
  let min = Infinity;
  for (let i = 0; i < groups.length; i++) for (let j = i + 1; j < groups.length; j++)
    for (const a of groups[i]) for (const b of groups[j]) min = Math.min(min, boxGap(a, b));
  return min;
}

/** The smallest room between any slot's bubble or label and the canvas edges; < 0 means something is off-canvas. */
export function orbitMargin(layout: Pick<OrbitLayout, 'slots' | 'labelHalf'>, width: number, height: number): number {
  let min = Infinity;
  for (const s of layout.slots) {
    for (const box of slotBoxes(s.x, s.y, layout.labelHalf)) min = Math.min(min, box.x0, width - box.x1, box.y0, height - box.y1);
  }
  return min;
}

/**
 * How well a layout fits: ≥ {@link ORBIT_MIN_GAP} when every bubble and label clears the others and the orb by
 * that much and stays on the canvas (a label's max-width box may touch an edge: its centred text is narrower).
 */
const orbitFit = (layout: OrbitLayout, width: number, height: number) =>
  Math.min(orbitClearance(layout), orbitMargin(layout, width, height) + ORBIT_MIN_GAP);

/**
 * 7+ slots: one evenly spaced elliptical ring filling the free area (taller than wide is fine),
 * rotated to the start angle that leaves the most room between every bubble, label and the centre.
 */
function denseOrbit(count: number, width: number, height: number): OrbitLayout {
  const center = {x: width / 2, y: height / 2};
  const labelHalf = ORBIT_DENSE_LABEL_HALF;
  const ry = Math.max(0, Math.min(height / 2 - LABEL_BOTTOM - 4, height / 2 - BUBBLE_R - 4));
  const step = (2 * Math.PI) / count;
  let best: OrbitLayout | null = null, bestGap = -Infinity;
  for (let k = 0; k < 48; k++) {
    const offset = (step * k) / 48;
    const angles = Array.from({length: count}, (_, i) => -Math.PI / 2 + offset + step * i);
    const widest = Math.max(...angles.map(a => Math.abs(Math.cos(a))));
    // A lone slot straight up or down has no horizontal reach (widest 0): only the bubble bound applies.
    const reach = widest > 1e-9 ? (width / 2 - labelHalf - 4) / widest : Infinity;
    const rx = Math.max(0, Math.min(reach, width / 2 - BUBBLE_R - 4));
    const slots = angles.map(a => ({x: center.x + rx * Math.cos(a), y: center.y + ry * Math.sin(a), ring: 1 as const}));
    const layout: OrbitLayout = {center, radii: [ry * 0.62, ry], slots, aspect: ry > 0 ? rx / ry : 1, labelHalf};
    const gap = orbitClearance(layout);
    if (gap > bestGap + 1e-9) { best = layout; bestGap = gap; }
  }
  return best!;
}

/**
 * The designed layout for the count when it fits the canvas with {@link ORBIT_MIN_GAP} to spare;
 * otherwise (a short orbit area above a tall sheet) whichever of it and the elliptical ring leaves more room.
 */
export function orbitLayout(count: number, width: number, height: number): OrbitLayout {
  if (count >= 7) return denseOrbit(count, width, height);
  const classic = classicOrbit(count, width, height);
  if (count === 0 || orbitFit(classic, width, height) >= ORBIT_MIN_GAP) return classic;
  const dense = denseOrbit(count, width, height);
  return orbitFit(dense, width, height) > orbitFit(classic, width, height) + 1e-9 ? dense : classic;
}

function classicOrbit(count: number, width: number, height: number): OrbitLayout {
  const center = {x: width / 2, y: height / 2};
  const span = Math.max(0, Math.min(width, height));
  // Inner ring clear of the centre orb and its pulse; outer ring kept inside the canvas width.
  const outer = Math.max(0, Math.min(span * 0.47, width / 2 - 44));
  const radii: [number, number] = [Math.min(span * 0.34, outer * 0.78), outer];
  const slots: OrbitSlot[] = [];
  if (count === 5 || count === 6) {
    // Bound the widest horizontal offset by the label half-width so names never run off the sides.
    const step = (2 * Math.PI) / count;
    let widest = 0;
    for (let i = 0; i < count; i++) widest = Math.max(widest, Math.abs(Math.sin(step * i)));
    // …and the lowest label (or highest bubble) inside the orbit area, so none hides behind the sheet or header.
    let below = 0, above = 0;
    for (let i = 0; i < count; i++) {
      const sin = Math.sin(-Math.PI / 2 + step * i);
      below = Math.max(below, sin);
      above = Math.max(above, -sin);
    }
    const vertical = Math.min((height / 2 - LABEL_BOTTOM - ORBIT_MIN_GAP) / below, (height / 2 - BUBBLE_R - ORBIT_MIN_GAP) / above);
    const ring = Math.max(radii[0], Math.min(outer, (width / 2 - ORBIT_LABEL_HALF) / widest, vertical));
    radii[1] = ring;
    for (let i = 0; i < count; i++) {
      const angle = -Math.PI / 2 + step * i;
      slots.push({x: center.x + ring * Math.cos(angle), y: center.y + ring * Math.sin(angle), ring: 1});
    }
    return {center, radii, slots, aspect: 1, labelHalf: ORBIT_LABEL_HALF};
  }
  // 1–4 slots on the inner ring, starting up-left (-90° - 36°). That start can leave a label across the centre
  // orb (3 nodes + add: the right slot's label ran 15 dp over it), so the ring turns the least it must for every
  // bubble and label to clear the orb, each other and the edges; if no turn of the inner ring clears, the outer
  // ring is tried the same way, and when neither clears the roomiest arrangement wins.
  if (count === 0) return {center, radii, slots, aspect: 1, labelHalf: ORBIT_LABEL_HALF};
  const step = (2 * Math.PI) / count;
  const ringAt = (ring: 0 | 1, offset: number): OrbitLayout => ({center, radii, aspect: 1, labelHalf: ORBIT_LABEL_HALF,
    slots: Array.from({length: count}, (_, i) => {
      const angle = -Math.PI / 2 - Math.PI / 5 + offset + step * i;
      return {x: center.x + radii[ring] * Math.cos(angle), y: center.y + radii[ring] * Math.sin(angle), ring};
    })});
  const turns = 96;
  let best = ringAt(0, 0), bestFit = orbitFit(best, width, height);
  for (const ring of [0, 1] as const) {
    // 0, +1, −1, +2, −2, … steps of step/96: the nearest turn to the designed start wins.
    for (let k = ring === 0 ? 1 : 0; k <= turns && bestFit < ORBIT_MIN_GAP; k++) {
      const offset = (k % 2 ? 1 : -1) * Math.ceil(k / 2) * step / turns;
      const candidate = ringAt(ring, offset), fit = orbitFit(candidate, width, height);
      if (fit > bestFit + 1e-9) { best = candidate; bestFit = fit; }
    }
  }
  return best;
}

/** User-facing message for a gateway/broker error code. */
export function connectError(code: string, language: Language): string {
  const zh: Record<string, string> = {
    NODE_UNREACHABLE: '连不上这台设备，请检查地址和网络', NODE_UNAUTHORIZED: '密钥不正确', TOKEN_REQUIRED: '请填写密钥',
    INVALID_URL: '地址格式不正确', INVALID_NAME: '名称不能为空且不超过 64 字', TOKEN_FILE_NOT_FOUND: '找不到密钥文件',
    TOKEN_FILE_LOCATION_NOT_ALLOWED: '密钥文件需位于 /data/local/agentos/ 下', TOO_MANY_NODES: '设备数量已达上限',
    REVISION_CONFLICT: '配置刚被修改，请重试', SECRET_EMPTY: '请填写密钥', NODE_NOT_FOUND: '设备已不存在',
  };
  const en: Record<string, string> = {
    NODE_UNREACHABLE: 'Cannot reach this device. Check the address and network.', NODE_UNAUTHORIZED: 'The token was rejected.',
    TOKEN_REQUIRED: 'Enter a token.', INVALID_URL: 'The address is not valid.', INVALID_NAME: 'Enter a name of up to 64 characters.',
    TOKEN_FILE_NOT_FOUND: 'The token file was not found.', TOKEN_FILE_LOCATION_NOT_ALLOWED: 'Token files must be under /data/local/agentos/.',
    TOO_MANY_NODES: 'Too many devices.', REVISION_CONFLICT: 'Settings just changed. Try again.', SECRET_EMPTY: 'Enter a token.',
    NODE_NOT_FOUND: 'This device no longer exists.',
  };
  const messages = language === 'zh' ? zh : en;
  // Own keys only: a code such as `toString` or `constructor` must not resolve to an Object.prototype member.
  return Object.prototype.hasOwnProperty.call(messages, code) ? messages[code] : language === 'zh' ? `操作失败（${code}）` : `Failed (${code})`;
}
