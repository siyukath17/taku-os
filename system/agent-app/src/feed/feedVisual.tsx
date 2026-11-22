import React, {useEffect, useLayoutEffect, useRef, type ReactNode} from 'react';
import {ActivityIndicator, Image, Platform, Pressable, StyleSheet, Switch, Text, TextInput, View} from 'react-native';
import Slider from '@react-native-community/slider';
import Svg, {Path, SvgAst} from 'react-native-svg';
import {ICONS, type IconName} from './feedCatalog';
import {accent, containsCjk, content, fills, frosted, main, radii, shell, surface, textStyle, tones, type Fill, type TextVariant, type Tone} from './feedTokens';
import {imageUri, isSvg, useSvgAsset} from './assets';
import {splitValue} from './value';
import iconPaths from '../theme/icons.json';
import {Icon, type IconName as AppIconName} from '../components/Icon';

export type FeedControlState = {disabled?: boolean; busy?: boolean; selected?: boolean};

export function FeedText({text, variant = 'body-md', tone = 'primary', align, lines, selectable, flex, strike, accessibilityRole}: {
  text: string; variant?: TextVariant; tone?: Tone; align?: 'left' | 'center' | 'right'; lines?: number; selectable?: boolean; flex?: number; strike?: boolean; accessibilityRole?: 'header';
}) {
  return <Text accessibilityRole={accessibilityRole} numberOfLines={lines} selectable={selectable} style={[textStyle(variant, containsCjk(text)), {color: tones[tone]},
    align ? {textAlign: align} : null, flex != null ? {flex, minWidth: 0} : null, strike && styles.struck]}>{text}</Text>;
}

export function FeedValue({text, size = 'xl', tone = 'primary', selectable}: {text: string; size?: 'lg' | 'xl' | '2xl'; tone?: Tone; selectable?: boolean}) {
  const number = textStyle(size === '2xl' ? 'heading-2xl' : size === 'lg' ? 'heading-lg' : 'heading-xl');
  return <View accessible accessibilityLabel={text} style={styles.valueRow}>
    {splitValue(text).map((part, index) => part.kind === 'number'
      ? <Text key={index} selectable={selectable} style={[number, styles.valueRun, {color: tones[tone]}]}>{part.text}</Text>
      : <Text key={index} selectable={selectable} style={[textStyle('body-lg'), styles.valueRun, styles.unit, {color: tones[tone]}]}>{part.text}</Text>)}
  </View>;
}

export function FeedImage({src, label, width = '100%', height, aspectRatio, radius = 0, fit = 'cover', onLoad, onError}: {
  src: string; label?: string; width?: number | `${number}%`; height?: number | `${number}%`; aspectRatio?: number; radius?: number;
  fit?: 'cover' | 'contain'; onLoad?: () => void; onError?: () => void;
}) {
  const mappedUri = imageUri(src);
  const uri = mappedUri ?? (/^(file:\/\/|content:\/\/|data:image\/)/.test(src) ? src : null);
  const svg = mappedUri !== null && isSvg(src);
  const asset = useSvgAsset(svg ? src : undefined);
  const callbacks = useRef({onLoad, onError});
  useLayoutEffect(() => {callbacks.current = {onLoad, onError};}, [onLoad, onError]);
  useEffect(() => {
    if (asset?.status === 'ready') callbacks.current.onLoad?.();
    else if (asset?.status === 'failed' || uri === null) callbacks.current.onError?.();
  }, [asset, src, uri]);
  const geometry = {width, height: height ?? (aspectRatio ? undefined : 120), aspectRatio, borderRadius: radius};
  if (svg) return <View accessible={!!label} accessibilityLabel={label} style={[geometry, {overflow: radius ? 'hidden' : 'visible'}]}>
    {asset?.status === 'ready' ? <SvgAst ast={asset.ast} override={{width: '100%', height: '100%', preserveAspectRatio: fit === 'contain' ? 'xMidYMid meet' : 'none'}}/> : null}
  </View>;
  if (!uri) return <View accessibilityLabel={label} style={geometry}/>;
  return <Image accessibilityLabel={label} source={{uri}} resizeMode={fit} style={geometry} onLoad={onLoad} onError={onError}/>;
}

