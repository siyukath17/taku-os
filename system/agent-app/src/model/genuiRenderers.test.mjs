import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';
import {existsSync, readFileSync} from 'node:fs';
import ts from 'typescript';
import {cardState, templateSpec} from './genui.ts';
import {formatUiValue} from '../components/genuiFormat.ts';

const runtime = {host: null, contexts: new Map(), links: []};
globalThis[Symbol.for('taku.genui.renderer-test')] = runtime;
const access = "const runtime = globalThis[Symbol.for('taku.genui.renderer-test')];";
const moduleUrl = source => `data:text/javascript,${encodeURIComponent(`${access}\n${source}`)}`;
const react = moduleUrl(`
  export const createElement = (type, props, ...children) => ({type, props: {...props, ...(children.length ? {children: children.length === 1 ? children[0] : children} : {})}});
  export const createContext = value => {const context = {value}; context.Provider = {context}; return context;};
  export const useContext = context => runtime.contexts.has(context) ? runtime.contexts.get(context) : context.value;
  export const useState = initial => runtime.host.state(initial);
  export const useRef = initial => runtime.host.state(() => ({current: initial}))[0];
  export const useEffect = (effect, deps) => runtime.host.effect(effect, deps);
  export const useLayoutEffect = useEffect;
  export const useMemo = fn => fn();
  export const useCallback = fn => fn;
  export const useSyncExternalStore = (_subscribe, snapshot) => snapshot();
  export const useDebugValue = () => {};
  export const useId = () => 'test';
  const Children = {count: children => children == null ? 0 : Array.isArray(children) ? children.length : 1};
  export default {createElement, Fragment: 'Fragment', Children, useState, useRef, useEffect, useLayoutEffect, useMemo, useCallback, useSyncExternalStore, useDebugValue};
`);
const native = moduleUrl(`
  export const View = 'View', Text = 'Text', TextInput = 'TextInput', Switch = 'Switch', Pressable = 'Pressable', ScrollView = 'ScrollView', Image = 'Image', ActivityIndicator = 'ActivityIndicator';
  export const Platform = {OS: 'android'};
  export const PanResponder = {create: () => ({panHandlers: {}})};
  export const StyleSheet = {create: value => value, absoluteFill: {}, hairlineWidth: 1};
  export const AppState = {currentState: 'active', addEventListener: () => ({remove() {}})};
  export const Linking = {openSettings: async () => {}};
`);
const root = new URL('../', import.meta.url).href;
const mocks = new Map([
  ['react', react], ['react-native', native],
  ['@react-native-community/slider', moduleUrl("export default 'Slider';")],
  ['@json-render/react-native', moduleUrl(`
    export const useBoundProp = (fallback, binding) => binding
      ? [binding.endsWith('/draft') ? runtime.host.field.draft : runtime.host.bound.has(binding) ? runtime.host.bound.get(binding) : fallback,
        next => {if (binding.endsWith('/draft')) runtime.host.field.draft = next; else runtime.host.bound.set(binding, next); runtime.host.writes.push(next);}]
      : runtime.host.state(fallback);
    export const useStateValue = path => path === '/assembly' ? runtime.host.assembly : path === '/data' ? runtime.host.data : runtime.host.field;
  `)],
  ['expo', moduleUrl('export const requireOptionalNativeModule = () => null;')],
  ['expo-linking', moduleUrl('export const openURL = async url => {runtime.links.push(url);};')],
  ['expo-camera', moduleUrl("export const CameraView = 'CameraView', useCameraPermissions = () => [null, async () => {throw new Error('Camera unavailable in host');}];")],
  ['expo-video', moduleUrl("export const VideoView = 'VideoView', createVideoPlayer = () => {throw new Error('Playback unavailable in host');};")],
  ['expo-file-system', moduleUrl('export class File {} export const Paths = {};')],
  ['expo-file-system/legacy', moduleUrl("export const copyAsync = async () => {throw new Error('Files unavailable in host');};")],
  ['expo-media-library', moduleUrl('export const Asset = {};')],
  ['expo-image-manipulator', moduleUrl('export const ImageManipulator = {}, SaveFormat = {};')],
  ['react-native-svg', moduleUrl("export default 'Svg'; export const Defs = 'Defs', LinearGradient = 'LinearGradient', Rect = 'Rect', Stop = 'Stop', Path = 'Path', SvgAst = 'SvgAst', Filter = 'Filter', FeColorMatrix = 'FeColorMatrix', Image = 'SvgImage', Line = 'Line', Circle = 'Circle', Polygon = 'Polygon', Polyline = 'Polyline'; export const parse = xml => {if (!xml.startsWith('<svg')) throw new Error('Invalid SVG'); return {tag: 'svg', xml};};")],
]);
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (mocks.has(specifier)) return {url: mocks.get(specifier), shortCircuit: true};
    if (context.parentURL?.startsWith(root) && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) {
      for (const suffix of ['.tsx', '.ts', '/index.ts']) {
        const url = new URL(`${specifier}${suffix}`, context.parentURL);
        if (existsSync(url)) return {url: url.href, shortCircuit: true};
      }
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url.startsWith('file:') && url.endsWith('.json')) return {format: 'module', shortCircuit: true, source: `export default ${readFileSync(new URL(url), 'utf8')};`};
    if (url.startsWith(root) && /\.tsx?$/.test(url)) return {format: 'module', shortCircuit: true,
      source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {compilerOptions: {jsx: ts.JsxEmit.React, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022}}).outputText};
    return nextLoad(url, context);
  },
});
const {Field, ChoiceGroup, Counter, ControlledText} = await import('../components/genuiInputs.tsx');
const {TimeDisplay, Metric, Collection, CardLanguage} = await import('../components/genuiWidgets.tsx');
const {ProgressiveImage} = await import('../components/genuiAssembly.tsx');
const {FeedValue} = await import('../feed/feedVisual.tsx');

