import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {carChips, carReducer, demoDispatchNote, initialCar, initialTv, tvChips, tvReducer, tvStatus, TV_VOLUME} from './deviceCards.ts';

test('car card: Climate, Unlock and Frunk toggle independently with their labels', () => {
  assert.deepEqual(carChips(initialCar, 'en').map(c => [c.label, c.active]), [['Climate 22°C', false], ['Unlock', false], ['Frunk', false]]);
  let state = carReducer(initialCar, 'lock');
  state = carReducer(state, 'frunk');
  assert.deepEqual(carChips(state, 'en').map(c => [c.label, c.active]), [['Climate 22°C', false], ['Unlocked', true], ['Frunk open', true]]);
  state = carReducer(carReducer(state, 'climate'), 'lock');
  assert.deepEqual(carChips(state, 'en').map(c => [c.id, c.active]), [['climate', true], ['lock', false], ['frunk', true]]);
  assert.equal(carChips(state, 'zh')[2].label, '前备箱已开');
});

test('TV card: play/pause, stepping volume that wraps, cast; the spoken status follows', () => {
  assert.deepEqual(tvChips(initialTv, 'en').map(c => c.label), ['Play / Pause', 'Volume', 'Cast']);
  assert.deepEqual(tvStatus(initialTv, 'en'), {title: 'Ambient Mode', caption: 'Living Room'});
  const playing = tvReducer(initialTv, 'play');
  assert.equal(tvChips(playing, 'en')[0].active, true);
  assert.equal(tvStatus(playing, 'en').title, 'Now playing');
  const louder = tvReducer(playing, 'volume');
  assert.equal(louder.volume, initialTv.volume + TV_VOLUME.step);
  assert.equal(tvChips(louder, 'en')[1].label, `Volume ${louder.volume}`);
  assert.equal(tvReducer({...louder, volume: TV_VOLUME.max}, 'volume').volume, TV_VOLUME.min, 'wraps after the maximum');
  const casting = tvReducer(louder, 'cast');
  assert.equal(tvChips(casting, 'en')[2].label, 'Casting');
  assert.equal(tvStatus(casting, 'en').title, 'Casting from Taku Phone');
  assert.equal(tvStatus(tvReducer(casting, 'play'), 'en').caption, 'Living Room · Paused');
});

test('demo dispatch says it is a preview, in both languages', () => {
  assert.match(demoDispatchNote('en'), /Demo device/);
  assert.match(demoDispatchNote('zh'), /演示设备/);
});

test('device card photos ship as small white-background JPEGs next to the card', () => {
  for (const name of ['tesla-model-y', 'xiaomi-tv']) {
    const bytes = readFileSync(new URL(`../../assets/connect/${name}.jpg`, import.meta.url));
    assert.deepEqual([...bytes.subarray(0, 3)], [0xff, 0xd8, 0xff], `${name} is a JPEG`);
    assert.ok(bytes.length < 200_000, `${name} stays under 200 KB`);
  }
  const card = readFileSync(new URL('../components/connect/DeviceCards.tsx', import.meta.url), 'utf8');
  assert.match(card, /require\('\.\.\/\.\.\/\.\.\/assets\/connect\/tesla-model-y\.jpg'\)/);
  assert.match(card, /require\('\.\.\/\.\.\/\.\.\/assets\/connect\/xiaomi-tv\.jpg'\)/);
});
