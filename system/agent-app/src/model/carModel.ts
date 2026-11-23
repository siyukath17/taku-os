/**
 * A small offline 3D renderer for the Taku Connect car card: a procedural pearl-white crossover
 * (lofted body, glass greenhouse, wheels with arches), projected with a fixed studio camera and
 * flat-shaded faces sorted back to front. It draws with plain SVG paths, so it needs no WebView,
 * GL context or network and renders the same on every Android WebView/GPU combination.
 *
 * Model space: x along the car (front +x), y up (ground at 0), z across (left side +z), metres.
 */

export interface Vec3 {x: number; y: number; z: number}
export type Material = 'paint' | 'glass' | 'trim' | 'tire' | 'rim' | 'hub' | 'light' | 'tail';
export interface Face {points: Vec3[]; normal: Vec3; material: Material; bias: number}
export interface ScreenFace {d: string; fill: string; depth: number; material: Material}

export const CAR = {length: 4.75, width: 1.92, height: 1.62, wheelRadius: 0.37, wheelWidth: 0.26, wheelBase: 2.9} as const;

const sub = (a: Vec3, b: Vec3): Vec3 => ({x: a.x - b.x, y: a.y - b.y, z: a.z - b.z});
const dot = (a: Vec3, b: Vec3) => a.x * b.x + a.y * b.y + a.z * b.z;
const norm = (a: Vec3): Vec3 => { const l = Math.hypot(a.x, a.y, a.z) || 1; return {x: a.x / l, y: a.y / l, z: a.z / l}; };
const centroid = (points: Vec3[]): Vec3 => {
  const c = {x: 0, y: 0, z: 0};
  for (const p of points) { c.x += p.x; c.y += p.y; c.z += p.z; }
  return {x: c.x / points.length, y: c.y / points.length, z: c.z / points.length};
};
const smooth = (t: number) => { const k = Math.min(1, Math.max(0, t)); return k * k * (3 - 2 * k); };
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Newell normal of a (possibly non-planar) polygon, turned to point away from [inside]. */
function faceNormal(points: Vec3[], inside: Vec3): Vec3 {
  const n = {x: 0, y: 0, z: 0};
  for (let i = 0; i < points.length; i++) {
    const a = points[i], b = points[(i + 1) % points.length];
    n.x += (a.y - b.y) * (a.z + b.z); n.y += (a.z - b.z) * (a.x + b.x); n.z += (a.x - b.x) * (a.y + b.y);
  }
  const out = norm(n);
  return dot(out, sub(centroid(points), inside)) < 0 ? {x: -out.x, y: -out.y, z: -out.z} : out;
}

const wheelXs = [-CAR.wheelBase / 2 + 0.05, CAR.wheelBase / 2 + 0.05];

/** Body cross-section at u ∈ [0, 1] (0 = tail, 1 = nose): heights and half width. */
export function section(u: number) {
  const x = (u - 0.5) * CAR.length;
  const end = Math.abs(2 * u - 1);
  // Plan view: full width through the cabin, rounded nose and tail (the nose a little rounder).
  const taper = u > 0.5 ? Math.pow(end, 5) : Math.pow(end, 8);
  const halfWidth = (CAR.width / 2) * Math.pow(Math.max(0, 1 - taper), 1 / 4);
  let bottom = 0.2 + 0.18 * Math.pow(Math.max(0, (end - 0.74) / 0.26), 2);
  for (const wx of wheelXs) {
    const dx = x - wx, r = CAR.wheelRadius + 0.07;
    if (Math.abs(dx) < r) bottom = Math.max(bottom, CAR.wheelRadius + Math.sqrt(r * r - dx * dx) * 0.95);
  }
  // Beltline: rises gently toward the tail; the nose rolls down into a short rounded front.
  let belt = 0.92 + 0.06 * (1 - u);
  if (u > 0.84) belt -= 0.3 * Math.pow((u - 0.84) / 0.16, 1.8);
  if (u < 0.06) belt -= 0.12 * Math.pow((0.06 - u) / 0.06, 2);
  // Roof: short hood, steeply raked windshield, long glass roof, sloping fastback.
  const hood = (v: number) => 0.92 + 0.06 * (1 - v) + 0.04;
  let roof: number;
  if (u >= 0.72) roof = belt + 0.04 * (1 - smooth((u - 0.72) / 0.28));
  else if (u >= 0.5) roof = lerp(CAR.height, hood(0.72), smooth((u - 0.5) / 0.22));
  else roof = lerp(1.1, CAR.height, Math.pow(Math.sin((Math.PI / 2) * Math.min(1, u / 0.5)), 0.75)); // one fastback arc
  roof = Math.max(roof, belt + 0.01);
  return {x, halfWidth, bottom, belt, roof};
}