function mount(Component, props, field = {draft: null}, language = 'en') {
  const host = {field, bound: new Map(), writes: [], slots: new Map(), path: '', index: 0, tree: [], effects: [], assembly: undefined, data: undefined,
    state(initial) {
      const key = `${this.path}:${this.index++}`;
      if (!this.slots.has(key)) this.slots.set(key, typeof initial === 'function' ? initial() : initial);
      return [this.slots.get(key), next => this.slots.set(key, typeof next === 'function' ? next(this.slots.get(key)) : next)];
    },
    effect(callback, deps) {
      const key = `${this.path}:${this.index++}`, previous = this.slots.get(key);
      if (!previous || !deps || deps.some((value, index) => !Object.is(value, previous.deps?.[index]))) {
        this.slots.set(key, {deps, cleanup: previous?.cleanup});
        this.effects.push(() => {previous?.cleanup?.(); this.slots.get(key).cleanup = callback();});
      }
    },
    render() {
      runtime.host = this;
      runtime.contexts.set(CardLanguage, language);
      const visit = (node, path) => {
        if (node == null || typeof node === 'boolean') return [];
        if (Array.isArray(node)) return node.flatMap((child, index) => visit(child, `${path}.${index}`));
        if (typeof node !== 'object') return [node];
        if (node.type?.context) {
          const context = node.type.context, previous = runtime.contexts.get(context);
          runtime.contexts.set(context, node.props.value);
          const children = visit(node.props.children, `${path}.provider`);
          if (previous === undefined) runtime.contexts.delete(context); else runtime.contexts.set(context, previous);
          return children;
        }
        if (typeof node.type === 'function') {
          this.path = path; this.index = 0;
          return visit(node.type(node.props), `${path}.rendered`);
        }
        return [{props: node.props, children: visit(node.props.children, `${path}.children`)}];
      };
      this.tree = visit({type: Component, props}, 'root');
      for (const effect of this.effects.splice(0)) effect();
      return this;
    },
    findAll(predicate) {
      const visit = nodes => nodes.flatMap(node => typeof node === 'object' ? [...(predicate(node.props) ? [node] : []), ...visit(node.children)] : []);
      return visit(this.tree);
    },
    input() {return this.findAll(props => typeof props.onChangeText === 'function')[0];},
    toggle() {return this.findAll(props => typeof props.onValueChange === 'function' && typeof props.accessibilityState?.checked === 'boolean')[0];},
    range() {return this.findAll(props => typeof props.onValueChange === 'function' && typeof props.minimumValue === 'number')[0];},
    press(label) {const target = this.findAll(props => typeof props.onPress === 'function' && props.accessibilityLabel === label)[0]; assert.ok(target, label); target.props.onPress();},
    text() {
      const visit = nodes => nodes.flatMap(node => typeof node === 'object' ? visit(node.children) : [String(node)]);
      return visit(this.tree).join('');
    },
  };
  return host.render();
}
const binding = '/ui/fields/entry/draft';
const fields = [
  ['text', {type: 'text', value: null}, 'A confirmed note', 'A confirmed note'],
  ['number', {type: 'number', value: null}, '0', 0],
  ['range', {type: 'number', value: null}, '0', 0],
  ['toggle', {type: 'boolean', value: null}, null, false],
  ['time', {type: 'instant', value: null, timeZone: 'UTC'}, '2026-11-03T10:00:00Z', '2026-11-03T10:00:00Z'],
];
const request = entry => ({version: 2, key: 'renderer', mode: 'card', title: '', summary: '', blocks: [entry], actions: []});
const block = (value, config, items = []) => ({id: 'entry', kind: 'input', title: 'Entry', text: '', value, config, items, image: '', url: ''});

