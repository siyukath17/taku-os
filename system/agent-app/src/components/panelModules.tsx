/**
 * Panel module layout. The reply contract keeps one Card root; on a Panel that root draws no surface.
 * Each top-level child becomes a module on the wallpaper, following the Figma Panel standard
 * (Taku Mobile OS 1532:2084): photos and labels sit directly on the wallpaper, facts become
 * Surface/900 tiles two per row, longer content takes a full-width module, and the page scrolls.
 * Nothing scrolls inside a module.
 */
import React, {type ReactElement, type ReactNode} from 'react';
import {StyleSheet, View} from 'react-native';
import {useStateValue, type Spec} from '@json-render/react-native';
import type {UIElement} from '@json-render/core';
import {FeedModule, PanelActionBar, PanelPill, PanelRow} from '../feed/feedVisual';
import {spacing} from '../feed/feedTokens';
import {fieldIsTile, fieldVariant} from '../model/panelScene';
import type {UiConfig, UiValue} from '../model/genui';

type RendererProps = {element: UIElement; spec: Spec; registry: unknown; loading?: boolean; fallback?: unknown};
type Child = ReactElement<RendererProps>;
type Look = 'raised' | 'quiet';
type Module = {key: string; kind: 'bare' | 'wide' | 'tile'; node: ReactNode; look?: Look; padding?: number};
/** What the user presses or changes sits on white and lifts; what they only read stays translucent (GenUI 场景框架 v0.1). */
const PRESSABLE = new Set(['Field', 'Pressable', 'ChoiceGroup', 'Button', 'ItemRow', 'Switch', 'Slider', 'Checkbox', 'Counter', 'TextInput', 'SearchBar']);

/** Drawn straight on the wallpaper; these draw their own surfaces or need none. */
const BARE = new Set(['Text', 'Value', 'Tags', 'Image', 'Overlay', 'MediaSurface', 'RouteView', 'Collection', 'ChoiceGroup', 'Status', 'Button', 'Spacer',
  'Spinner', 'ProgressBar', 'CornerTag', 'AppHeader', 'Card']);
/** Children that make a Row a row of tiles. */
const TILEABLE = new Set(['Inset', 'Metric', 'TimeDisplay', 'Column', 'Pressable', 'Progress']);
/** A Column holding any of these is structure, not content: lay its children out as modules. */
const STRUCTURE = new Set(['Row', 'Inset', 'Metric', 'TimeDisplay', 'Collection', 'Image', 'Overlay', 'Tags', 'Column', 'MediaSurface', 'RouteView',
  'ChoiceGroup', 'Button', 'Progress']);
/** Width a half tile holds (CJK counts double): its value is set at 24 in a 160 column, its label at 14. */
const TILE_BUDGET = 30, TILE_VALUE_BUDGET = 12, PILL_VALUE_BUDGET = 8;
/** A paragraph longer than this reads on a surface, not on the wallpaper. */
const LABEL_BUDGET = 48;

const widget = (spec: Spec, key: string): {key: string; element?: UIElement} => {
  const region = spec.elements[key];
  if (region?.type !== 'AssemblyRegion') return {key, element: region};
  const inner = region.children?.[0];
  return {key, element: inner ? spec.elements[inner] : undefined};
};
const conditional = (element: UIElement) => element.visible !== undefined || !!(element as {repeat?: unknown}).repeat;

