import assert from 'node:assert/strict';
import test from 'node:test';
import {buildCarMesh, CAR, frame, INITIAL_YAW, renderCar, section, shadowPath, spinStep, wrapAngle, yawForDrag} from './carModel.ts';

const mesh = buildCarMesh();
const stage = {width: 371, height: 160};
const coords = d => [...d.matchAll(/[ML](-?[\d.]+) (-?[\d.]+)/g)].map(m => ({x: Number(m[1]), y: Number(m[2])}));

test('car mesh: a closed lofted body plus four wheels, all normals unit length', () => {
  assert.ok(mesh.length > 300 && mesh.length < 900, `faces ${mesh.length}`);
  for (const face of mesh) assert.ok(Math.abs(Math.hypot(face.normal.x, face.normal.y, face.normal.z) - 1) < 1e-6);
  const kinds = new Set(mesh.map(f => f.material));
  for (const material of ['paint', 'glass', 'tire', 'rim', 'light', 'tail']) assert.ok(kinds.has(material), material);
  // Proportions of a crossover: roof at full height mid-cabin, a low nose, wheel arches cut into the sill.
  assert.ok(Math.abs(section(0.45).roof - CAR.height) < 0.05);
  assert.ok(section(0.99).belt < 0.75 && section(0.5).bottom < 0.25);
  assert.ok(section(0.5 + (CAR.wheelBase / 2 + 0.05) / CAR.length).bottom > CAR.wheelRadius * 1.6, 'arch over the front wheel');
});

test('render: back faces culled, far → near order, every yaw fits the stage', () => {
  for (let deg = 0; deg < 360; deg += 15) {
    const faces = renderCar(mesh, {...stage, yaw: (deg * Math.PI) / 180});
    assert.ok(faces.length > 0.25 * mesh.length && faces.length < 0.75 * mesh.length, `${deg}°: ${faces.length} visible`);
    for (let i = 1; i < faces.length; i++) assert.ok(faces[i - 1].depth <= faces[i].depth);
    for (const face of faces) for (const p of coords(face.d)) {
      assert.ok(p.x >= 0 && p.x <= stage.width && p.y >= 0 && p.y <= stage.height, `${deg}°: point ${p.x},${p.y} off stage`);
    }
    for (const face of faces) assert.match(face.fill, /^rgb\(\d+,\d+,\d+\)$/);
    assert.ok(coords(shadowPath({...stage, yaw: (deg * Math.PI) / 180}, 0.92)).every(p => p.y <= stage.height && p.y > frame(stage).groundY - 70));
  }
});

test('render: the initial view is a front three-quarter (nose and driver side visible), and turning changes it', () => {
  const start = renderCar(mesh, {...stage, yaw: INITIAL_YAW});
  assert.ok(start.some(f => f.material === 'light'), 'headlights in view');
  assert.ok(!start.some(f => f.material === 'tail'), 'tail lights hidden');
  const back = renderCar(mesh, {...stage, yaw: INITIAL_YAW + Math.PI});
  assert.ok(back.some(f => f.material === 'tail') && !back.some(f => f.material === 'light'), 'half a turn shows the tail');
  assert.notDeepEqual(start.map(f => f.d), renderCar(mesh, {...stage, yaw: INITIAL_YAW + 0.3}).map(f => f.d));
  assert.deepEqual(start, renderCar(mesh, {...stage, yaw: INITIAL_YAW}), 'deterministic');
});

test('drag and fling: a stage-width swipe is half a turn; inertia decays to rest', () => {
  assert.ok(Math.abs(yawForDrag(0, 371, 371) - Math.PI) < 1e-9);
  assert.ok(Math.abs(yawForDrag(0, -185.5, 371) + Math.PI / 2) < 1e-9);
  assert.ok(Math.abs(wrapAngle(3 * Math.PI) - Math.PI) < 1e-9 && Math.abs(wrapAngle(-Math.PI / 2) + Math.PI / 2) < 1e-9);
  let state = {yaw: 0, velocity: 0.01}, steps = 0;
  while (state.velocity && steps < 1000) { state = spinStep(state.yaw, state.velocity, 16); steps++; }
  assert.ok(steps > 10 && steps < 200, `settles in ${steps} frames`);
  assert.ok(Math.abs(state.yaw) > 0.5, 'the fling turned the car');
});