for (const language of ['en', 'zh']) for (const [editor, value, input, expected] of fields) {
  test(`${language} ${editor} stays unset until explicit input, including false and zero`, () => {
    const config = {editor, ...(editor === 'range' ? {min: 0, max: 10} : {})}, source = request(block(value, config));
    const state = cardState(source), field = state.ui.fields.entry;
    const host = mount(Field, {title: 'Entry', draft: field.draft, value, config, binding}, field, language);
    assert.equal(field.draft, null);
    assert.deepEqual(host.writes, [], 'rendering must not confirm a value');
    if (editor === 'toggle') {
      host.press(language === 'zh' ? 'Entry · 否' : 'Entry · No');
    } else {
      assert.equal(host.input().props.value, '');
      assert.equal(host.input().props.accessibilityLabel, 'Entry');
      host.input().props.onChangeText(input);
    }
    assert.equal(field.draft, expected);
    assert.equal(source.blocks[0].value.value, null);
    host.render();
    if (editor === 'toggle') assert.equal(host.toggle().props.value, false);
    if (editor === 'range') assert.equal(host.range().props.value, 0);
    assert.equal(cardState(source, state.ui).ui.fields.entry.draft, expected);
  });
}

test('range explicitly chooses its minimum and writes later adjustments', () => {
  const field = {draft: null}, host = mount(Field, {draft: null, binding, config: {editor: 'range', min: 5, max: 20}}, field);
  assert.deepEqual(host.writes, []);
  host.press('Choose minimum 5'); host.render();
  assert.equal(field.draft, 5);
  host.range().props.onValueChange(10); host.render();
  assert.equal(field.draft, 10);
});

test('known toggles preserve review state through explicit changes', () => {
  const field = {draft: false, needsReview: true}, host = mount(Field, {draft: false, binding, config: {editor: 'toggle'}, value: {type: 'boolean', value: true}}, field);
  assert.equal(host.toggle().props.value, false);
  host.toggle().props.onValueChange(true); host.render();
  assert.equal(field.draft, true);
  assert.equal(field.needsReview, true);
  assert.match(host.text(), /Draft false is preserved|Draft true is preserved/);
});

test('number editing retains incomplete text without writing a fabricated number', () => {
  const field = {draft: 5}, host = mount(Field, {draft: 5, binding, config: {editor: 'number'}}, field);
  host.input().props.onChangeText('-'); host.render();
  assert.equal(field.draft, 5);
  assert.equal(host.input().props.value, '-');
  host.input().props.onBlur(); host.render();
  assert.equal(host.input().props.value, '5');
});

