/**
 * Taku Connect (Taku 互联): the phone's taku-runtime nodes on a star-orbit canvas. Taku Phone sits
 * in the centre, connected nodes orbit it, and a white bottom sheet shows the selected node —
 * live status and latency, read-only capability chips, its switch, "dispatch a task" and its
 * permissions — or the add-device form. All data comes from the gateway's `connect/*` actions
 * (the same path Agents use through open-access); nothing here is fixture data.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator, Animated, AppState, BackHandler, Easing, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Switch,
  Text, TextInput, View,
} from 'react-native';
import {useLocales} from 'expo-localization';
import {useRouter} from 'expo-router';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import Svg, {Circle, Ellipse, Line} from 'react-native-svg';
import {IconButton} from './Icon';
import {DeviceIcon} from './connect/DeviceIcon';
import {CarPanel, TvPanel} from './connect/DeviceCards';
import {languageFor, type Language} from '../i18n';
import {callAgent, type AgentGatewayError} from '../native/agent';
import {SecretField, type SecretFieldHandle} from '../native/SecretField';
import {
  capabilityChips, cardKind, connectError, connectLabel, dispatchDraft, displayName, effectiveDeviceType, forEachLimited, hostPort, isLive,
  isTransientProbeError, nodeState, orbitLayout, orbitName, PROBE_CONCURRENCY, probeFor,
  resolveIcon, routeRows, stateTone, statusLine, validateAddForm, type ConnectNode, type ConnectProbe, type NodeState, type RouteRow,
  type RouteTone, type StateTone,
} from '../model/connect';
import {queueConnectDispatch} from '../model/connectHandoff';
import {demoDispatchNote} from '../model/deviceCards';
import {colors, containsChinese, typography, type TypeRole} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';

const PROBE_MS = 10_000;
const BUBBLE = 64, ORB = 84;
type Sheet = 'node' | 'add' | 'permissions';
type TypeFor = (role: TypeRole, content?: string) => object;

const copy = {
  zh: {
    title: 'Taku 互联', back: '返回', refresh: '刷新', phone: 'Taku Phone', add: '添加设备', thisPhone: '本机 · 主控',
    phoneHint: '已接入 {n} 台设备。点按设备查看状态，或添加新的 taku-runtime 设备。', dispatch: '派发任务', permissions: '权限',
    enabled: '允许 Agent 使用', permissionsTitle: '权限与配置', token: '密钥',
    tokenStored: '已保存在系统中，App 不可读取', tokenMissing: '未配置', exec: '命令执行', execOn: '已开启', execOff: '已关闭',
    roots: '允许目录', timeout: '超时', timeoutValue: '默认 {d}，最长 {m}', output: '单次输出上限', tools: '可用工具', reserved: '预留能力',
    platform: '系统', runtime: '运行时', none: '无', notReported: '连接后可见', name: '名称', namePlaceholder: '例如 客厅电视',
    url: '地址', urlPlaceholder: '192.168.1.30:7878', tokenMode: '密钥', fileMode: '密钥路径', tokenPlaceholder: '粘贴 taku-runtime 的 Token',
    filePlaceholder: '/data/local/agentos/acp/secrets/xxx.token', test: '测试连接', save: '保存', cancel: '取消',
    saved: '已添加，Agent 现在可以使用这台设备', empty: '还没有接入设备', loadFailed: '读取设备失败',
    nameRequired: '请填写名称', nameTooLong: '名称不超过 64 字', urlRequired: '请填写地址', urlInvalid: '地址格式不正确',
    tokenRequired: '请填写密钥', tokenFileInvalid: '密钥文件需位于 /data/local/agentos/ 下', mode: '模式',
    autonomous: '自主（有本地 Agent）', endpoint: '端点（仅执行）',
  },
  en: {
    title: 'Taku Connect', back: 'Back', refresh: 'Refresh', phone: 'Taku Phone', add: 'Add device', thisPhone: 'This phone · controller',
    phoneHint: '{n} devices connected. Tap one to see its status, or add another taku-runtime device.', dispatch: 'Dispatch task',
    permissions: 'Permissions', enabled: 'Let the Agent use it', permissionsTitle: 'Permissions & setup',
    token: 'Token', tokenStored: 'Stored by the system; not readable by apps', tokenMissing: 'Not set', exec: 'Commands', execOn: 'Allowed',
    execOff: 'Disabled', roots: 'Allowed folders', timeout: 'Timeout', timeoutValue: '{d} default, {m} max', output: 'Output limit',
    tools: 'Tools', reserved: 'Reserved', platform: 'System', runtime: 'Runtime', none: 'None', notReported: 'Visible once connected',
    name: 'Name', namePlaceholder: 'e.g. Living room TV', url: 'Address', urlPlaceholder: '192.168.1.30:7878', tokenMode: 'Token',
    fileMode: 'Token file', tokenPlaceholder: 'Paste the taku-runtime token', filePlaceholder: '/data/local/agentos/acp/secrets/xxx.token',
    test: 'Test', save: 'Save', cancel: 'Cancel', saved: 'Added. The Agent can now use this device.', empty: 'No devices yet',
    loadFailed: 'Could not read devices', nameRequired: 'Enter a name', nameTooLong: 'Up to 64 characters', urlRequired: 'Enter an address',
    urlInvalid: 'Not a valid address', tokenRequired: 'Enter a token', tokenFileInvalid: 'Token files must be under /data/local/agentos/',
    mode: 'Mode', autonomous: 'Autonomous (own agent)', endpoint: 'Endpoint (hands only)',
  },
} as const;
type Copy = Record<keyof typeof copy.zh, string>;

const toneColor = (tone: StateTone) =>
  tone === 'success' ? colors.successDot : tone === 'attention' ? colors.attention : tone === 'error' ? colors.error : colors.switchOff;

const duration = (ms: number | undefined) => !ms ? '–' : ms % 60000 === 0 ? `${ms / 60000}m` : `${Math.round(ms / 1000)}s`;
const bytes = (n: number | undefined) => !n ? '–' : n >= 1024 * 1024 ? `${Math.round(n / 1024 / 1024)} MiB` : `${Math.round(n / 1024)} KiB`;
const codeOf = (error: unknown) => (error as AgentGatewayError)?.code || 'GATEWAY_UNAVAILABLE';

export function ConnectScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const language: Language = languageFor(useLocales()[0]?.languageTag);
  const zh = language === 'zh';
  const t = copy[language];
  const type = useCallback<TypeFor>((role, content) => typography(role, content === undefined ? zh : containsChinese(content)), [zh]);
  const [fonts, fontError] = useTakuFonts();

  const [nodes, setNodes] = useState<ConnectNode[] | null>(null);
  const [probes, setProbes] = useState<Record<string, ConnectProbe>>({});
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [sheet, setSheet] = useState<Sheet>('node');
  const [toggling, setToggling] = useState(false);
  const [canvas, setCanvas] = useState({width: 0, height: 0});
  const [sheetHeight, setSheetHeight] = useState(0);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const nodesRef = useRef<ConnectNode[] | null>(null);
  useEffect(() => { nodesRef.current = nodes; }, [nodes]);

  // One probe round at a time: the 10 s timer, focus and refresh must not stack rounds on the broker.
  // A request during a round (e.g. after adding a device) runs once more right after it, with the newest list.
  const probing = useRef(false);
  const queued = useRef<ConnectNode[] | null>(null);
  const probeAll = useCallback(async (list: ConnectNode[]) => {
    if (probing.current) { queued.current = list; return; }
    probing.current = true;
    try {
      for (let round: ConnectNode[] | null = list; round && mounted.current; round = queued.current, queued.current = null) {
        // Demo nodes are answered locally (probeFor): no gateway call, no network.
        await forEachLimited(round.filter(n => !n.demo), PROBE_CONCURRENCY, async node => {
          for (let attempt = 0; ; attempt++) {
            try {
              const probe = await callAgent<ConnectProbe>('connect/probe', {id: node.id});
              if (mounted.current) setProbes(current => ({...current, [node.id]: probe}));
              return;
            } catch (error) {
              // Gateway load is not the device's state: retry briefly, else keep the last result.
              if (isTransientProbeError((error as AgentGatewayError).code)) {
                if (attempt < 3) { await new Promise(resolve => setTimeout(resolve, 250 * (attempt + 1))); continue; }
                return;
              }
              if (mounted.current) setProbes(current => ({...current, [node.id]: {status: 'offline', online: false, error: 'PROBE_FAILED'}}));
              return;
            }
          }
        });
      }
    } finally {
      probing.current = false;
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const result = await callAgent<{nodes: ConnectNode[]}>('connect/list');
      if (!mounted.current) return [];
      setNodes(result.nodes);
      setLoadError('');
      setSelected(current => current && (current === 'phone' || result.nodes.some(n => n.id === current)) ? current : result.nodes[0]?.id ?? 'phone');
      void probeAll(result.nodes);
      return result.nodes;
    } catch (error) {
      if (mounted.current) { setLoadError(connectError(codeOf(error), language)); setNodes(current => current ?? []); }
      return [];
    }
  }, [language, probeAll]);

  // Reads the broker's node list (external system); state is set only after the async read resolves.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);
  // Live status while the page is in front.
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => { if (!timer) timer = setInterval(() => { if (nodesRef.current) void probeAll(nodesRef.current); }, PROBE_MS); };
    const stop = () => { if (timer) clearInterval(timer); timer = null; };
    start();
    const subscription = AppState.addEventListener('change', state => state === 'active' ? start() : stop());
    return () => { stop(); subscription.remove(); };
  }, [probeAll]);
  const close = useCallback(() => { if (router.canGoBack()) router.back(); else router.replace('/'); }, [router]);
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (sheet !== 'node') { setSheet('node'); return true; }
      return false;
    });
    return () => subscription.remove();
  }, [sheet]);

  const node = nodes?.find(n => n.id === selected) ?? null;
  const probe = node ? probeFor(node, probes) : undefined;

  const toggle = useCallback(async (target: ConnectNode) => {
    setToggling(true);
    try {
      const result = await callAgent<{node: ConnectNode}>('connect/setEnabled', {id: target.id, enabled: !target.enabled});
      if (!mounted.current) return;
      setNodes(current => current?.map(n => n.id === target.id ? result.node : n) ?? current);
    } catch (error) {
      if (mounted.current) setLoadError(connectError(codeOf(error), language));
    } finally {
      if (mounted.current) setToggling(false);
    }
  }, [language]);

  const dispatch = useCallback((target: ConnectNode) => {
    queueConnectDispatch(dispatchDraft(target, probeFor(target, probes), language));
    router.replace('/');
  }, [language, probes, router]);

  // The orbit (bubbles and their labels) ends above the sheet; nothing may hide behind its top edge.
  const visibleHeight = Math.max(0, canvas.height - sheetHeight - 4);
  const layout = useMemo(() => {
    const top = insets.top + 56;
    const area = Math.max(0, visibleHeight - top);
    const orbit = orbitLayout((nodes?.length ?? 0) + 1, canvas.width, area);
    return {...orbit, center: {x: orbit.center.x, y: orbit.center.y + top}, slots: orbit.slots.map(s => ({...s, y: s.y + top}))};
  }, [canvas.width, insets.top, nodes?.length, visibleHeight]);

  if (!fonts && !fontError) return <View style={[styles.root, styles.center]}><ActivityIndicator color={colors.ink}/></View>;

  return <View style={styles.root} onLayout={e => setCanvas({width: e.nativeEvent.layout.width, height: e.nativeEvent.layout.height})}>
    {canvas.width > 0 && <Orbit layout={layout} nodes={nodes ?? []} probes={probes} selected={selected}/>}
    {canvas.width > 0 && <PhoneOrb x={layout.center.x} y={layout.center.y} label={t.phone} selected={selected === 'phone'} type={type}
      live={(nodes ?? []).some(n => isLive(nodeState(n, probeFor(n, probes))))} onPress={() => { setSelected('phone'); setSheet('node'); }}/>}
    {canvas.width > 0 && (nodes ?? []).map((n, i) => {
      const slot = layout.slots[i];
      if (!slot) return null;
      const p = probeFor(n, probes);
      return <NodeBubble key={n.id} x={slot.x} y={slot.y} node={n} probe={p} selected={selected === n.id} type={type} labelHalf={layout.labelHalf}
        state={nodeState(n, p)} name={orbitName(n, p)} onPress={() => { setSelected(n.id); setSheet('node'); }}/>;
    })}
    {canvas.width > 0 && nodes && layout.slots[nodes.length] && <AddBubble x={layout.slots[nodes.length].x} y={layout.slots[nodes.length].y}
      label={t.add} type={type} labelHalf={layout.labelHalf} active={sheet === 'add'} onPress={() => setSheet('add')}/>}

    <View style={[styles.header, {paddingTop: insets.top}]} pointerEvents="box-none">
      <IconButton name="arrow_left" label={t.back} onPress={close}/>
      <Text accessibilityRole="header" style={[type('context', t.title), styles.title]}>{t.title}</Text>
      <IconButton name="refresh" label={t.refresh} onPress={() => { void load(); }}/>
    </View>

    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.sheetWrap} pointerEvents="box-none">
      <View style={[styles.sheet, {paddingBottom: insets.bottom + 16}]} onLayout={e => setSheetHeight(e.nativeEvent.layout.height)}>
        <View style={styles.grabber}/>
        {nodes === null ? <ActivityIndicator color={colors.ink} style={styles.sheetLoading}/>
          : sheet === 'add' ? <AddSheet t={t} type={type} language={language} onCancel={() => setSheet('node')}
            onSaved={async id => { await load(); if (mounted.current) { setSelected(id); setSheet('node'); } }}/>
          : sheet === 'permissions' && node ? <PermissionsSheet t={t} type={type} language={language} node={node} probe={probe} onBack={() => setSheet('node')}/>
          : node ? <NodeSheet key={node.id} t={t} type={type} language={language} node={node} probe={probe} toggling={toggling} error={loadError}
            onToggle={() => { void toggle(node); }} onDispatch={() => dispatch(node)} onPermissions={() => setSheet('permissions')}/>
          : <PhoneSheet t={t} type={type} count={nodes.length} error={loadError}/>}
      </View>
    </KeyboardAvoidingView>
  </View>;
}

// ---- canvas ----

function Orbit({layout, nodes, probes, selected}: {
  layout: ReturnType<typeof orbitLayout>; nodes: ConnectNode[]; probes: Record<string, ConnectProbe>; selected: string | null;
}) {
  const {center, radii, slots, aspect} = layout;
  // Star-track dots: fixed pseudo-random positions on and between the rings.
  const stars = useMemo(() => Array.from({length: 28}, (_, i) => {
    const angle = (i * 137.5 * Math.PI) / 180;
    const r = radii[0] * 0.55 + ((i * 53) % 100) / 100 * (radii[1] * 1.25 - radii[0] * 0.55);
    return {x: center.x + r * aspect * Math.cos(angle), y: center.y + r * Math.sin(angle), r: i % 3 === 0 ? 1.6 : 1};
  }), [aspect, center.x, center.y, radii]);
  return <Svg style={StyleSheet.absoluteFill} pointerEvents="none">
    <Ellipse cx={center.x} cy={center.y} rx={radii[0] * aspect} ry={radii[0]} stroke={colors.border} strokeWidth={1} fill="none"/>
    <Ellipse cx={center.x} cy={center.y} rx={radii[1] * aspect} ry={radii[1]} stroke={colors.border} strokeWidth={1} strokeDasharray="2 6" fill="none"/>
    <Ellipse cx={center.x} cy={center.y} rx={radii[1] * 1.32 * aspect} ry={radii[1] * 1.32} stroke={colors.subtle} strokeWidth={1} fill="none"/>
    {stars.map((s, i) => <Circle key={i} cx={s.x} cy={s.y} r={s.r} fill={colors.switchOff}/>)}
    {nodes.map((n, i) => {
      const slot = slots[i];
      const state = nodeState(n, probeFor(n, probes));
      if (!slot || !isLive(state)) return null;
      return <Line key={n.id} x1={center.x} y1={center.y} x2={slot.x} y2={slot.y} stroke={colors.brand}
        strokeWidth={selected === n.id ? 1.8 : 1.2} strokeDasharray={selected === n.id ? undefined : '3 5'} strokeLinecap="round" opacity={selected === n.id ? 0.9 : 0.55}/>;
    })}
  </Svg>;
}

/** Ember ring that expands and fades: the "connected" pulse. */
function Pulse({size, active}: {size: number; active: boolean}) {
  const [value] = useState(() => new Animated.Value(0));
  useEffect(() => {
    if (!active) { value.stopAnimation(); value.setValue(0); return; }
    const loop = Animated.loop(Animated.timing(value, {toValue: 1, duration: 2200, easing: Easing.out(Easing.quad), useNativeDriver: true}));
    loop.start();
    return () => loop.stop();
  }, [active, value]);
  if (!active) return null;
  return <Animated.View pointerEvents="none" style={[styles.pulse, {width: size, height: size, borderRadius: size / 2, marginLeft: -size / 2, marginTop: -size / 2,
    opacity: value.interpolate({inputRange: [0, 1], outputRange: [0.55, 0]}),
    transform: [{scale: value.interpolate({inputRange: [0, 1], outputRange: [1, 1.7]})}]}]}/>;
}