function resolve(value: unknown, data: unknown): unknown {
  if (value && typeof value === 'object' && '$state' in value) {
    const path = (value as {$state: unknown}).$state;
    if (typeof path !== 'string' || !path.startsWith('/data/')) return undefined;
    return path.slice(6).split('/').reduce<unknown>((node, part) => node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined, data);
  }
  return value;
}
const weight = (text: string) => [...text].reduce((sum, char) => sum + (/[　-鿿가-힯＀-￯]/.test(char) ? 2 : 1), 0);
function textWeight(spec: Spec, key: string, data: unknown, depth = 0): number {
  const {element} = widget(spec, key);
  if (!element || depth > 6) return 0;
  let total = 0;
  for (const prop of ['text', 'title', 'label', 'subtitle', 'value', 'items'] as const) {
    const value = resolve(element.props[prop], data);
    if (typeof value === 'string') total += weight(value);
    else if (value && typeof value === 'object' && !Array.isArray(value) && 'value' in value) total += weight(String((value as {value: unknown}).value ?? ''));
    else if (Array.isArray(value)) total += value.reduce<number>((sum, item) => sum + weight(String((item as {label?: unknown})?.label ?? '')) + 2, 0);
  }
  return total + (element.children ?? []).reduce((sum, child) => sum + textWeight(spec, child, data, depth + 1), 0);
}
function valueWeight(element: UIElement, data: unknown): number {
  const value = resolve(element.props.value, data);
  if (typeof value === 'string') return weight(value);
  if (value && typeof value === 'object' && 'value' in value) return weight(String((value as {value: unknown}).value ?? ''));
  return 0;
}
const size = (element: UIElement, data: unknown) => {
  const config = resolve(element.props.config, data);
  return config && typeof config === 'object' ? (config as {size?: unknown}).size : undefined;
};