const iconRef = (name: IconName) => `asset:feed/${ICONS[name]}`;
const weatherPaths: Record<string, string> = {
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.4 1.4m11.2 11.2L19 19M5 19l1.4-1.4M17.6 6.4 19 5',
  cloud: 'M6 18a4 4 0 0 1-.3-8 6 6 0 0 1 11.6-1A4.5 4.5 0 1 1 18 18H6Z',
  'partly-cloudy': 'M8 2v2M2 8h2M3.8 3.8l1.4 1.4M8 5a3 3 0 0 0-2.9 3.8M7 19a4 4 0 0 1-.2-8 5 5 0 0 1 9.7-.5A4.3 4.3 0 1 1 18 19H7Z',
  rain: 'M5 15a3 3 0 0 1 .2-6 5 5 0 0 1 9.7-.8A3.5 3.5 0 1 1 18 15H5M8 18l-1 3m6-3-1 3m6-3-1 3',
  snow: 'M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9 5l3 2 3-2M9 19l3-2 3 2M5 11l3 .5-.5-3.5M19 13l-3-.5.5 3.5M5 13l3-.5-.5 3.5M19 11l-3 .5.5-3.5',
  storm: 'M5 15a3 3 0 0 1 .2-6 5 5 0 0 1 9.7-.8A3.5 3.5 0 1 1 18 15M13 13l-4 5h4l-2 4',
  fog: 'M5 11a3 3 0 0 1 .2-6 5 5 0 0 1 9.7-.8A3.5 3.5 0 1 1 18 11M3 15h18M5 19h14',
};
export function FeedIcon({name, tone = 'primary', size = 'small'}: {name: string; tone?: Tone; size?: 'small' | 'medium' | 'large'}) {
  const pixels = {small: 16, medium: 24, large: 36}[size];
  if (Object.hasOwn(ICONS, name)) return <FeedImage src={iconRef(name as IconName)} width={pixels} height={pixels}/>;
  const path = Object.hasOwn(weatherPaths, name) ? weatherPaths[name] : undefined;
  if (path) return <Svg width={pixels} height={pixels} viewBox="0 0 24 24" accessibilityLabel={name.replace(/-/g, ' ')}>
    <Path d={path} stroke={tones[tone]} strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" fill="none"/>
  </Svg>;
  return Object.hasOwn(iconPaths, name) || name === 'tree' || name === 'tako' || name === 'panel'
    ? <Icon name={name as AppIconName} color={tones[tone]} size={pixels}/> : null;
}

