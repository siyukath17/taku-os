/**
 * Device cards for Taku Connect demo nodes: the car card's 360° 3D stage and controls, and the TV
 * card's screen and controls. The sheet header, switch and "Dispatch task" button stay in
 * ConnectScreen's NodeSheet, which picks the panel with `cardKind`.
 */
import React, {useCallback, useEffect, useMemo, useReducer, useRef, useState} from 'react';
import {PanResponder, Pressable, StyleSheet, Text, View, type AccessibilityActionEvent} from 'react-native';
import Svg, {Circle, Defs, G, LinearGradient, Path, RadialGradient, Rect, Stop} from 'react-native-svg';
import type {Language} from '../../i18n';
import {buildCarMesh, frame, INITIAL_YAW, renderCar, shadowPath, spinStep, wrapAngle, yawForDrag, type Face} from '../../model/carModel';
import {carChips, carReducer, initialCar, initialTv, tvChips, tvReducer, tvScreen, TV_VOLUME, type ControlChip} from '../../model/deviceCards';
import {colors, type TypeRole} from '../../theme';

type TypeFor = (role: TypeRole, content?: string) => object;
export const STAGE_HEIGHT = 160;

let mesh: Face[] | null = null;
const carMesh = () => (mesh ??= buildCarMesh());

function ChipRow<Id extends string>({chips, disabled, type, onPress}: {
  chips: ControlChip<Id>[]; disabled: boolean; type: TypeFor; onPress: (id: Id) => void;
}) {
  return <View style={styles.chipRow}>
    {chips.map(chip => <Pressable key={chip.id} testID={`connect-control-${chip.id}`} accessibilityRole="button" accessibilityLabel={chip.label}
      accessibilityState={{selected: chip.active, disabled}} disabled={disabled} onPress={() => onPress(chip.id)}
      style={({pressed}) => [styles.control, chip.active && styles.controlOn, disabled && styles.dim, pressed && styles.pressed]}>
      {chip.active && <View style={styles.controlDot}/>}
      <Text numberOfLines={1} style={[type('meta', chip.label), chip.active ? styles.inverse : styles.ink]}>{chip.label}</Text>
    </Pressable>)}
  </View>;
}

// ---- car ----

/**
 * The 360° stage: drag sideways to turn the car (a full stage-width swipe is half a turn); a fling
 * keeps it spinning and eases out. Screen readers get increment/decrement actions (30° steps).
 */