function PhoneOrb({x, y, label, selected, live, type, onPress}: {x: number; y: number; label: string; selected: boolean; live: boolean; type: TypeFor; onPress: () => void}) {
  return <View style={[styles.anchor, {left: x, top: y}]} pointerEvents="box-none">
    <Pulse size={ORB} active={live}/>
    <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{selected}} onPress={onPress}
      style={({pressed}) => [styles.orb, selected && styles.orbSelected, pressed && styles.pressed]}>
      <DeviceIcon source={{tier: 'builtin', key: 'phone'}} fallback="phone" size={34} color={colors.white}/>
    </Pressable>
    <View style={styles.orbLabel} pointerEvents="none"><Text numberOfLines={1} style={[type('label', label), styles.labelPill]}>{label}</Text></View>
  </View>;
}

function NodeBubble({x, y, node, probe, state, name, selected, type, labelHalf, onPress}: {
  x: number; y: number; node: ConnectNode; probe?: ConnectProbe; state: NodeState; name: string; selected: boolean; type: TypeFor; labelHalf: number; onPress: () => void;
}) {
  return <View style={[styles.anchor, {left: x, top: y}]} pointerEvents="box-none">
    <Pulse size={BUBBLE} active={selected && isLive(state)}/>
    <Pressable testID={`connect-node-${node.id}`} accessibilityRole="button" accessibilityLabel={name} accessibilityState={{selected}} onPress={onPress}
      style={({pressed}) => [styles.bubble, selected && styles.bubbleSelected, state === 'disabled' && styles.dim, pressed && styles.pressed]}>
      <DeviceIcon source={resolveIcon(node, probe)} fallback={effectiveDeviceType(node, probe)} size={30}/>
      <View style={[styles.statusDot, {backgroundColor: toneColor(stateTone(state))}]}/>
    </Pressable>
    <View style={[styles.bubbleLabel, {width: labelHalf * 2, left: -labelHalf}]} pointerEvents="none">
      <Text numberOfLines={1} style={[type('meta', name), styles.labelPill, {maxWidth: labelHalf * 2}]}>{name}</Text>
    </View>
  </View>;
}

