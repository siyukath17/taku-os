import test from 'node:test';
import assert from 'node:assert/strict';
import {checklistFields, checklistProgress, panelContentSpec} from './checklist.ts';
import {ensureCard, mergeCardView, rendererStore} from './genuiStore.ts';
import {cardState} from './genui.ts';

const ids = ['room-card', 'phone', 'wallet', 'power-bank'];
function fixture(key = 'leaving', order = ids) {
  const request = {version: 2, key, mode: 'card', title: '出门检查', summary: '', actions: [],
    blocks: order.map(id => ({id, kind: 'input', title: id, text: '', image: '', url: '', items: [],
      value: {type: 'boolean', value: false}, config: {editor: 'toggle'}}))};
  const spec = {root: 'root', elements: {root: {type: 'Card', props: {title: {$state: '/data/title'}}, children: order.map(id => `${id}-row`)},
    ...Object.fromEntries(order.map(id => [`${id}-row`, {type: 'Checkbox', props: {
      label: {$state: `/data/blocksById/${id}/title`}, checked: {$bindState: `/ui/fields/${id}/draft`}}, children: []}]))}};
  return {request, spec};
}

test('only a complete flat bound checkbox list opts in; custom and mixed layouts retain their presentation', () => {
  const {request, spec} = fixture();
  assert.deepEqual(checklistFields(request, spec), ids);
  assert.equal(checklistFields({...request, blocks: []}, spec), null);
  assert.equal(checklistFields({...request, actions: [{id: 'submit'}]}, spec), null);
  assert.equal(checklistFields({...request, blocks: [...request.blocks, {...request.blocks[0], id: 'notes', kind: 'text'}]}, spec), null);
  for (const patch of [{type: 'Switch'}, {props: {checked: true}}, {props: {checked: {$bindState: '/ui/fields/other/draft'}}}, {on: {change: {action: 'reply'}}}]) {
    assert.equal(checklistFields(request, {...spec, elements: {...spec.elements, 'phone-row': {...spec.elements['phone-row'], ...patch}}}), null);
  }
  const partial = {...spec, elements: {...spec.elements, root: {...spec.elements.root, children: ['room-card-row']}}};
  assert.equal(checklistFields(request, partial), null);
});

test('count follows shared editable state, including native hydration, local taps, all-done and undo', () => {
  const {request, spec} = fixture('live-progress');
  const id = ensureCard('checklist-tests', {kind: 'matter', request, sourceId: 'source'});
  const store = rendererStore(id), fields = checklistFields(request, spec);
  const read = () => checklistProgress(fields, store.get('/ui/fields'));
  assert.deepEqual(read(), {completed: 0, total: 4, unknown: 0, allDone: false});
  const persisted = cardState(request).ui;
  persisted.fields['room-card'].draft = true;
  mergeCardView(id, {matterId: id, uiRevision: 1, ui: persisted});
  assert.equal(read().completed, 1);
  let notifications = 0;
  const unsubscribe = store.subscribe(() => {notifications++;});
  for (const item of fields) store.set(`/ui/fields/${item}/draft`, true);
  assert.deepEqual(read(), {completed: 4, total: 4, unknown: 0, allDone: true});
  store.set('/ui/fields/phone/draft', false);
  assert.equal(read().completed, 3);
  assert.equal(read().allDone, false);
  assert.ok(notifications > 0, 'the same store notifies the counter and its controls');
  unsubscribe();
  // Original semantic booleans remain false: the count must not read these stale defaults.
  assert.ok(request.blocks.every(block => block.value.value === false));
});

test('a published reordered revision counts only current IDs and restores the canonical checked item', () => {
  const order = ['passport', ...ids], {request, spec} = fixture('published-revision', order);
  const id = ensureCard('checklist-tests', {kind: 'matter', request, sourceId: 'new-source'});
  const persisted = cardState(request).ui;
  persisted.fields['room-card'].draft = true;
  persisted.fields['removed-item'] = {draft: true};
  mergeCardView(id, {matterId: id, uiRevision: 3, ui: persisted});
  assert.deepEqual(checklistFields(request, spec), order);
  assert.deepEqual(checklistProgress(order, rendererStore(id).get('/ui/fields')), {completed: 1, total: 5, unknown: 0, allDone: false});
});

test('unknown, absent and review-needed values never become checked or all-done', () => {
  assert.deepEqual(checklistProgress(['a', 'b', 'c', 'd'], {a: {draft: true}, b: {draft: null}, c: {draft: true, needsReview: true}}),
    {completed: 1, total: 4, unknown: 3, allDone: false});
  assert.equal(checklistProgress([], {}).allDone, false);
});

test('Panel title deduplication is a local root-only projection, preserving distinct titles and semantic data', () => {
  const {request, spec} = fixture();
  const result = panelContentSpec(spec, request.title, ' 出门检查 ');
  assert.equal(result.elements.root.props.title, undefined);
  assert.deepEqual(spec.elements.root.props.title, {$state: '/data/title'});
  assert.equal(request.title, '出门检查');
  assert.equal(panelContentSpec(spec, request.title), spec);
  assert.equal(panelContentSpec(spec, request.title, '旅行准备'), spec);
  assert.equal(result.elements['phone-row'], spec.elements['phone-row']);
});