export function CarStage({type, disabled}: {type: TypeFor; disabled?: boolean}) {
  const [width, setWidth] = useState(0);
  const [yaw, setYaw] = useState(INITIAL_YAW);
  const yawRef = useRef(INITIAL_YAW), startYaw = useRef(INITIAL_YAW), widthRef = useRef(0);
  const frameRef = useRef<number | null>(null), spinRef = useRef<number | null>(null);
  // One state update per display frame, whatever the touch event rate.
  const show = useCallback((value: number) => {
    yawRef.current = value;
    if (frameRef.current === null) frameRef.current = requestAnimationFrame(() => { frameRef.current = null; setYaw(yawRef.current); });
  }, []);
  const stopSpin = useCallback(() => { if (spinRef.current !== null) cancelAnimationFrame(spinRef.current); spinRef.current = null; }, []);
  useEffect(() => () => { stopSpin(); if (frameRef.current !== null) cancelAnimationFrame(frameRef.current); }, [stopSpin]);
  const spin = useCallback((velocity: number) => {
    stopSpin();
    let v = velocity, last = Date.now();
    const tick = () => {
      const now = Date.now();
      const next = spinStep(yawRef.current, v, Math.min(48, now - last));
      last = now;
      v = next.velocity;
      show(next.yaw);
      spinRef.current = v ? requestAnimationFrame(tick) : null;
    };
    if (v) spinRef.current = requestAnimationFrame(tick);
  }, [show, stopSpin]);
  // The responder's callbacks read refs only while a gesture runs, never during render.
  // eslint-disable-next-line react-hooks/refs
  const pan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: (_, g) => Math.abs(g.dx) > 2 && Math.abs(g.dx) >= Math.abs(g.dy),
    onPanResponderTerminationRequest: () => false,
    onPanResponderGrant: () => { stopSpin(); startYaw.current = yawRef.current; },
    onPanResponderMove: (_, g) => show(yawForDrag(startYaw.current, g.dx, widthRef.current)),
    onPanResponderRelease: (_, g) => spin((g.vx / Math.max(1, widthRef.current)) * Math.PI),
    onPanResponderTerminate: () => undefined,
  // show/spin/stopSpin are stable callbacks; yaw and width are read through refs.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }), []);
  const onAction = useCallback((event: AccessibilityActionEvent) => {
    const turn = event.nativeEvent.actionName === 'increment' ? Math.PI / 6 : event.nativeEvent.actionName === 'decrement' ? -Math.PI / 6 : 0;
    if (turn) { stopSpin(); show(wrapAngle(yawRef.current + turn)); }
  }, [show, stopSpin]);

  const view = useMemo(() => ({width, height: STAGE_HEIGHT, yaw}), [width, yaw]);
  const faces = useMemo(() => width ? renderCar(carMesh(), view) : [], [view, width]);
  const {cx, groundY} = frame(view);
  const arc = {rx: width * 0.42, ry: 20, y: groundY + 8};
  const degrees = Math.round((((-yaw + INITIAL_YAW) * 180) / Math.PI % 360 + 360) % 360);
  return <View testID="connect-car-stage" style={[styles.stage, disabled && styles.dim]} {...pan.panHandlers}
    onLayout={e => { widthRef.current = e.nativeEvent.layout.width; setWidth(e.nativeEvent.layout.width); }}
    accessible accessibilityRole="adjustable" accessibilityLabel="360°" accessibilityValue={{text: `${degrees}°`}}
    accessibilityActions={[{name: 'increment'}, {name: 'decrement'}]} onAccessibilityAction={onAction}>
    {width > 0 && <Svg width={width} height={STAGE_HEIGHT}>
      <Defs>
        <LinearGradient id="floor" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={colors.white} stopOpacity="1"/>
          <Stop offset="1" stopColor={colors.page} stopOpacity="1"/>
        </LinearGradient>
        <RadialGradient id="shadow" cx="50%" cy="50%" rx="50%" ry="50%">
          <Stop offset="0" stopColor={colors.ink} stopOpacity="0.32"/>
          <Stop offset="0.6" stopColor={colors.ink} stopOpacity="0.12"/>
          <Stop offset="1" stopColor={colors.ink} stopOpacity="0"/>
        </RadialGradient>
      </Defs>
      <Rect x={0} y={0} width={width} height={STAGE_HEIGHT} fill="url(#floor)"/>
      {/* The 360° drag hint: a ground arc with arrowheads at both ends. */}
      <Path d={`M${cx - arc.rx} ${arc.y} A${arc.rx} ${arc.ry} 0 0 0 ${cx + arc.rx} ${arc.y}`} stroke={colors.switchOff} strokeWidth={1.4} fill="none" strokeLinecap="round"/>
      <Path d={`M${cx - arc.rx - 5} ${arc.y - 7} L${cx - arc.rx} ${arc.y} L${cx - arc.rx + 7} ${arc.y - 4}`} stroke={colors.switchOff} strokeWidth={1.4} fill="none" strokeLinecap="round" strokeLinejoin="round"/>
      <Path d={`M${cx + arc.rx + 5} ${arc.y - 7} L${cx + arc.rx} ${arc.y} L${cx + arc.rx - 7} ${arc.y - 4}`} stroke={colors.switchOff} strokeWidth={1.4} fill="none" strokeLinecap="round" strokeLinejoin="round"/>
      <Path d={shadowPath(view, 1.12)} fill="url(#shadow)"/>
      <Path d={shadowPath(view, 0.92)} fill="url(#shadow)"/>
      <G>
        {faces.map((face, i) => <Path key={i} d={face.d} fill={face.fill} stroke={face.fill} strokeWidth={0.7} strokeLinejoin="round"/>)}
      </G>
    </Svg>}
    <View pointerEvents="none" style={[styles.degreePill, {top: arc.y + arc.ry - 10}]}>
      <Text style={[type('caption', '360°'), styles.secondary]}>360°</Text>
    </View>
  </View>;
}