function AddBubble({x, y, label, type, labelHalf, active, onPress}: {
  x: number; y: number; label: string; type: TypeFor; labelHalf: number; active: boolean; onPress: () => void;
}) {
  return <View style={[styles.anchor, {left: x, top: y}]} pointerEvents="box-none">
    <Pressable testID="connect-add" accessibilityRole="button" accessibilityLabel={label} onPress={onPress}
      style={({pressed}) => [styles.bubble, styles.addBubble, active && styles.addActive, pressed && styles.pressed]}>
      <Text style={styles.plus}>+</Text>
    </Pressable>
    <View style={[styles.bubbleLabel, {width: labelHalf * 2, left: -labelHalf}]} pointerEvents="none">
      <Text numberOfLines={1} style={[type('meta', label), styles.labelPill, styles.secondary, {maxWidth: labelHalf * 2}]}>{label}</Text>
    </View>
  </View>;
}

// ---- sheets ----

function TakuSwitch({value, disabled, label, onToggle}: {value: boolean; disabled?: boolean; label: string; onToggle: () => void}) {
  return <Pressable accessibilityRole="switch" accessibilityLabel={label} accessibilityState={{checked: value, disabled: Boolean(disabled)}}
    disabled={disabled} onPress={onToggle} hitSlop={8}>
    <View pointerEvents="none" importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
      <Switch value={value} disabled={disabled} trackColor={{false: colors.switchOff, true: colors.brand}}
        thumbColor={value ? colors.ink : colors.white} ios_backgroundColor={colors.switchOff}/>
    </View>
  </Pressable>;
}