for (const value of [{type: 'duration', value: 60000}, {type: 'instant', value: '2026-11-03T10:00:00Z', timeZone: 'UTC'}]) {
  for (const emphasis of ['hero', 'inline']) test(`${value.type} ${emphasis} displays late item content and source links`, () => {
    const entry = {...block(value, {emphasis}), kind: 'metric'}, element = templateSpec(request(entry)).elements['block-entry'];
    const resolve = () => Object.fromEntries(Object.entries(element.props).map(([name, ref]) => [name, entry[ref.$state.split('/').at(-1)]]));
    const props = resolve(), host = mount(TimeDisplay, props);
    entry.items.push(
      {id: 'first', label: 'First identity', detail: 'First substantive detail', value: {type: 'number', value: 0}, url: 'https://example.com/first', image: '', icon: ''},
      {id: 'second', label: 'Second identity', detail: 'Second substantive detail', value: {type: 'boolean', value: false}, url: 'https://example.com/second', image: '', icon: ''},
    );
    Object.assign(props, resolve()); host.render();
    for (const item of entry.items) {
      assert.ok(host.text().includes(item.label));
      assert.ok(host.text().includes(item.detail));
      assert.ok(host.text().includes(formatUiValue(item.value)));
      host.press(item.label);
      assert.equal(runtime.links.at(-1), item.url);
    }
    assert.ok(host.text().includes(formatUiValue(value)));
  });
}

for (const [editor, unknown, input, expected] of fields) test(`${editor} retains concrete source values and draft updates`, () => {
  const initial = editor === 'toggle' ? true : editor === 'number' || editor === 'range' ? 5 : editor === 'time' ? '2026-11-02T10:00:00Z' : 'Original note';
  const value = {...unknown, value: initial}, config = {editor, ...(editor === 'range' ? {min: 0, max: 10} : {})};
  const source = request(block(value, config)), state = cardState(source), field = state.ui.fields.entry;
  const host = mount(Field, {title: 'Entry', draft: initial, binding, value, config}, field);
  assert.deepEqual(host.writes, []);
  if (editor === 'toggle') host.toggle().props.onValueChange(false);
  else {assert.equal(host.input().props.value, String(initial)); host.input().props.onChangeText(input);}
  assert.equal(field.draft, expected);
  assert.equal(source.blocks[0].value.value, initial);
  assert.equal(cardState(source, state.ui).ui.fields.entry.draft, expected);
});

test('choice IDs survive option reordering and unavailable selections require explicit clearing', () => {
  const selectionBinding = '/ui/collections/entry/selectedItemIds';
  const options = ['a', 'b'].map(id => ({id, label: id, detail: `${id} detail`, value: {type: 'number', value: 0}, image: '', icon: '', url: ''}));
  const props = {options, selection: ['b'], multi: true, binding: selectionBinding}, host = mount(ChoiceGroup, props);
  props.options = options.toReversed(); host.render();
  assert.deepEqual(host.writes, []);
  assert.equal(host.findAll(p => p.accessibilityLabel === 'b')[0].props.accessibilityState.checked, true);
  host.press('a'); host.render();
  assert.deepEqual(host.bound.get(selectionBinding), ['b', 'a']);
  props.options = [options[0]]; host.render();
  assert.match(host.text(), /no longer available/);
  host.press('Clear unavailable selections');
  assert.deepEqual(host.bound.get(selectionBinding), ['a']);
});

test('unknown counter writes only after an explicit quantity change', () => {
  const host = mount(Counter, {value: null, binding});
  assert.deepEqual(host.writes, []);
  assert.match(host.text(), /Not set/);
  host.press('Increase');
  assert.equal(host.field.draft, 1);
});