/**
 * One side of a section ring, bottom → roof centre-line edge: rocker, a softly rounded flank up to
 * the shoulder, then the glass greenhouse leaning in (tumblehome) and the curved roof edge.
 */
function ringSide(u: number): {z: number; y: number}[] {
  const {halfWidth: w, bottom: b, belt: s, roof: r} = section(u);
  const green = r - s, lean = Math.min(1, green / 0.55);
  const flank = Array.from({length: 5}, (_, i) => {
    const t = i / 4; // 0 = sill, 1 = shoulder
    const bulge = Math.sin(Math.PI * (0.25 + 0.6 * t));
    return {z: w * (0.9 + 0.1 * bulge), y: lerp(b + 0.04, s - 0.03, t)};
  });
  const glass = Array.from({length: 4}, (_, i) => {
    const t = (i + 1) / 4;
    const arc = Math.sin((Math.PI / 2) * t);
    return {z: w * (0.92 - lean * (0.14 * t + 0.2 * (1 - Math.cos((Math.PI / 2) * t)))), y: s + green * arc};
  });
  return [{z: 0.8 * w, y: b}, ...flank, {z: 0.93 * w, y: s}, ...glass];
}

const GLASS_FROM = 7; // ring-side index of the beltline point

/** Builds the car's faces (a few hundred) in model space. */
export function buildCarMesh(sections = 32): Face[] {
  const faces: Face[] = [];
  const us: number[] = [];
  for (let i = 0; i <= sections; i++) us.push(0.004 + (0.992 * i) / sections);
  const rings = us.map(u => {
    const {x} = section(u);
    const side = ringSide(u);
    const right = side.map(p => ({x, y: p.y, z: p.z}));
    const left = side.slice().reverse().map(p => ({x, y: p.y, z: -p.z}));
    return [...right, ...left];
  });
  const n = rings[0].length;
  const axis = (x: number): Vec3 => ({x, y: 0.75, z: 0});
  const isGlassSegment = (k: number) => k >= GLASS_FROM && k < n - 1 - GLASS_FROM;
  const greenhouse = (u: number) => { const s = section(u); return s.roof - s.belt; };
  for (let i = 0; i < sections; i++) {
    const a = rings[i], b = rings[i + 1];
    const glassy = greenhouse(us[i]) > 0.12 && greenhouse(us[i + 1]) > 0.12;
    const x = (a[0].x + b[0].x) / 2;
    for (let k = 0; k < n; k++) {
      const k2 = (k + 1) % n;
      const points = [a[k], a[k2], b[k2], b[k]];
      let material: Material = 'paint';
      if (glassy && isGlassSegment(k)) material = 'glass';
      else if (k === n - 1 || k === 0 || k === n - 2) material = 'trim'; // underbody and sill cladding
      const strip = k === 4 || k === n - 6; // a band just below the shoulder
      if (i >= sections - 1 && strip) material = 'light';
      if (i === 0 && (k === 4 || k === 5 || k === n - 6 || k === n - 7)) material = 'tail';
      faces.push({points, normal: faceNormal(points, axis(x)), material, bias: 0});
    }
  }
  // End caps.
  for (const [ring, inward] of [[rings[0], 0.3], [rings[sections], -0.3]] as const) {
    const points = ring.slice();
    const inside = {x: ring[0].x + inward, y: 0.7, z: 0};
    faces.push({points, normal: faceNormal(points, inside), material: 'paint', bias: 0});
  }
  // Wheels.
  const segs = 18;
  for (const wx of wheelXs) for (const side of [1, -1]) {
    const zOut = side * (CAR.width / 2 - 0.03 + 0.02), zIn = zOut - side * CAR.wheelWidth;
    const centre = (z: number): Vec3 => ({x: wx, y: CAR.wheelRadius, z});
    const rim = (r: number, z: number) => Array.from({length: segs}, (_, j) => {
      const t = (2 * Math.PI * j) / segs;
      return {x: wx + r * Math.cos(t), y: CAR.wheelRadius + r * Math.sin(t), z};
    });
    const outer = rim(CAR.wheelRadius, zOut), inner = rim(CAR.wheelRadius, zIn);
    const mid = centre((zOut + zIn) / 2);
    for (let j = 0; j < segs; j++) {
      const j2 = (j + 1) % segs;
      const points = [outer[j], outer[j2], inner[j2], inner[j]];
      faces.push({points, normal: faceNormal(points, {x: wx, y: CAR.wheelRadius, z: points[0].z}), material: 'tire', bias: 0});
    }
    const disc = (points: Vec3[], material: Material, bias: number) => faces.push({points, normal: faceNormal(points, mid), material, bias});
    disc(outer, 'tire', 0.001);
    disc(inner.slice().reverse(), 'tire', 0);
    const face = zOut + side * 0.004;
    disc(rim(CAR.wheelRadius * 0.7, face), 'rim', 0.002);
    // Five aero spokes as darker wedges between the rim and the hub.
    for (let s = 0; s < 5; s++) {
      const t0 = (2 * Math.PI * s) / 5 + 0.28, t1 = t0 + 0.62;
      const wedge = [t0, (t0 + t1) / 2, t1].map(t => ({x: wx + CAR.wheelRadius * 0.62 * Math.cos(t), y: CAR.wheelRadius + CAR.wheelRadius * 0.62 * Math.sin(t), z: face + side * 0.002}));
      const hubSide = [t1, t0].map(t => ({x: wx + CAR.wheelRadius * 0.22 * Math.cos(t), y: CAR.wheelRadius + CAR.wheelRadius * 0.22 * Math.sin(t), z: face + side * 0.002}));
      disc([...wedge, ...hubSide], 'hub', 0.003);
    }
    disc(rim(CAR.wheelRadius * 0.16, face + side * 0.003), 'rim', 0.004);
  }
  return faces;
}