export function FeedCornerTag({label, tone = 'ai', icon}: {label: string; tone?: 'ai' | 'solid' | 'quiet'; icon?: IconName}) {
  if (tone === 'solid') return <View style={[styles.tag, styles.tagSolid]}>
    <FeedImage src={iconRef(icon ?? 'lightning-white')} width={12} height={12}/><FeedText text={label} variant="body-sm" tone="inverseStrong"/>
  </View>;
  if (tone === 'quiet') return <View style={[styles.tag, styles.tagQuiet]}>
    {icon ? <FeedImage src={iconRef(icon)} width={8} height={8}/> : null}<FeedText text={label} variant="body-sm" tone="muted"/>
  </View>;
  return <View style={[styles.tag, styles.tagAi]}>
    <View style={StyleSheet.absoluteFill}><FeedImage src="asset:feed/slack/relevant-tag-bg.webp" width="100%" height="100%"/></View>
    <View pointerEvents="none" style={styles.tagGlow}/>
    <FeedImage src={iconRef(icon ?? 'sparkle-small')} width={8} height={8}/><FeedText text={label} variant="body-sm" tone="secondary"/>
  </View>;
}
export function FeedTags({items}: {items: string[]}) {
  return <View style={styles.tags}>{items.map(item => <View key={item} style={styles.chip}><FeedText text={item} variant="body-sm" tone="tertiary"/></View>)}</View>;
}
export function FeedHeader({title, icon, wordmark, wordmarkWidth, trailing, trailingIcon, count, gap}: {
  title?: string; icon?: string; wordmark?: string; wordmarkWidth?: number; trailing?: string; trailingIcon?: IconName; count?: string; gap?: 4 | 8;
}) {
  return <View style={styles.header}>
    <View style={[styles.rowCenter, {gap: gap ?? 4}]}>
      {wordmark ? <FeedImage src={wordmark} width={wordmarkWidth ?? 46} height={16} fit="contain" label={title}/>
        : icon ? <FeedImage src={icon} width={16} height={16} fit="contain"/> : null}
      {title && !wordmark ? <FeedText text={title} tone="secondary" lines={1}/> : null}
    </View>
    {count ? <FeedText text={count} variant="heading-lg" tone="secondary"/>
      : trailing ? <View style={[styles.rowCenter, styles.headerTrailing]}>
        {trailingIcon ? <FeedIcon name={trailingIcon}/> : null}<Text numberOfLines={1} style={[textStyle('body-md', containsCjk(trailing)), {color: tones.secondary}, styles.shrink]}>{trailing}</Text>
      </View> : null}
  </View>;
}
export function FeedSurface({kind, children}: {kind: 'card' | 'control' | 'sheet'; children: ReactNode}) {
  return <View style={kind === 'control' ? styles.surfaceControl : kind === 'sheet' ? styles.sheet : styles.surfaceCard}>{children}</View>;
}
export function FeedInset({children, fill = 'content-100', radius = radii.xl}: {children: ReactNode; fill?: Fill; radius?: number}) {
  return <View style={[styles.inset, {backgroundColor: fills[fill], borderRadius: radius}]}>{children}</View>;
}
export function FeedButton({label, variant = 'secondary', appearance = 'light', size = 'lg', icon, flex, busy, selected, disabled, onPress}: FeedControlState & {
  label: string; variant?: 'primary' | 'secondary'; appearance?: 'light' | 'dark'; size?: 'lg' | 'md'; icon?: 'sparkle'; flex?: number; onPress: () => void;
}) {
  const primary = variant === 'primary', inverse = appearance === 'dark' ? !primary : primary;
  const ink = inverse ? main.surface : main.foreground;
  const background = appearance === 'dark' ? (primary ? main.surface : surface[200]) : (primary ? main.foreground : content[200]);
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{busy, selected, disabled: disabled || busy}} disabled={disabled || busy} onPress={onPress}
    style={({pressed}) => [styles.button, size === 'md' ? styles.buttonMd : styles.buttonLg, {backgroundColor: background},
      flex != null ? {flex, minWidth: 0} : {alignSelf: 'stretch'}, selected && styles.selected, (pressed || busy) && styles.pressed, disabled && styles.disabled]}>
    {busy ? <ActivityIndicator size={size === 'md' ? 12 : 16} color={ink}/> : icon === 'sparkle' ? <FeedIcon name="sparkle"/> : null}
    <FeedText text={label} variant={size === 'md' ? 'body-md' : 'body-lg'} tone={inverse ? 'inverse' : 'foreground'} lines={1}/>
  </Pressable>;
}
export function FeedPressable({label, role = 'button', expanded, onPress, onLongPress, children, disabled, busy, selected}: FeedControlState & {
  label: string; role?: 'button' | 'link'; expanded?: boolean; onPress: () => void; onLongPress?: () => void; children: ReactNode;
}) {
  return <Pressable accessibilityRole={role} accessibilityLabel={label} accessibilityState={{expanded, disabled, busy, selected}} disabled={disabled || busy}
    onPress={onPress} onLongPress={onLongPress} style={({pressed}) => [pressed && styles.pressed, disabled && styles.disabled]}>{children}</Pressable>;
}
export function FeedItem({leading, children, trailing, corner, onPress, label, layout = 'row'}: {
  leading?: ReactNode; children: ReactNode; trailing?: ReactNode; corner?: ReactNode; onPress?: () => void; label?: string; layout?: 'row' | 'tile';
}) {
  const body = <><View style={[styles.itemLead, layout === 'tile' && styles.tile]}>{leading}{children}</View>{trailing}
    {corner ? <View style={styles.itemCorner}>{corner}</View> : null}</>;
  return onPress ? <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({pressed}) => [styles.item, layout === 'tile' && styles.tile, pressed && styles.pressed]}>{body}</Pressable>
    : <View style={[styles.item, layout === 'tile' && styles.tile]}>{body}</View>;
}
export function FeedSelection({label, kind, checked, disabled, children, onPress, compact = false, appearance = 'selection'}: {
  label: string; kind: 'checkbox' | 'radio'; checked: boolean | 'mixed'; disabled?: boolean; children: ReactNode; onPress: () => void; compact?: boolean; appearance?: 'selection' | 'checklist';
}) {
  return <Pressable accessibilityRole={kind} accessibilityLabel={label} accessibilityState={{checked, selected: checked === true, disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.checkRow, appearance === 'checklist' ? styles.checklistRow : !compact && styles.selection, pressed && styles.pressed, disabled && styles.disabled]}>
    {compact ? children : <View style={styles.itemBody}>{children}</View>}
    <View style={[styles.checkbox, kind === 'radio' && styles.radio, checked !== false && styles.checkboxOn]}>
      {checked !== false ? <Svg width={12} height={12} viewBox="0 0 12 12"><Path d={checked === 'mixed' ? 'M2.5 6h7' : 'M2.5 6.2l2.3 2.3 4.7-5'} stroke="#fff" strokeWidth={1.6} fill="none" strokeLinecap="round" strokeLinejoin="round"/></Svg> : null}
    </View>
  </Pressable>;
}
const progressTones = {green: accent.green, brand: accent.brand, foreground: main.foreground};
export function FeedProgress({value, tone = 'green', label, valueText}: {value: number | null; tone?: 'green' | 'brand' | 'foreground'; label?: string; valueText?: string}) {
  const track = <View accessible accessibilityRole="progressbar" accessibilityLabel={label}
    accessibilityValue={value === null ? {text: valueText ?? 'Unknown'} : {min: 0, max: 100, now: Math.round(value * 100)}} style={styles.track}>
    {value !== null && <View style={[styles.trackFill, {width: `${Math.max(0, Math.min(1, value)) * 100}%`, backgroundColor: progressTones[tone]}]}/>}</View>;
  return label || valueText ? <View style={styles.stack}>{label && <FeedText text={label} tone="secondary"/>}{track}{valueText && <FeedText text={valueText} variant="body-sm" tone="secondary"/>}</View> : track;
}
export function FeedStatus({text, state, children, selectable}: {text: string; state: 'info' | 'pending' | 'error' | 'review'; children?: ReactNode; selectable?: boolean}) {
  return <View accessibilityRole={state === 'error' || state === 'review' ? 'alert' : undefined} accessibilityLiveRegion="polite" accessibilityState={{busy: state === 'pending'}} style={styles.status}>
    <View style={styles.statusRow}>{state === 'pending' ? <FeedSpinner/> : state === 'error' || state === 'review' ? <FeedIcon name="info" tone="secondary"/> : null}
      <FeedText text={text} variant="body-sm" tone="secondary" selectable={selectable} flex={1}/></View>{children}
  </View>;
}
export function FeedPlaceholder({kind}: {kind: 'text' | 'value' | 'item' | 'media' | 'action'}) {
  return <View accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
    style={[styles.placeholder, kind === 'value' ? styles.placeholderValue : kind === 'item' ? styles.placeholderItem : kind === 'media' ? styles.placeholderMedia : kind === 'action' ? styles.placeholderAction : null]}/>;
}
export function FeedMediaFrame({kind, aspectRatio, height, children}: {kind: 'image' | 'audio' | 'video' | 'camera'; aspectRatio?: number; height?: number; children: ReactNode}) {
  return <View style={[styles.media, {height, aspectRatio: aspectRatio ?? (kind === 'video' ? 16 / 9 : kind === 'camera' ? 3 / 4 : undefined)},
    (kind === 'video' || kind === 'camera') && styles.nativeMedia]}>{children}</View>;
}
export function FeedTextInput({value, onChangeText, onSubmit, onBlur, label, hint, placeholder, mode = 'text', multiline, disabled, secure, keyboardType}: {
  value: string; onChangeText: (text: string) => void; onSubmit?: () => void; onBlur?: () => void; label?: string; hint?: string; placeholder?: string;
  mode?: 'text' | 'decimal' | 'search'; multiline?: boolean; disabled?: boolean; secure?: boolean;
  keyboardType?: 'default' | 'email-address' | 'numeric' | 'phone-pad' | 'number-pad' | 'decimal-pad' | 'url';
}) {
  return <TextInput accessibilityLabel={label ?? placeholder} accessibilityHint={hint} accessibilityRole={mode === 'search' ? 'search' : undefined}
    value={value} onChangeText={onChangeText} onSubmitEditing={onSubmit} onBlur={onBlur} placeholder={placeholder} keyboardType={keyboardType ?? (mode === 'decimal' ? 'decimal-pad' : 'default')}
    returnKeyType={mode === 'search' ? 'search' : undefined} secureTextEntry={secure} multiline={multiline} editable={!disabled}
    placeholderTextColor={tones.muted} selectionColor={content[300]} cursorColor={main.foreground} selectionHandleColor={main.foreground} underlineColorAndroid="transparent"
    style={[styles.input, textStyle('body-md', containsCjk(value || placeholder || '')), multiline && styles.multiline, disabled && styles.disabled]}/>;
}
export function FeedToggle({value, onChange, label, disabled}: {value: boolean; onChange: (value: boolean) => void; label?: string; disabled?: boolean}) {
  return <View style={styles.toggleRow}>{label && <FeedText text={label} flex={1}/>}<Switch accessibilityLabel={label} accessibilityState={{checked: value, disabled}} value={value} disabled={disabled}
    {...(Platform.OS === 'web' && {activeThumbColor: main.surface})} trackColor={{false: content[300], true: main.foreground}} thumbColor={value ? main.surface : surface[800]}
    ios_backgroundColor={content[300]} hitSlop={12} onValueChange={onChange}/></View>;
}
export function FeedSlider({value, min, max, step, label, disabled, onChange, onComplete}: {
  value: number; min: number; max: number; step?: number; label?: string; disabled?: boolean; onChange?: (value: number) => void; onComplete?: (value: number) => void;
}) {
  return <Slider accessibilityLabel={label} minimumValue={min} maximumValue={max} step={step ?? 0} value={value} disabled={disabled}
    minimumTrackTintColor={main.foreground} maximumTrackTintColor={content[300]} thumbTintColor={main.foreground} style={[styles.slider, disabled && styles.disabled]}
    onValueChange={onChange} onSlidingComplete={onComplete}/>;
}
export function FeedDivider({direction = 'horizontal'}: {direction?: 'horizontal' | 'vertical'}) {
  return <View style={direction === 'vertical' ? styles.dividerVertical : styles.divider}/>;
}
export function FeedSpinner({size = 'small'}: {size?: 'small' | 'large'}) {
  return <ActivityIndicator size={size} color={tones.secondary}/>;
}

