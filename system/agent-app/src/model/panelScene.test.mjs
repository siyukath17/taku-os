import test from 'node:test';
import assert from 'node:assert/strict';
import {fieldVariant, panelAccent, panelScene} from './panelScene.ts';

const value = (type, v, unit) => ({type, value: v, ...(unit ? {unit} : {})});
const block = (id, kind, extra = {}) => ({id, kind, title: '', text: '', value: value('text', null), image: '', url: '', items: [], config: {}, ...extra});
const item = (id, icon, v = value('text', null), image = '') => ({id, label: id, detail: '', value: v, url: '', image, icon});
const request = blocks => ({version: 2, key: 'k', mode: 'card', title: 't', summary: '', blocks, actions: []});

test('living room evening is a control scene in amber', () => {
  const r = request([
    block('ceiling', 'input', {value: value('boolean', false), config: {editor: 'toggle', icon: 'lamp-ceiling'}}),
    block('floor', 'input', {value: value('number', 50, '%'), config: {editor: 'range', min: 0, max: 100, icon: 'lamp-floor'}}),
    block('ac', 'input', {value: value('number', 24, '°C'), config: {editor: 'range', min: 16, max: 30, icon: 'thermometer'}}),
    block('curtains', 'input', {text: '拉上|拉开', value: value('boolean', true), config: {editor: 'toggle', icon: 'blinds'}}),
    block('music', 'input', {value: value('boolean', true), config: {editor: 'toggle', icon: 'music'}}),
  ]);
  assert.equal(panelScene(r), 'control');
  assert.equal(panelAccent(r), 'amber');
  const [ceiling, floor, ac, curtains, music] = r.blocks.map(b => fieldVariant(b.config, b.value, b.text));
  assert.deepEqual([ceiling, floor, ac, curtains, music], ['switch', 'dimmer', 'stepper', 'segment', 'player']);
});

test('a menu with prices is a pick scene in terracotta', () => {
  const money = n => ({type: 'number', value: n, unit: '$'});
  const r = request([block('picks', 'choices', {config: {multi: true, emphasis: 'hero'}, items: [item('set', 'utensils', money(44)), item('soup', 'soup', money(19))]})]);
  assert.equal(panelScene(r), 'pick');
  assert.equal(panelAccent(r), 'terracotta');
});

test('a day plan with a packing list is a plan scene; carried objects do not vote', () => {
  const r = request([
    block('day', 'timeline', {items: [item('out', 'footprints'), item('park', 'trees'), item('coffee', 'coffee')]}),
    block('pack', 'choices', {config: {multi: true}, items: [item('phone', 'smartphone'), item('battery', 'battery-charging'), item('voucher', 'file-text')]}),
  ]);
  assert.equal(panelScene(r), 'plan');
  assert.equal(panelAccent(r), 'moss');
});

test('no themed icon falls back to graphite', () => {
  assert.equal(panelAccent(request([block('a', 'text', {text: 'x'})])), 'graphite');
  assert.equal(panelScene(request([block('a', 'text', {text: 'x'})])), 'answer');
});

test('on and off words stay a switch', () => {
  assert.equal(fieldVariant({editor: 'toggle', icon: 'lamp-ceiling'}, {type: 'boolean', value: false}, '开|关'), 'switch');
  assert.equal(fieldVariant({editor: 'toggle', icon: 'blinds'}, {type: 'boolean', value: true}, '拉上|拉开'), 'segment');
});