export function CarPanel({language, type, disabled}: {language: Language; type: TypeFor; disabled: boolean}) {
  const [state, dispatch] = useReducer(carReducer, initialCar);
  return <View style={styles.panel}>
    <CarStage type={type} disabled={disabled}/>
    <ChipRow chips={carChips(state, language)} disabled={disabled} type={type} onPress={dispatch}/>
  </View>;
}

// ---- TV ----

function TvScreen({width, playing, volume, volumeShown, title, caption, badge, progress, type}: {
  width: number; playing: boolean; volume: number; volumeShown: boolean; title: string; caption: string; badge: string; progress: boolean; type: TypeFor;
}) {
  const height = STAGE_HEIGHT - 26;
  const screenWidth = Math.min(width - 32, (height * 16) / 9);
  const left = (width - screenWidth) / 2;
  return <View style={StyleSheet.absoluteFill} pointerEvents="none">
    <Svg width={width} height={STAGE_HEIGHT}>
      <Defs>
        <LinearGradient id="tvSky" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor="#1B2233"/>
          <Stop offset="0.55" stopColor="#5A4A63"/>
          <Stop offset="1" stopColor="#E9A27A"/>
        </LinearGradient>
        <RadialGradient id="tvSun" cx="50%" cy="50%" rx="50%" ry="50%">
          <Stop offset="0" stopColor="#FFE3C4" stopOpacity="0.95"/>
          <Stop offset="0.4" stopColor={colors.brand} stopOpacity="0.45"/>
          <Stop offset="1" stopColor={colors.brand} stopOpacity="0"/>
        </RadialGradient>
        <LinearGradient id="tvFade" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0.5" stopColor="#000" stopOpacity="0"/>
          <Stop offset="1" stopColor="#000" stopOpacity="0.55"/>
        </LinearGradient>
      </Defs>
      <Rect x={left - 4} y={0} width={screenWidth + 8} height={height + 8} rx={12} fill={colors.ink}/>
      <G x={left} y={4}>
        <Rect width={screenWidth} height={height} rx={8} fill="url(#tvSky)"/>
        {[[0.12, 0.14], [0.3, 0.08], [0.52, 0.18], [0.7, 0.1], [0.86, 0.2], [0.22, 0.28], [0.62, 0.3]].map(([x, y], i) =>
          <Circle key={i} cx={x * screenWidth} cy={y * height} r={i % 2 ? 0.9 : 1.3} fill="#FFFFFF" opacity={0.7}/>)}
        <Circle cx={screenWidth * 0.66} cy={height * 0.6} r={height * 0.34} fill="url(#tvSun)"/>
        <Path d={`M0 ${height * 0.7} C${screenWidth * 0.2} ${height * 0.58} ${screenWidth * 0.35} ${height * 0.66} ${screenWidth * 0.5} ${height * 0.62} S${screenWidth * 0.82} ${height * 0.56} ${screenWidth} ${height * 0.64} L${screenWidth} ${height} L0 ${height}Z`} fill="#3A2F45" opacity={0.85}/>
        <Path d={`M0 ${height * 0.82} C${screenWidth * 0.25} ${height * 0.74} ${screenWidth * 0.45} ${height * 0.84} ${screenWidth * 0.62} ${height * 0.78} S${screenWidth * 0.9} ${height * 0.76} ${screenWidth} ${height * 0.8} L${screenWidth} ${height} L0 ${height}Z`} fill="#1E1A28"/>
        <Rect width={screenWidth} height={height} rx={8} fill="url(#tvFade)"/>
        {progress && <>
          <Rect x={14} y={height - 10} width={screenWidth - 28} height={3} rx={1.5} fill="#FFFFFF" opacity={0.3}/>
          <Rect x={14} y={height - 10} width={(screenWidth - 28) * 0.38} height={3} rx={1.5} fill={colors.brand}/>
        </>}
        {volumeShown && <>
          <Rect x={screenWidth - 16} y={height * 0.2} width={4} height={height * 0.46} rx={2} fill="#FFFFFF" opacity={0.3}/>
          <Rect x={screenWidth - 16} y={height * 0.2 + height * 0.46 * (1 - volume / TV_VOLUME.max)} width={4} height={height * 0.46 * (volume / TV_VOLUME.max)} rx={2} fill="#FFFFFF"/>
        </>}
      </G>
      <Path d={`M${width / 2 - 26} ${height + 8} L${width / 2 + 26} ${height + 8} L${width / 2 + 40} ${STAGE_HEIGHT - 4} L${width / 2 - 40} ${STAGE_HEIGHT - 4}Z`} fill={colors.switchOff}/>
      <Rect x={width / 2 - 56} y={STAGE_HEIGHT - 5} width={112} height={3} rx={1.5} fill={colors.secondary} opacity={0.5}/>
    </Svg>
    <View style={[styles.tvOverlay, {left: left + 14, width: screenWidth - 28, top: 16, height: height - 28}]}>
      <View style={styles.tvTop}>
        <Text numberOfLines={1} style={[type('caption', caption), styles.onScreenSoft]}>{caption}</Text>
        <View style={styles.tvBadge}><Text style={[type('caption', badge), styles.onScreen]}>{badge}</Text></View>
      </View>
      <View style={styles.tvBottom}>
        <Text numberOfLines={1} style={[type('label', title), styles.onScreen, styles.flex]}>{title}</Text>
      </View>
    </View>
  </View>;
}

