import assert from 'node:assert/strict';
import test from 'node:test';
import {carChips, carReducer, demoDispatchNote, initialCar, initialTv, tvChips, tvReducer, tvScreen, TV_VOLUME} from './deviceCards.ts';

test('car card: Climate, Unlock and Frunk toggle independently with their labels', () => {
  assert.deepEqual(carChips(initialCar, 'en').map(c => [c.label, c.active]), [['Climate 22°C', false], ['Unlock', false], ['Frunk', false]]);
  let state = carReducer(initialCar, 'lock');
  state = carReducer(state, 'frunk');
  assert.deepEqual(carChips(state, 'en').map(c => [c.label, c.active]), [['Climate 22°C', false], ['Unlocked', true], ['Frunk open', true]]);
  state = carReducer(carReducer(state, 'climate'), 'lock');
  assert.deepEqual(carChips(state, 'en').map(c => [c.id, c.active]), [['climate', true], ['lock', false], ['frunk', true]]);
  assert.equal(carChips(state, 'zh')[2].label, '前备箱已开');
});

test('TV card: play/pause, stepping volume that wraps, cast; the screen follows', () => {
  assert.deepEqual(tvChips(initialTv, 'en').map(c => c.label), ['Play / Pause', 'Volume', 'Cast']);
  assert.deepEqual(tvScreen(initialTv, 'en'), {title: 'Ambient Mode', caption: 'Living Room', badge: '4K HDR', progress: false});
  const playing = tvReducer(initialTv, 'play');
  assert.equal(tvChips(playing, 'en')[0].active, true);
  assert.equal(tvScreen(playing, 'en').title, 'Now playing');
  assert.equal(tvScreen(playing, 'en').progress, true);
  const louder = tvReducer(playing, 'volume');
  assert.equal(louder.volume, initialTv.volume + TV_VOLUME.step);
  assert.equal(tvChips(louder, 'en')[1].label, `Volume ${louder.volume}`);
  assert.equal(tvReducer({...louder, volume: TV_VOLUME.max}, 'volume').volume, TV_VOLUME.min, 'wraps after the maximum');
  const casting = tvReducer(louder, 'cast');
  assert.equal(tvChips(casting, 'en')[2].label, 'Casting');
  assert.equal(tvScreen(casting, 'en').title, 'Casting from Taku Phone');
  assert.equal(tvScreen(tvReducer(casting, 'play'), 'en').caption, 'Living Room · Paused');
});

test('demo dispatch says it is a preview, in both languages', () => {
  assert.match(demoDispatchNote('en'), /Demo device/);
  assert.match(demoDispatchNote('zh'), /演示设备/);
});
