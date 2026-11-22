// SubagentCards (components/SubagentCards.tsx) rendered against minimal React / React Native stand-ins.
import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';
import {existsSync, readFileSync} from 'node:fs';
import ts from 'typescript';

const runtime = {host: null, layout: 0};
globalThis[Symbol.for('taku.subagent-card-test')] = runtime;
const access = "const runtime = globalThis[Symbol.for('taku.subagent-card-test')];";
const moduleUrl = source => `data:text/javascript,${encodeURIComponent(`${access}\n${source}`)}`;
const react = moduleUrl(`
  export const createElement = (type, props, ...children) => ({type, props: {...props, ...(children.length ? {children: children.length === 1 ? children[0] : children} : {})}});
  export const useState = initial => runtime.host.state(initial);
  export const useRef = initial => runtime.host.state(() => ({current: initial}))[0];
  export const useEffect = () => {};
  export const useSyncExternalStore = (_s, snapshot) => snapshot();
  export default {createElement, Fragment: 'Fragment', useState, useRef, useEffect, useSyncExternalStore};
`);
const native = moduleUrl(`
  class Value { constructor(v) { this.v = v; } interpolate() { return this; } }
  const animation = {start() {}, stop() {}};
  export const Animated = {Value, View: 'Animated.View', timing: () => animation, loop: () => animation, sequence: () => animation};
  export const Easing = {out: f => f, in: f => f, quad: 'quad'};
  export const LayoutAnimation = {configureNext: () => { runtime.layout++; }};
  export const View = 'View', Text = 'Text', Pressable = 'Pressable';
  export const Platform = {OS: 'android'};
  export const StyleSheet = {create: value => value, absoluteFill: {}, hairlineWidth: 1};
`);
const root = new URL('../', import.meta.url).href;
const mocks = new Map([['react', react], ['react-native', native],
  ['react-native-svg', moduleUrl("export default 'Svg'; export const Path = 'Path', Rect = 'Rect';")]]);
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
const {SubagentCard, SubagentGroup} = await import('../components/SubagentCards.tsx');

function mount(Component, props) {
  const host = {slots: new Map(), path: '', index: 0, tree: [],
    state(initial) {
      const key = `${this.path}:${this.index++}`;
      if (!this.slots.has(key)) this.slots.set(key, typeof initial === 'function' ? initial() : initial);
      return [this.slots.get(key), next => this.slots.set(key, typeof next === 'function' ? next(this.slots.get(key)) : next)];
    },
    render() {
      runtime.host = this;
      const visit = (node, path) => {
        if (node == null || typeof node === 'boolean') return [];
        if (Array.isArray(node)) return node.flatMap((child, index) => visit(child, `${path}.${index}`));
        if (typeof node !== 'object') return [node];
        if (typeof node.type === 'function') { this.path = path; this.index = 0; return visit(node.type(node.props), `${path}.r`); }
        return [{type: node.type, props: node.props, children: visit(node.props.children, `${path}.c`)}];
      };
      this.tree = visit({type: Component, props}, 'root');
      return this;
    },
    findAll(predicate) {
      const visit = nodes => nodes.flatMap(node => typeof node === 'object' ? [...(predicate(node) ? [node] : []), ...visit(node.children)] : []);
      return visit(this.tree);
    },
    byId(id) { return this.findAll(node => node.props.testID === id)[0]; },
    text(nodes = this.tree) {
      return nodes.flatMap(node => typeof node === 'object' ? [this.text(node.children)] : [String(node)]).join('|');
    },
  };
  return host.render();
}

const task = (over = {}) => ({id: 'turn/subagent-1', title: 'Device Status Inspector', type: 'self', model: 'Gemini 4 Neon', source: 'local',
  status: 'running', startedAt: Date.now() - 18_000, completedAt: 0, elapsedMs: 0, goal: 'Check battery and Android version',
  activity: '读取设备信息', summary: '', report: '', ...over});

test('a running card: title, status capsule with a live clock, badges, current step; nothing to leak', () => {
  const card = mount(SubagentCard, {task: task(), language: 'zh'});
  const text = card.text();
  assert.match(text, /Device Status Inspector/);
  assert.match(text, /运行中/);
  assert.match(text, /1[89]s/, 'the clock counts from startedAt');
  assert.match(text, /Gemini 4 Neon/);
  assert.match(text, /本地子代理/);
  assert.doesNotMatch(text, /self/, 'the default "self" type is not shown as a badge');
  assert.match(text, /读取设备信息/);
  assert.doesNotMatch(text, /Check battery/, 'details stay folded');
  assert.equal(card.byId('subagent-card-turn/subagent-1').props.accessibilityState.expanded, false);
});

test('tapping a finished card opens its goal and report in place, with a layout animation', () => {
  const done = task({status: 'completed', completedAt: 2_000, startedAt: 1_000, elapsedMs: 18_000, activity: '',
    summary: 'Android 17, battery 74%', report: '### Device\n- Android 17\n- Battery 74%'});
  const card = mount(SubagentCard, {task: done, language: 'zh', renderMarkdown: text => ({type: 'Markdown', props: {children: `md:${text}`}})});
  let text = card.text();
  assert.match(text, /已完成/);
  assert.match(text, /18s/);
  assert.match(text, /Android 17, battery 74%/);
  const before = runtime.layout;
  card.byId('subagent-card-turn/subagent-1').props.onPress();
  card.render();
  text = card.text();
  assert.equal(runtime.layout, before + 1);
  assert.equal(card.byId('subagent-card-turn/subagent-1').props.accessibilityState.expanded, true);
  assert.match(text, /任务目标/);
  assert.match(text, /Check battery and Android version/);
  assert.match(text, /回报摘要/);
  assert.match(text, /md:### Device/, 'the report renders as Markdown');
});

test('failed and interrupted cards say so', () => {
  assert.match(mount(SubagentCard, {task: task({status: 'failed', completedAt: Date.now(), activity: ''}), language: 'en'}).text(), /Failed/);
  const stopped = mount(SubagentCard, {task: task({status: 'stopped', completedAt: Date.now(), activity: ''}), language: 'zh'}).text();
  assert.match(stopped, /已中断/);
  assert.match(stopped, /子任务未回报结果/);
});

test('several cards stack under a header that counts them; one card has no header', () => {
  const now = Date.now();
  const running = [task(), task({id: 'turn/subagent-2', title: 'Taku App Scanner'})];
  const group = mount(SubagentGroup, {tasks: running, language: 'zh'});
  assert.match(group.text(), /正在并行推进 2 项子任务/);
  assert.equal(group.findAll(node => String(node.props.testID).startsWith('subagent-card-')).length, 2);
  const finished = running.map((t, i) => ({...t, status: 'completed', activity: '', startedAt: now - 30_000, completedAt: now - 30_000 + (18 + i * 6) * 1000, elapsedMs: (18 + i * 6) * 1000}));
  const done = mount(SubagentGroup, {tasks: finished, language: 'zh', openIds: ['turn/subagent-2']});
  assert.match(done.text(), /2 个子任务 · 全部完成/);
  assert.match(done.text(), /24s/, 'the header spans first start to last end');
  assert.equal(done.byId('subagent-card-turn/subagent-2').props.accessibilityState.expanded, true);
  const single = mount(SubagentGroup, {tasks: [task()], language: 'zh'});
  assert.doesNotMatch(single.text(), /子任务 ·|并行推进/);
});