function Button({label, tone = 'gray', onPress, disabled, busy, type, testID}: {
  label: string; tone?: 'ink' | 'gray'; onPress: () => void; disabled?: boolean; busy?: boolean; type: TypeFor; testID?: string;
}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityState={{disabled: Boolean(disabled || busy), busy: Boolean(busy)}}
    disabled={disabled || busy} onPress={onPress} style={({pressed}) => [styles.button, tone === 'ink' && styles.buttonInk, (disabled || busy) && styles.dim, pressed && styles.pressed]}>
    {busy ? <ActivityIndicator color={tone === 'ink' ? colors.white : colors.ink}/>
      : <Text style={[type('label', label), tone === 'ink' && styles.inverse]}>{label}</Text>}
  </Pressable>;
}

function Chips({labels, type}: {labels: string[]; type: TypeFor}) {
  if (!labels.length) return null;
  return <View style={styles.chips}>{labels.map(label => <View key={label} style={styles.chip}>
    <Text style={[type('meta', label), styles.chipText]}>{label}</Text>
  </View>)}</View>;
}

function StatusRow({text, tone, type}: {text: string; tone: StateTone; type: TypeFor}) {
  return <View style={styles.statusRow}>
    <View style={[styles.dot, {backgroundColor: toneColor(tone)}]}/>
    <Text style={[type('meta', text), styles.secondary]}>{text}</Text>
  </View>;
}