export function TvPanel({language, type, disabled}: {language: Language; type: TypeFor; disabled: boolean}) {
  const [state, dispatch] = useReducer(tvReducer, initialTv);
  const [width, setWidth] = useState(0);
  const screen = tvScreen(state, language);
  return <View style={styles.panel}>
    <View testID="connect-tv-screen" style={[styles.stage, disabled && styles.dim]} onLayout={e => setWidth(e.nativeEvent.layout.width)}
      accessible accessibilityLabel={`${screen.caption}. ${screen.title}`}>
      {width > 0 && <TvScreen width={width} type={type} playing={state.playing} volume={state.volume} volumeShown={state.volumeShown} {...screen}/>}
    </View>
    <ChipRow chips={tvChips(state, language)} disabled={disabled} type={type} onPress={dispatch}/>
  </View>;
}

const styles = StyleSheet.create({
  panel: {gap: 12},
  flex: {flex: 1, minWidth: 0},
  stage: {height: STAGE_HEIGHT, borderRadius: 20, overflow: 'hidden', backgroundColor: colors.page},
  degreePill: {position: 'absolute', alignSelf: 'center', backgroundColor: colors.page, borderRadius: 999, paddingHorizontal: 8, paddingVertical: 1},
  chipRow: {flexDirection: 'row', gap: 8},
  control: {flex: 1, minHeight: 40, borderRadius: 999, paddingHorizontal: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    backgroundColor: colors.subtle, borderWidth: 1, borderColor: colors.border},
  controlOn: {backgroundColor: colors.ink, borderColor: colors.ink},
  controlDot: {width: 6, height: 6, borderRadius: 3, backgroundColor: colors.brand},
  ink: {color: colors.ink},
  inverse: {color: colors.white},
  secondary: {color: colors.secondary},
  dim: {opacity: 0.45},
  pressed: {opacity: 0.7},
  tvOverlay: {position: 'absolute', justifyContent: 'space-between'},
  tvTop: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8},
  tvBottom: {flexDirection: 'row', alignItems: 'center', gap: 8},
  tvBadge: {borderRadius: 4, borderWidth: 1, borderColor: 'rgba(255,255,255,0.55)', paddingHorizontal: 5, paddingVertical: 1},
  onScreen: {color: colors.white},
  onScreenSoft: {color: 'rgba(255,255,255,0.78)', flexShrink: 1},
});