const styles = StyleSheet.create({
  rowCenter: {flexDirection: 'row', alignItems: 'center'}, shrink: {flexShrink: 1}, struck: {textDecorationLine: 'line-through'},
  valueRow: {flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', maxWidth: '100%'}, valueRun: {maxWidth: '100%', flexShrink: 1}, unit: {marginLeft: 1},
  header: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, minHeight: 20}, headerTrailing: {gap: 4, flexShrink: 1},
  tag: {flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', paddingVertical: 2, borderTopLeftRadius: radii.lg, borderBottomRightRadius: radii.lg, overflow: 'hidden'},
  tagAi: {paddingHorizontal: 6, gap: 4}, tagGlow: {position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, borderTopLeftRadius: radii.lg, borderBottomRightRadius: radii.lg, borderWidth: 1, borderColor: 'rgba(255,255,255,0.24)'},
  tagSolid: {paddingHorizontal: 4, gap: 2, backgroundColor: accent.uberBlue}, tagQuiet: {paddingHorizontal: 6, gap: 4, backgroundColor: content[50]},
  tags: {flexDirection: 'row', flexWrap: 'wrap', gap: 4}, chip: {paddingHorizontal: 4, paddingVertical: 2, borderRadius: radii.base, backgroundColor: content[50]},
  surfaceCard: {...shell.card, overflow: 'hidden'}, surfaceControl: {...shell.control, height: undefined, minHeight: shell.control.height, overflow: 'hidden'}, sheet: {...shell.card, borderBottomLeftRadius: 0, borderBottomRightRadius: 0, overflow: 'hidden'}, inset: {overflow: 'hidden'},
  button: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, borderRadius: radii.full, overflow: 'hidden', paddingHorizontal: 12},
  buttonLg: {height: 40}, buttonMd: {height: 32}, pressed: {opacity: 0.6}, disabled: {opacity: 0.4}, selected: {borderWidth: 1, borderColor: main.foreground},
  item: {flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, borderRadius: radii.lg, backgroundColor: frosted.medium, overflow: 'hidden'},
  itemLead: {flexDirection: 'row', alignItems: 'center', flex: 1, minWidth: 0, gap: 8},
  tile: {flexDirection: 'column', alignItems: 'stretch'}, itemBody: {flex: 1, minWidth: 0, gap: 4}, itemCorner: {position: 'absolute', left: 0, top: 0},
  checkRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8}, selection: {padding: 12, borderRadius: radii.xl, backgroundColor: content[50]},
  // Figma 115:1699 has no row fill. 48dp is the interactive adaptation of its 20px text row.
  checklistRow: {minHeight: 48, paddingVertical: 12, backgroundColor: 'transparent'},
  checkbox: {width: 20, height: 20, borderRadius: 5, borderWidth: 1.667, borderColor: content[600], alignItems: 'center', justifyContent: 'center', flexShrink: 0},
  checkboxOn: {backgroundColor: main.foreground, borderColor: main.foreground}, radio: {borderRadius: radii.full},
  track: {height: 8, borderRadius: radii.full, backgroundColor: content[200], overflow: 'hidden'}, trackFill: {height: 8, borderRadius: radii.full}, stack: {gap: 6},
  status: {paddingHorizontal: 12, paddingVertical: 8, borderRadius: radii.xl, backgroundColor: content[50], gap: 8, minWidth: 0},
  statusRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  placeholder: {height: 12, minWidth: 48, width: '65%', borderRadius: radii.md, backgroundColor: content[100]},
  placeholderValue: {height: 32}, placeholderItem: {height: 48, width: '100%'}, placeholderMedia: {height: 72, width: '100%', borderRadius: radii.xl},
  placeholderAction: {height: 40, width: 120, borderRadius: radii.full}, media: {width: '100%', borderRadius: radii.xl, overflow: 'hidden', backgroundColor: content[100]}, nativeMedia: {backgroundColor: main.foreground},
  input: {minHeight: 40, paddingHorizontal: 12, paddingVertical: 10, borderRadius: radii.full, minWidth: 0, color: tones.primary, backgroundColor: content[100]},
  multiline: {borderRadius: radii.xl, textAlignVertical: 'top'}, toggleRow: {flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 40},
  slider: {height: 40, width: '100%'}, divider: {height: StyleSheet.hairlineWidth, width: '100%', backgroundColor: content[200], marginVertical: 8},
  dividerVertical: {width: StyleSheet.hairlineWidth, alignSelf: 'stretch', backgroundColor: content[200], marginHorizontal: 8},
});