test('metric disclosure keeps expansion in the existing bound state', () => {
  const expansion = '/ui/views/details/expanded', host = mount(Metric, {title: 'Details', value: {type: 'number', value: 0}, config: {size: 'small_size'}, children: 'Retained detail', binding: expansion});
  assert.ok(!host.text().includes('Retained detail'));
  host.press('Details · Expand details'); host.render();
  assert.equal(host.bound.get(expansion), true);
  assert.ok(host.text().includes('Retained detail'));
});

test('shared value drawing preserves complete content and selection', () => {
  const host = mount(FeedValue, {text: '￥0.00 / false', selectable: true});
  assert.equal(host.text(), '￥0.00 / false');
  assert.ok(host.findAll(props => props.selectable === true).length > 0);
});

test('shared input preserves nonvisual keyboard behavior and bound text', () => {
  const host = mount(ControlledText, {value: '', binding, keyboardType: 'email-address'}, {draft: 'person@example.com'});
  assert.equal(host.input().props.keyboardType, 'email-address');
  host.input().props.onChangeText('new@example.com');
  assert.equal(host.field.draft, 'new@example.com');
});

test('progressive image ignores completion events from a replaced source', () => {
  let loads = 0, errors = 0;
  const props = {src: 'https://example.com/first.png', onLoad: () => loads++, onError: () => errors++}, host = mount(ProgressiveImage, props);
  const old = host.findAll(p => p.source?.uri === props.src)[0];
  props.src = 'https://example.com/second.png'; host.render();
  old.props.onLoad(); old.props.onError();
  assert.equal(loads, 0); assert.equal(errors, 0);
  host.findAll(p => p.source?.uri === props.src)[0].props.onLoad();
  assert.equal(loads, 1);
});

test('collection keeps rich source content, values and links in supplied order', () => {
  const items = [{id: 'z', label: 'More', detail: 'Full source explanation', value: {type: 'money', value: '1200', currency: 'USD', scale: 2}, image: '', icon: '', url: 'https://example.com/more'},
    {id: 'a', label: 'Less', detail: 'Another source explanation', value: {type: 'money', value: '900', currency: 'USD', scale: 2}, image: '', icon: '', url: 'https://example.com/less'}];
  const host = mount(Collection, {items, config: {layout: 'table'}});
  assert.ok(host.text().indexOf('More') < host.text().indexOf('Less'));
  for (const item of items) {assert.ok(host.text().includes(item.detail)); assert.ok(host.text().includes(formatUiValue(item.value))); host.press(item.label); assert.equal(runtime.links.at(-1), item.url);}
});