export function PanelModules({children}: {children: ReactNode}) {
  const data = useStateValue<unknown>('/data');
  const nodes = React.Children.toArray(children).filter(React.isValidElement) as Child[];
  const template = nodes.find(node => node.props?.spec && node.props.element);
  if (!template) return <>{children}</>;
  const {spec} = template.props;
  const draw = (key: string): ReactNode => {
    const element = spec.elements[key];
    return element ? React.createElement(template.type, {...template.props, key, element}) : null;
  };

  const modules: Module[] = [];
  const collect = (key: string, depth: number) => {
    const {element} = widget(spec, key);
    if (!element) return;
    const type = element.type, kids = element.children ?? [];
    if (type === 'Divider') return; // modules are already separated
    if (type === 'Text' && weight(String(resolve(element.props.text, data) ?? '')) > LABEL_BUDGET) {
      modules.push({key, kind: 'wide', node: <FeedModule key={key} look="quiet">{draw(key)}</FeedModule>});
      return;
    }
    if (BARE.has(type)) { modules.push({key, kind: 'bare', node: draw(key)}); return; }
    // A press target around a photo or label is still that photo or label.
    if (type === 'Pressable' && kids.length === 1 && BARE.has(widget(spec, kids[0]).element?.type ?? '')) { modules.push({key, kind: 'bare', node: draw(key)}); return; }
    if (type === 'Column' && !conditional(element) && depth < 3 && kids.some(child => STRUCTURE.has(widget(spec, child).element?.type ?? ''))) {
      kids.forEach(child => collect(child, depth + 1));
      return;
    }
    // A lone action needs no box and no row: it spans the column on the wallpaper, like the Figma primary.
    if (type === 'Row' && !conditional(element) && kids.length === 1 && widget(spec, kids[0]).element?.type === 'Button') {
      modules.push({key, kind: 'bare', node: <View key={key} style={styles.lone}>{draw(kids[0])}</View>});
      return;
    }
    if (type === 'Row' && !conditional(element) && kids.length > 1) {
      const types = kids.map(child => {
        const inner = widget(spec, child).element;
        // Round Insets are pills, not tiles.
        return inner?.type === 'Inset' && Number(inner.props.radius ?? 0) >= 9999 ? 'Pill' : inner?.type ?? '';
      });
      if (types.every(child => child === 'Pill' || child === 'Text')) { modules.push({key, kind: 'bare', node: draw(key)}); return; }
      if (types.every(child => child === 'Button')) {
        modules.push({key, kind: 'bare', node: <View key={key} style={styles.bar}><PanelActionBar.Provider value={true}>
          {kids.map(child => <React.Fragment key={child}>{draw(child)}</React.Fragment>)}
        </PanelActionBar.Provider></View>});
        return;
      }
      // Two or three facts whose values are a few characters each read as status pills, not tiles.
      if (kids.length <= 3 && types.every(child => child === 'Metric') && kids.every(child => {
        const inner = widget(spec, child).element!;
        return valueWeight(inner, data) <= PILL_VALUE_BUDGET && (size(inner, data) === 'small_size' || kids.length === 3);
      })) {
        modules.push({key, kind: 'bare', node: <View key={key} style={styles.pills}><PanelPill.Provider value={true}>
          {kids.map(child => <React.Fragment key={child}>{draw(child)}</React.Fragment>)}
        </PanelPill.Provider></View>});
        return;
      }
      if (types.every(child => TILEABLE.has(child)) && kids.length <= 3) {
        modules.push({key, kind: 'bare', node: <View key={key} style={styles.row}>{kids.map(child => <FeedModule key={child} flex={1}
          look={PRESSABLE.has(widget(spec, child).element?.type ?? '') ? 'raised' : 'quiet'}>{draw(child)}</FeedModule>)}</View>});
        return;
      }
    }
    // A device or setting with real state pairs as a tile like any short fact.
    // Lights pair as half tiles; climate, two-state settings and media take the full row.
    if (type === 'Field') {
      const config = resolve(element.props.config, data) as UiConfig | undefined;
      if (config?.editor === 'toggle' || config?.editor === 'range') {
        const variant = fieldVariant(config, resolve(element.props.value, data) as UiValue | undefined, resolve(element.props.text, data) as string | undefined);
        if (fieldIsTile(variant)) { modules.push({key, kind: 'tile', node: draw(key), look: 'raised', padding: variant === 'dimmer' ? 0 : 12}); return; }
        modules.push({key, kind: 'wide', node: <FeedModule key={key} look="raised">{draw(key)}</FeedModule>});
        return;
      }
    }
    if ((type === 'Metric' || type === 'TimeDisplay' || type === 'Inset') && size(element, data) !== 'full_size'
      && textWeight(spec, key, data) <= TILE_BUDGET && valueWeight(element, data) <= TILE_VALUE_BUDGET) {
      modules.push({key, kind: 'tile', node: draw(key), look: 'quiet'});
      return;
    }
    modules.push({key, kind: 'wide', node: <FeedModule key={key} look={PRESSABLE.has(type) ? 'raised' : 'quiet'}>{draw(key)}</FeedModule>});
  };
  // React.Children.toArray rewrites keys, so find each child's spec key by element identity.
  const keys = new Map(Object.entries(spec.elements).map(([key, element]) => [element, key] as const));
  for (const node of nodes) {
    const key = keys.get(node.props.element);
    if (key) collect(key, 0); else modules.push({key: String(node.key), kind: 'bare', node});
  }

  // Consecutive short tiles pair up two per row; a single tile keeps its half width, as in the Figma standard.
  const out: ReactNode[] = [];
  for (let index = 0; index < modules.length; index++) {
    const current = modules[index];
    if (current.kind !== 'tile') { out.push(current.kind === 'bare' ? <React.Fragment key={current.key}>{current.node}</React.Fragment> : current.node); continue; }
    const next = modules[index + 1]?.kind === 'tile' ? modules[++index] : null;
    // A tile with no neighbour in a grid gets the full row; it keeps half width only as the odd end of a grid.
    if (!next && modules[index - 1]?.kind !== 'tile') { out.push(<FeedModule key={current.key} look={current.look} padding={current.padding}><PanelRow.Provider value={true}>{current.node}</PanelRow.Provider></FeedModule>); continue; }
    out.push(<View key={current.key} style={styles.row}>
      <FeedModule flex={1} minHeight={TILE_HEIGHT} look={current.look} padding={current.padding}>{current.node}</FeedModule>
      {next ? <FeedModule flex={1} minHeight={TILE_HEIGHT} look={next.look} padding={next.padding}>{next.node}</FeedModule> : <View style={styles.half}/>}
    </View>);
  }
  return <>{out}</>;
}
const TILE_HEIGHT = 120;
const styles = StyleSheet.create({
  row: {flexDirection: 'row', alignItems: 'stretch', gap: spacing[8]}, half: {flex: 1},
  pills: {flexDirection: 'row', gap: spacing[8]}, lone: {alignItems: 'stretch'},
  bar: {flexDirection: 'row', alignItems: 'center', gap: spacing[4], padding: 6, borderRadius: 9999, backgroundColor: 'rgba(255,255,255,0.86)',
    boxShadow: '0px 12px 30px -16px rgba(30,40,70,0.5)'},
});