// ---- camera and shading ----

export interface View {width: number; height: number; yaw: number; pitch?: number; distance?: number}

/** Front three-quarter view: the nose toward the viewer's left, the driver's side in view. */
export const INITIAL_YAW = (-148 * Math.PI) / 180;
const DEFAULT_PITCH = (14 * Math.PI) / 180, DEFAULT_DISTANCE = 11;

const base: Record<Material, [number, number, number]> = {
  paint: [244, 243, 240], glass: [26, 30, 36], trim: [44, 46, 50], tire: [24, 24, 26], rim: [168, 172, 178], hub: [70, 73, 78],
  light: [236, 242, 248], tail: [176, 36, 40],
};
const shine: Record<Material, number> = {paint: 0.55, glass: 0.9, trim: 0.12, tire: 0.04, rim: 0.6, hub: 0.2, light: 0.4, tail: 0.5};
// Soft studio: a key light high on the left, a fill from the right, a bright sky dome.
const LIGHT = norm({x: -0.5, y: 0.9, z: 0.6});
const FILL = norm({x: 0.7, y: 0.35, z: 0.6});
const HALF = norm({x: LIGHT.x, y: LIGHT.y, z: LIGHT.z + 1});

function rotate(p: Vec3, yaw: number, pitch: number): Vec3 {
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  const x = p.x * cy + p.z * sy, z0 = -p.x * sy + p.z * cy;
  return {x, y: p.y * cp - z0 * sp, z: p.y * sp + z0 * cp};
}