test('cleartext SVG fails without making a network request', async () => {
  const originalFetch = globalThis.fetch;
  let reads = 0, errors = 0;
  globalThis.fetch = async () => { reads++; throw new Error('must not fetch HTTP'); };
  try {
    const host = mount(ProgressiveImage, {src: 'http://example.com/rejected.svg', onError: () => errors++});
    await new Promise(resolve => setImmediate(resolve)); host.render();
    assert.equal(reads, 0); assert.equal(errors, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test('HTTPS SVG becomes visible after loading and remains available to another consumer', async () => {
  const originalFetch = globalThis.fetch;
  let finish, reads = 0, loads = 0, errors = 0;
  globalThis.fetch = () => {reads++; return new Promise(resolve => {finish = resolve;});};
  try {
    const src = 'https://images.test/shared.svg?revision=1#view';
    const host = mount(ProgressiveImage, {src, onLoad: () => loads++, onError: () => errors++});
    assert.equal(host.findAll(props => props.ast?.tag === 'svg').length, 0);
    assert.equal(errors, 0);
    finish({ok: true, headers: new Headers({"Content-Type": "image/svg+xml"}), text: async () => '<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>'});
    await new Promise(resolve => setImmediate(resolve));
    host.render();
    assert.equal(host.findAll(props => props.ast?.tag === 'svg').length, 1);
    assert.equal(loads, 1);
    assert.equal(errors, 0);
    const second = mount(ProgressiveImage, {src});
    assert.equal(second.findAll(props => props.ast?.tag === 'svg').length, 1);
    assert.equal(reads, 1);
  } finally {globalThis.fetch = originalFetch;}
});

test('failed SVG fetch settles image failure through the shared cached result', async () => {
  const originalFetch = globalThis.fetch;
  let finish, reads = 0, errors = 0;
  globalThis.fetch = () => {reads++; return new Promise(resolve => {finish = resolve;});};
  try {
    const host = mount(ProgressiveImage, {src: 'https://example.com/failed-renderer-image.svg', onError: () => errors++});
    host.render();
    assert.equal(reads, 1);
    assert.equal(errors, 0);
    finish({ok: false});
    await new Promise(resolve => setImmediate(resolve));
    host.render();
    assert.equal(errors, 1);
    host.render();
    assert.equal(reads, 1);
  } finally {globalThis.fetch = originalFetch;}
});

test('a successful response containing invalid SVG settles only as failure', async () => {
  const originalFetch = globalThis.fetch;
  let reads = 0, errors = 0, loads = 0;
  globalThis.fetch = async () => {reads++; return {ok: true, headers: new Headers({"Content-Type": "image/svg+xml"}), text: async () => '<html>Not an SVG image</html>'};};
  try {
    const host = mount(ProgressiveImage, {src: 'https://example.com/invalid-renderer-image.svg', onError: () => errors++, onLoad: () => loads++});
    await new Promise(resolve => setImmediate(resolve));
    host.render();
    assert.equal(errors, 1);
    assert.equal(loads, 0);
    host.render();
    assert.equal(errors, 1);
    assert.equal(loads, 0);
    assert.equal(reads, 1);
  } finally {globalThis.fetch = originalFetch;}
});

test('money preserves exact minor units when native Intl rejects BigInt', () => {
  const NumberFormat = Intl.NumberFormat;
  const locales = ['en-US', 'zh-CN', 'de-DE', 'es-ES', 'hi-IN', 'ar-EG', 'fa-IR', 'en-US-u-nu-mathbold'];
  const whole = '1234567890'.repeat(20);
  const expected = locales.map(locale => new NumberFormat(locale, {style: 'currency', currency: 'USD', minimumFractionDigits: 0, maximumFractionDigits: 0}).format(BigInt(whole)));
  Intl.NumberFormat = class extends NumberFormat {
    get format() {
      const format = super.format;
      return value => {assert.ok(typeof value === 'number' && Number.isSafeInteger(value), 'native Intl receives only safe integers'); return format(value);};
    }
    formatToParts(value) {
      assert.ok(typeof value === 'number' && Number.isSafeInteger(value), 'native Intl parts receive only safe integers');
      return super.formatToParts(value);
    }
  };
  try {
    assert.equal(formatUiValue({type: 'money', value: '12345678901234567890123456789012345678', currency: 'USD', scale: 2}, 'en-US'), '$123,456,789,012,345,678,901,234,567,890,123,456.78');
    assert.equal(formatUiValue({type: 'money', value: '-5', currency: 'USD', scale: 2}, 'en-US'), '-$0.05');
    assert.equal(formatUiValue({type: 'money', value: '0', currency: 'USD', scale: 2}, 'en-US'), '$0.00');
    assert.equal(formatUiValue({type: 'money', value: '123400', currency: 'EUR', scale: 2}, 'es-ES'), new NumberFormat('es-ES', {style: 'currency', currency: 'EUR'}).format(1234));
    const value = {type: 'money', value: '12345678901234567890123456789012345678', currency: 'USD', scale: 2};
    const exact = '$123,456,789,012,345,678,901,234,567,890,123,456.78';
    assert.ok(mount(Metric, {title: 'Exact amount', value}).text().includes(exact));
    assert.ok(mount(Collection, {items: [{id: 'exact-price', label: 'Known price', value, detail: 'Complete source price', image: '', url: '', icon: ''}]}).text().includes(exact));
    for (const [index, locale] of locales.entries()) assert.equal(formatUiValue({type: 'money', value: whole, currency: 'USD', scale: 0}, locale), expected[index], locale);
  } finally {Intl.NumberFormat = NumberFormat;}
});