function NodeSheet({t, type, language, node, probe, toggling, error, onToggle, onDispatch, onPermissions}: {
  t: Copy; type: TypeFor; language: Language; node: ConnectNode; probe?: ConnectProbe; toggling: boolean; error: string;
  onToggle: () => void; onDispatch: () => void; onPermissions: () => void;
}) {
  const state = nodeState(node, probe);
  const name = displayName(node, probe);
  const caps = probe?.capabilities;
  const chips = capabilityChips(probe).map(chip => connectLabel(language, chip));
  // The address the agent is using right now: the selected route, else the first one.
  const detail = [hostPort(state === 'online' && probe?.route ? probe.route.url : node.url),
    caps?.os && caps.arch ? `${caps.os}/${caps.arch}` : '', caps?.runtime && caps.version ? `${caps.runtime} ${caps.version}` : ''].filter(Boolean).join(' · ');
  const problem = state === 'unauthorized' || state === 'offline' ? connectError(state === 'unauthorized' ? 'NODE_UNAUTHORIZED' : 'NODE_UNREACHABLE', language) : '';
  const kind = cardKind(node, probe);
  const [note, setNote] = useState('');
  const head = <View style={styles.nodeHead}>
      <View style={styles.tile}><DeviceIcon source={resolveIcon(node, probe)} fallback={effectiveDeviceType(node, probe)} size={28}/></View>
      <View style={styles.flex}>
        <Text numberOfLines={1} style={type('context', name)}>{name}</Text>
        <StatusRow text={statusLine(node, probe, language)} tone={stateTone(state)} type={type}/>
      </View>
      <TakuSwitch value={node.enabled} disabled={toggling} label={t.enabled} onToggle={onToggle}/>
    </View>;
  if (kind !== 'standard') {
    // Device card (demo node): its own panel; "Dispatch task" explains instead of handing the agent a device it has no tools for.
    const off = state !== 'online';
    return <View style={styles.sheetBody}>
      {head}
      {kind === 'car' ? <CarPanel language={language} type={type} disabled={off}/> : <TvPanel language={language} type={type} disabled={off}/>}
      {!!(note || error) && <Text style={[type('meta'), error ? styles.error : styles.secondary]}>{error || note}</Text>}
      <Button testID="connect-dispatch" label={t.dispatch} tone="ink" type={type} disabled={off} onPress={() => setNote(demoDispatchNote(language))}/>
    </View>;
  }
  return <View style={styles.sheetBody}>
    {head}
    <Chips labels={chips} type={type}/>
    {!!detail && <Text style={[type('caption'), styles.secondary]}>{detail}</Text>}
    {!!(problem || error) && <Text style={[type('meta'), styles.error]}>{error || problem}</Text>}
    <View style={styles.actions}>
      <View style={styles.flex}><Button testID="connect-dispatch" label={t.dispatch} tone="ink" type={type} disabled={state !== 'online'} onPress={onDispatch}/></View>
      <View style={styles.flex}><Button testID="connect-permissions" label={t.permissions} type={type} onPress={onPermissions}/></View>
    </View>
  </View>;
}