export function shade(material: Material, n: Vec3): string {
  const diffuse = Math.max(0, dot(n, LIGHT)), fill = Math.max(0, dot(n, FILL));
  const sky = 0.5 + 0.5 * n.y;
  const spec = Math.pow(Math.max(0, dot(n, HALF)), 30) * shine[material] * 0.8;
  const rim = Math.pow(1 - Math.max(0, n.z), 3) * 0.08 * shine[material];
  const lit = material === 'light' ? 1 : 0.73 + 0.12 * sky + 0.16 * diffuse + 0.08 * fill;
  const [r, g, b] = base[material];
  const c = (v: number) => Math.round(Math.max(0, Math.min(255, v * lit + 255 * (spec + rim))));
  return `rgb(${c(r)},${c(g)},${c(b)})`;
}

/**
 * Projects the faces for one yaw: back faces culled, the rest flat-shaded and sorted far → near.
 * Scale and placement fit the car (any yaw) into the view with the ground line low in the frame.
 */
export function renderCar(faces: Face[], view: View): ScreenFace[] {
  const pitch = view.pitch ?? DEFAULT_PITCH, distance = view.distance ?? DEFAULT_DISTANCE;
  const {scale, cx, groundY} = frame(view);
  const project = (p: Vec3) => {
    const q = rotate({x: p.x, y: p.y - CAR.height * 0.42, z: p.z}, view.yaw, pitch);
    const k = distance / (distance - q.z);
    return {x: cx + q.x * k * scale, y: groundY - CAR.height * 0.42 * scale * Math.cos(pitch) - q.y * k * scale, z: q.z};
  };
  const out: ScreenFace[] = [];
  for (const face of faces) {
    const n = rotate(face.normal, view.yaw, pitch);
    const c = rotate(centroid(face.points), view.yaw, pitch);
    // Perspective-correct culling: the face must turn toward the camera at (0, ·, distance).
    const toCamera = {x: -c.x, y: -c.y + 0, z: distance - c.z};
    if (dot(n, toCamera) <= 0) continue;
    const pts = face.points.map(project);
    let d = '';
    for (let i = 0; i < pts.length; i++) d += `${i ? 'L' : 'M'}${pts[i].x.toFixed(1)} ${pts[i].y.toFixed(1)}`;
    out.push({d: `${d}Z`, fill: shade(face.material, n), depth: c.z + face.bias, material: face.material});
  }
  return out.sort((a, b) => a.depth - b.depth);
}

/** Scale (px per metre), horizontal centre and ground line for a view. */
export function frame(view: Pick<View, 'width' | 'height'>) {
  const scale = Math.min((view.width * 0.8) / CAR.length, (view.height * 0.54) / CAR.height);
  return {scale, cx: view.width / 2, groundY: view.height * 0.7};
}

/** The contact shadow: the footprint ellipse projected onto the ground, as an SVG path. */
export function shadowPath(view: View, grow = 1): string {
  const pitch = view.pitch ?? DEFAULT_PITCH, distance = view.distance ?? DEFAULT_DISTANCE;
  const {scale, cx, groundY} = frame(view);
  let d = '';
  for (let i = 0; i < 40; i++) {
    const t = (2 * Math.PI * i) / 40;
    const p = {x: Math.cos(t) * (CAR.length / 2) * 1.02 * grow, y: -CAR.height * 0.42, z: Math.sin(t) * (CAR.width / 2) * 1.08 * grow};
    const q = rotate(p, view.yaw, pitch);
    const k = distance / (distance - q.z);
    const x = cx + q.x * k * scale, y = groundY - CAR.height * 0.42 * scale * Math.cos(pitch) - q.y * k * scale;
    d += `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`;
  }
  return `${d}Z`;
}

/** Normalizes an angle to (−π, π]. */
export const wrapAngle = (a: number) => { const t = ((a + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI; return t === -Math.PI ? Math.PI : t; };

/** Drag → yaw: a full stage-width swipe turns the car half way round. */
export const yawForDrag = (startYaw: number, dx: number, width: number) => wrapAngle(startYaw + (dx / Math.max(1, width)) * Math.PI);

/** One inertia step: velocity in rad/ms decays exponentially (half-life ~120 ms). */
export function spinStep(yaw: number, velocity: number, dtMs: number): {yaw: number; velocity: number} {
  const v = velocity * Math.pow(0.5, dtMs / 120);
  return {yaw: wrapAngle(yaw + velocity * dtMs), velocity: Math.abs(v) < 1e-5 ? 0 : v};
}