function PhoneSheet({t, type, count, error}: {t: Copy; type: TypeFor; count: number; error: string}) {
  return <View style={styles.sheetBody}>
    <View style={styles.nodeHead}>
      <View style={[styles.tile, styles.tileInk]}><DeviceIcon source={{tier: 'builtin', key: 'phone'}} fallback="phone" size={28} color={colors.white}/></View>
      <View style={styles.flex}>
        <Text style={type('context', t.phone)}>{t.phone}</Text>
        <Text style={[type('meta'), styles.secondary]}>{t.thisPhone}</Text>
      </View>
    </View>
    <Text style={[type('body'), styles.secondary]}>{count ? t.phoneHint.replace('{n}', String(count)) : t.empty}</Text>
    {!!error && <Text style={[type('meta'), styles.error]}>{error}</Text>}
  </View>;
}

function InfoRow({label, value, type}: {label: string; value: string; type: TypeFor}) {
  return <View style={styles.infoRow}>
    <Text style={[type('meta', label), styles.secondary, styles.infoLabel]}>{label}</Text>
    <Text style={[type('meta', value), styles.infoValue]} selectable>{value}</Text>
  </View>;
}

const routeDot = (tone: RouteTone) =>
  tone === 'selected' ? colors.successDot : tone === 'attention' ? colors.attention : tone === 'standby' ? colors.secondary : colors.switchOff;

/**
 * Routes, read-only: the route in use first (green dot, latency), backups quietly below, offline
 * ones grey without error text, one footnote. Editing is the Agent's (`connect/routes`).
 */
function RoutesRow({rows, language, type}: {rows: RouteRow[]; language: Language; type: TypeFor}) {
  const label = connectLabel(language, 'routes');
  return <View style={styles.infoRow}>
    <Text style={[type('meta', label), styles.secondary, styles.infoLabel]}>{label}</Text>
    <View style={[styles.infoValue, styles.routes]}>
      {rows.map(row => {
        const quiet = !row.selected;
        return <View key={row.url} style={styles.route} accessible accessibilityLabel={[row.label, hostPort(row.url), row.latency].filter(Boolean).join(', ')}>
          <View style={[styles.routeDot, {backgroundColor: routeDot(row.tone)}]}/>
          <View style={styles.flex}>
            <Text numberOfLines={1} style={[type('meta', row.label), quiet && styles.secondary, row.tone === 'offline' && styles.muted]}>{row.label}</Text>
            <Text numberOfLines={1} selectable style={[type('caption'), styles.muted]}>{hostPort(row.url)}</Text>
          </View>
          {!!row.latency && <Text style={[type('meta'), quiet ? styles.muted : styles.secondary]}>{row.latency}</Text>}
        </View>;
      })}
      {rows.length > 1 && <Text style={[type('caption', connectLabel(language, 'routesAuto')), styles.muted]}>{connectLabel(language, 'routesAuto')}</Text>}
    </View>
  </View>;
}

function PermissionsSheet({t, type, language, node, probe, onBack}: {
  t: Copy; type: TypeFor; language: Language; node: ConnectNode; probe?: ConnectProbe; onBack: () => void;
}) {
  const caps = probe?.capabilities;
  const exec = caps?.exec;
  const pending = t.notReported;
  return <View style={styles.sheetBody}>
    <View style={styles.sheetTitleRow}>
      <IconButton name="arrow_left" label={t.back} onPress={onBack} compact/>
      <Text style={[type('context', t.permissionsTitle), styles.flex]}>{t.permissionsTitle}</Text>
    </View>
    <ScrollView style={styles.permissionList} contentContainerStyle={styles.permissionContent}>
      <RoutesRow type={type} language={language} rows={routeRows(node, probe, language)}/>
      <InfoRow type={type} label={t.token} value={node.hasToken ? t.tokenStored : t.tokenMissing}/>
      <InfoRow type={type} label={t.mode} value={caps?.mode ? (caps.mode === 'autonomous' ? t.autonomous : t.endpoint) : pending}/>
      <InfoRow type={type} label={t.exec} value={exec ? (exec.enabled ? t.execOn : t.execOff) : pending}/>
      <InfoRow type={type} label={t.roots} value={exec ? (exec.allowedRoots.join('\n') || t.none) : pending}/>
      <InfoRow type={type} label={t.timeout} value={exec ? t.timeoutValue.replace('{d}', duration(exec.defaultTimeoutMs)).replace('{m}', duration(exec.maxTimeoutMs)) : pending}/>
      <InfoRow type={type} label={t.output} value={exec ? bytes(exec.maxOutputBytes) : pending}/>
      <InfoRow type={type} label={t.tools} value={caps ? ((caps.tools ?? []).map(tool => tool.name).join(', ') || t.none) : pending}/>
      <InfoRow type={type} label={t.reserved} value={caps ? ((caps.reservedTools ?? []).join(', ') || t.none) : pending}/>
      <InfoRow type={type} label={t.platform} value={caps?.os ? `${caps.os}/${caps.arch} · ${caps.device ?? ''}` : pending}/>
      <InfoRow type={type} label={t.runtime} value={caps?.runtime ? `${caps.runtime} ${caps.version ?? ''}` : pending}/>
    </ScrollView>
  </View>;
}

function AddSheet({t, type, language, onCancel, onSaved}: {
  t: Copy; type: TypeFor; language: Language; onCancel: () => void; onSaved: (id: string) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<'token' | 'file'>('token');
  const [tokenFile, setTokenFile] = useState('');
  const [busy, setBusy] = useState<'' | 'test' | 'save'>('');
  const [result, setResult] = useState<{probe?: ConnectProbe; message?: string; ok?: boolean} | null>(null);
  const [touched, setTouched] = useState(false);
  const secret = useRef<SecretFieldHandle>(null);
  // The token stays in the native field; for validation the form only needs to know it is not
  // empty, which the gateway reports (SECRET_EMPTY) if it is.
  const validation = validateAddForm({name, url, tokenMode: mode, tokenFile, tokenTyped: true});
  const shown = touched ? validation.errors : {};

  const send = async (action: 'connect/test' | 'connect/add') => {
    setTouched(true);
    const errors = action === 'connect/test' ? {url: validation.errors.url, token: validation.errors.token} : validation.errors;
    if (Object.values(errors).some(Boolean)) return;
    setBusy(action === 'connect/test' ? 'test' : 'save');
    setResult(null);
    try {
      const params: Record<string, unknown> = action === 'connect/add' ? {name: name.trim(), url: validation.url} : {url: validation.url};
      let answer: unknown;
      if (mode === 'file') answer = await callAgent(action, {...params, tokenFile: tokenFile.trim()});
      else if (secret.current) answer = await secret.current.submit(action, params);
      else throw Object.assign(new Error('SECRET_EMPTY'), {code: 'SECRET_EMPTY'});
      if (action === 'connect/test') {
        const probe = answer as ConnectProbe;
        setResult({probe, ok: probe.status === 'online', message: probe.status === 'online' ? statusLine({enabled: true}, probe, language)
          : connectError(probe.status === 'unauthorized' ? 'NODE_UNAUTHORIZED' : 'NODE_UNREACHABLE', language)});
      } else {
        const id = (answer as {node: ConnectNode}).node.id;
        setResult({ok: true, message: t.saved});
        await onSaved(id);
      }
    } catch (error) {
      setResult({ok: false, message: connectError(codeOf(error), language)});
    } finally {
      setBusy('');
    }
  };

  return <View style={styles.sheetBody}>
    <View style={styles.sheetTitleRow}>
      <IconButton name="arrow_left" label={t.cancel} onPress={onCancel} compact/>
      <Text style={[type('context', t.add), styles.flex]}>{t.add}</Text>
    </View>
    <Field label={t.name} error={shown.name && t[shown.name]} type={type}>
      <TextInput value={name} onChangeText={setName} placeholder={t.namePlaceholder} placeholderTextColor={colors.muted} maxLength={64}
        style={[type('body', name), styles.input]} accessibilityLabel={t.name}/>
    </Field>
    <Field label={t.url} error={shown.url && t[shown.url]} type={type}>
      <TextInput value={url} onChangeText={setUrl} placeholder={t.urlPlaceholder} placeholderTextColor={colors.muted} autoCapitalize="none"
        autoCorrect={false} keyboardType="url" style={[type('body', url), styles.input]} accessibilityLabel={t.url}/>
    </Field>
    <View style={styles.segmented} accessibilityRole="tablist">
      {(['token', 'file'] as const).map(id => <Pressable key={id} accessibilityRole="tab" accessibilityState={{selected: mode === id}} onPress={() => setMode(id)}
        style={[styles.segment, mode === id && styles.segmentOn]}>
        <Text style={[type('meta'), mode === id ? styles.inverse : styles.secondary]}>{id === 'token' ? t.tokenMode : t.fileMode}</Text>
      </Pressable>)}
    </View>
    <Field error={shown.token && t[shown.token]} type={type}>
      {mode === 'token'
        ? <SecretField ref={secret} placeholder={t.tokenPlaceholder} textColor={colors.ink} placeholderColor={colors.muted} style={styles.secret}/>
        : <TextInput value={tokenFile} onChangeText={setTokenFile} placeholder={t.filePlaceholder} placeholderTextColor={colors.muted}
          autoCapitalize="none" autoCorrect={false} style={[type('body', tokenFile), styles.input]} accessibilityLabel={t.fileMode}/>}
    </Field>
    {result?.message && <View style={styles.resultRow}>
      <View style={[styles.dot, {backgroundColor: result.ok ? colors.successDot : colors.error}]}/>
      <Text style={[type('meta', result.message), styles.flex, result.ok ? styles.secondary : styles.error]}>{result.message}</Text>
    </View>}
    {result?.probe && <Chips labels={capabilityChips(result.probe).map(chip => connectLabel(language, chip))} type={type}/>}
    <View style={styles.actions}>
      <View style={styles.flex}><Button label={t.test} type={type} busy={busy === 'test'} disabled={busy === 'save'} onPress={() => { void send('connect/test'); }}/></View>
      <View style={styles.flex}><Button label={t.save} tone="ink" type={type} busy={busy === 'save'} disabled={busy === 'test'} onPress={() => { void send('connect/add'); }}/></View>
    </View>
  </View>;
}

function Field({label, error, type, children}: {label?: string; error?: string; type: TypeFor; children: React.ReactNode}) {
  return <View style={styles.field}>
    {!!label && <Text style={[type('meta', label), styles.secondary]}>{label}</Text>}
    {children}
    {!!error && <Text style={[type('caption', error), styles.error]}>{error}</Text>}
  </View>;
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.page, overflow: 'hidden'},
  center: {alignItems: 'center', justifyContent: 'center'},
  flex: {flex: 1, minWidth: 0},
  header: {position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 4, minHeight: 56},
  title: {flex: 1, textAlign: 'center'},
  anchor: {position: 'absolute', width: 0, height: 0, alignItems: 'center', justifyContent: 'center', overflow: 'visible'},
  pulse: {position: 'absolute', left: 0, top: 0, borderWidth: 2, borderColor: colors.brand},
  orb: {position: 'absolute', width: ORB, height: ORB, borderRadius: ORB / 2, marginLeft: -ORB / 2, marginTop: -ORB / 2, left: 0, top: 0,
    backgroundColor: colors.ink, alignItems: 'center', justifyContent: 'center', elevation: 6, shadowColor: colors.ink, shadowOpacity: 0.2, shadowRadius: 12, shadowOffset: {width: 0, height: 6}},
  orbSelected: {borderWidth: 3, borderColor: colors.brand},
  orbLabel: {position: 'absolute', top: ORB / 2 + 8, width: 150, left: -75, alignItems: 'center'},
  bubble: {position: 'absolute', width: BUBBLE, height: BUBBLE, borderRadius: BUBBLE / 2, marginLeft: -BUBBLE / 2, marginTop: -BUBBLE / 2, left: 0, top: 0,
    backgroundColor: colors.white, alignItems: 'center', justifyContent: 'center', elevation: 3, shadowColor: colors.ink, shadowOpacity: 0.08, shadowRadius: 8, shadowOffset: {width: 0, height: 3}},
  bubbleSelected: {borderWidth: 2, borderColor: colors.brand},
  bubbleLabel: {position: 'absolute', top: BUBBLE / 2 + 6, width: 150, left: -75, alignItems: 'center'},
  // A page-coloured pill keeps names legible where a connection line passes behind them.
  labelPill: {backgroundColor: colors.page, borderRadius: 999, paddingHorizontal: 8, maxWidth: 150, overflow: 'hidden'},
  statusDot: {position: 'absolute', right: 3, top: 3, width: 12, height: 12, borderRadius: 6, borderWidth: 2, borderColor: colors.white},
  addBubble: {backgroundColor: colors.page, borderWidth: 1.5, borderStyle: 'dashed', borderColor: colors.secondary, elevation: 0, shadowOpacity: 0},
  addActive: {borderColor: colors.brand},
  plus: {fontSize: 28, lineHeight: 32, color: colors.secondary, includeFontPadding: false},
  sheetWrap: {position: 'absolute', left: 0, right: 0, bottom: 0},
  sheet: {backgroundColor: colors.white, borderTopLeftRadius: 24, borderTopRightRadius: 24, paddingHorizontal: 20, paddingTop: 8,
    elevation: 16, shadowColor: colors.ink, shadowOpacity: 0.12, shadowRadius: 24, shadowOffset: {width: 0, height: -6}},
  grabber: {alignSelf: 'center', width: 36, height: 4, borderRadius: 2, backgroundColor: colors.switchOff, marginBottom: 12},
  sheetLoading: {marginVertical: 32},
  sheetBody: {gap: 14},
  sheetTitleRow: {flexDirection: 'row', alignItems: 'center', gap: 4, marginLeft: -8},
  nodeHead: {flexDirection: 'row', alignItems: 'center', gap: 12},
  tile: {width: 52, height: 52, borderRadius: 26, backgroundColor: colors.page, alignItems: 'center', justifyContent: 'center'},
  tileInk: {backgroundColor: colors.ink},
  statusRow: {flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 2},
  dot: {width: 8, height: 8, borderRadius: 4},
  chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  chip: {paddingHorizontal: 12, paddingVertical: 6, borderRadius: 999, backgroundColor: colors.subtle, borderWidth: 1, borderColor: colors.border},
  chipText: {color: colors.ink},
  actions: {flexDirection: 'row', gap: 12, marginTop: 2},
  button: {minHeight: 48, borderRadius: 999, backgroundColor: colors.control, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16},
  buttonInk: {backgroundColor: colors.ink},
  inverse: {color: colors.white},
  secondary: {color: colors.secondary},
  muted: {color: colors.muted},
  error: {color: colors.error},
  dim: {opacity: 0.45},
  pressed: {opacity: 0.7},
  infoRow: {flexDirection: 'row', gap: 12, paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border},
  infoLabel: {width: 96},
  infoValue: {flex: 1},
  routes: {gap: 8},
  route: {flexDirection: 'row', alignItems: 'center', gap: 8},
  routeDot: {width: 6, height: 6, borderRadius: 3},
  permissionList: {maxHeight: 360},
  permissionContent: {paddingBottom: 4},
  field: {gap: 6},
  input: {minHeight: 48, borderRadius: 12, backgroundColor: colors.page, paddingHorizontal: 14},
  secret: {height: 48, borderRadius: 12, backgroundColor: colors.page, paddingHorizontal: 14},
  segmented: {flexDirection: 'row', backgroundColor: colors.page, borderRadius: 999, padding: 4, alignSelf: 'flex-start'},
  segment: {paddingHorizontal: 16, paddingVertical: 6, borderRadius: 999},
  segmentOn: {backgroundColor: colors.ink},
  resultRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
});
