// The camera's back, opened, to load a film into: a fixed 2.5D view from above.
// Modelled in millimetres like a high-end mechanical 35 mm body: the die-cast shell with its
// machined, chamfered flange in satin black paint worn to metal at the edges; the cartridge
// chamber with its rewind fork; the film channel with polished outer and gate rails and a
// titanium shutter behind the 36 x 24 gate; the sprocket with its two rows of teeth; the brass
// train (sprocket gear, idler, spool gear); the slotted take-up spool with knurled flanges;
// the hinged back on the left, its pressure plate and light seals facing up.
// Load: drag the cartridge into the chamber, pull the leader to the spool, wind twice, close.
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  const look = {
    name: q.get('name') || 'Kodak Portra 400', maker: (q.get('maker') || 'KODAK').toUpperCase(),
    title: (q.get('title') || 'PORTRA').toUpperCase(), iso: q.get('iso') || '400',
    base: '#' + (q.get('base') || 'e9dfc6'), band: '#' + (q.get('band') || '5d4a8f'), ink: '#' + (q.get('ink') || '1c1a1a'),
  };
  const bridge = window.AndroidBack;

  // ------------------------------------------------------------------ renderer, camera, light
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(vw(), vh());
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.1;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  document.body.appendChild(renderer.domElement);
  const scene = new THREE.Scene();

  // the page's size: a WebView may run this before it is laid out (0 x 0), which would put
  // the camera at NaN and leave the screen blank for good
  function vw() { return window.innerWidth || document.documentElement.clientWidth || screen.width || 1; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight || screen.height || 1; }
  // straight down on the back (a front view of it), a little perspective, the camera's top at the
  // top of the screen; the body fills the screen. The phone's turn tilts the view a few degrees.
  const camera = new THREE.PerspectiveCamera(22, vw() / vh(), 10, 4000);
  camera.up.set(0, 0, -1);
  const BODY_W = 178, BODY_H = 118, BODY_Z = -11;
  let dist = 400, tiltX = 0, tiltZ = 0;
  function placeCamera() {
    const aspect = vw() / vh();
    camera.aspect = aspect; camera.updateProjectionMatrix();
    const vHalf = THREE.MathUtils.degToRad(11);
    const hHalf = Math.atan(Math.tan(vHalf) * aspect);
    dist = Math.max((BODY_H / 2) / Math.tan(vHalf), (BODY_W / 2) / Math.tan(hHalf)) + FL;
    aim();
  }
  function aim() {
    camera.position.set(Math.sin(tiltX) * dist, Math.cos(tiltX) * Math.cos(tiltZ) * dist, BODY_Z + Math.sin(tiltZ) * dist);
    camera.lookAt(0, 0, BODY_Z);
  }

  (function environment() {
    const room = new THREE.Scene();
    room.add(new THREE.Mesh(new THREE.BoxGeometry(14, 9, 14), new THREE.MeshBasicMaterial({ color: 0x24221f, side: THREE.BackSide })));
    function panel(w, h, x, y, z, rx, ry, k, color) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(k) }));
      m.position.set(x, y, z); m.rotation.set(rx, ry, 0); room.add(m);
    }
    panel(8, 4, -2, 4.4, 1.5, Math.PI / 2, 0, 5.5, 0xfff3e2);        // a big softbox overhead
    panel(4, 2, -6.9, 2, 2, 0, Math.PI / 2, 2.2, 0xfff0dc);          // warm side light, left
    panel(3, 5, 6.9, 1, -1, 0, -Math.PI / 2, 1.6, 0xd8e4ff);         // cool strip, right
    panel(10, 1.2, 0, 1, 6.9, 0, Math.PI, 0.8, 0xffffff);            // a low card in front
    const pm = new THREE.PMREMGenerator(renderer);
    scene.environment = pm.fromScene(room, 0.025).texture;
    pm.dispose();
  })();
  const key = new THREE.DirectionalLight(0xfff1df, 2.4);
  key.position.set(-120, 260, 140);
  key.target.position.set(-40, 0, 0);
  key.castShadow = true;
  key.shadow.mapSize.set(1024, 1024);
  Object.assign(key.shadow.camera, { left: -240, right: 140, top: 120, bottom: -120, near: 50, far: 700 });
  key.shadow.radius = 5; key.shadow.bias = -0.0005;
  scene.add(key, key.target);
  const rim = new THREE.DirectionalLight(0xdfe8ff, 0.7);
  rim.position.set(160, 120, -160);
  scene.add(rim);
  scene.add(new THREE.HemisphereLight(0xfff6ea, 0x2a2420, 0.3));

  // ------------------------------------------------------------------ textures
  function tex(w, h, draw, srgb) {
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c);
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    if (srgb) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }
  function rnd(n) { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }
  // paint: satin, a fine orange peel, handling marks
  const paintRough = tex(512, 512, (g, w, h) => {
    g.fillStyle = '#787878'; g.fillRect(0, 0, w, h);
    for (let i = 0; i < 9000; i++) { const v = 100 + 60 * rnd(i); g.fillStyle = `rgba(${v},${v},${v},0.5)`; g.fillRect(rnd(i + 1) * w, rnd(i + 2) * h, 2, 2); }
    g.strokeStyle = 'rgba(170,170,170,0.6)';
    for (let i = 0; i < 60; i++) { g.lineWidth = 0.7; const x = rnd(i + 9) * w, y = rnd(i + 19) * h; g.beginPath(); g.moveTo(x, y); g.lineTo(x + 40 * (rnd(i + 3) - 0.5), y + 40 * (rnd(i + 4) - 0.5)); g.stroke(); }
  }, false);
  paintRough.wrapS = paintRough.wrapT = THREE.RepeatWrapping; paintRough.repeat.set(3, 3);
  // brushed steel: lines along one way
  const brushed = tex(512, 64, (g, w, h) => {
    for (let y = 0; y < h; y++) { const v = 70 + 70 * rnd(y * 7.3); g.fillStyle = `rgb(${v},${v},${v})`; g.fillRect(0, y, w, 1); }
  }, false);
  brushed.wrapS = brushed.wrapT = THREE.RepeatWrapping;
  // leatherette: a pebbled black
  const pebble = tex(512, 512, (g, w, h) => {
    g.fillStyle = '#5a5a5a'; g.fillRect(0, 0, w, h);
    for (let i = 0; i < 2600; i++) { const r = 2 + rnd(i) * 5, v = 30 + 90 * rnd(i + 5); g.fillStyle = `rgb(${v},${v},${v})`; g.beginPath(); g.arc(rnd(i + 7) * w, rnd(i + 11) * h, r, 0, Math.PI * 2); g.fill(); }
  }, false);
  pebble.wrapS = pebble.wrapT = THREE.RepeatWrapping; pebble.repeat.set(4, 2);

  // ------------------------------------------------------------------ materials
  const paint = new THREE.MeshPhysicalMaterial({ color: 0x151515, metalness: 0.25, roughness: 0.5, roughnessMap: paintRough, clearcoat: 0.55, clearcoatRoughness: 0.32, envMapIntensity: 1.0 });
  const worn = new THREE.MeshPhysicalMaterial({ color: 0x9c9993, metalness: 1.0, roughness: 0.32, envMapIntensity: 1.2 }); // paint rubbed off the arrises
  const flock = new THREE.MeshStandardMaterial({ color: 0x0a0a0a, roughness: 1.0, metalness: 0.0 });
  const flockSide = new THREE.MeshStandardMaterial({ color: 0x0b0b0b, roughness: 1.0, side: THREE.BackSide });
  const polished = new THREE.MeshPhysicalMaterial({ color: 0xe2e1de, metalness: 1.0, roughness: 0.14, roughnessMap: brushed, envMapIntensity: 1.5 });
  const satin = new THREE.MeshPhysicalMaterial({ color: 0xc7c5c0, metalness: 1.0, roughness: 0.34, roughnessMap: brushed, envMapIntensity: 1.2 });
  const darkMetal = new THREE.MeshPhysicalMaterial({ color: 0x2c2b29, metalness: 0.9, roughness: 0.42, envMapIntensity: 0.9 });
  const brass = new THREE.MeshPhysicalMaterial({ color: 0xc9a25a, metalness: 1.0, roughness: 0.28, roughnessMap: brushed, envMapIntensity: 1.3 });
  const titanium = new THREE.MeshPhysicalMaterial({ color: 0x77777a, metalness: 0.95, roughness: 0.3, envMapIntensity: 1.1 });
  const leather = new THREE.MeshStandardMaterial({ color: 0x141414, roughness: 0.85, roughnessMap: pebble, bumpMap: pebble, bumpScale: 0.35 });
  const foam = new THREE.MeshStandardMaterial({ color: 0x0c0c0c, roughness: 1.0 });

  // ------------------------------------------------------------------ geometry helpers
  // a rounded rectangle as a Shape in (x, -z) so an extrusion rotated flat lies in the x-z plane
  function rrect(x0, z0, x1, z1, r) {
    const s = new THREE.Shape();
    const a = -z1, b = -z0; // shape y = -z
    s.moveTo(x0 + r, a); s.lineTo(x1 - r, a); s.quadraticCurveTo(x1, a, x1, a + r);
    s.lineTo(x1, b - r); s.quadraticCurveTo(x1, b, x1 - r, b); s.lineTo(x0 + r, b);
    s.quadraticCurveTo(x0, b, x0, b - r); s.lineTo(x0, a + r); s.quadraticCurveTo(x0, a, x0 + r, a);
    return s;
  }
  function holeOf(shape) { const p = new THREE.Path(); p.setFromPoints(shape.getPoints(8)); return p; }
  // a flat part: shape extruded [depth] upward with chamfered (bevelled) arrises, its top at [top]
  function plate(shape, depth, top, mats, bevel) {
    const geo = new THREE.ExtrudeGeometry(shape, {
      depth: depth, curveSegments: 10, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 3,
    });
    const m = new THREE.Mesh(geo, mats);
    m.rotation.x = -Math.PI / 2;
    m.position.y = top - depth - bevel;
    m.castShadow = true; m.receiveShadow = true;
    return m;
  }
  function box(w, h, d, mat, x, y, z) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
    m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true;
    return m;
  }
  // a gear: [teeth] trapezoid teeth round a disc, a bore, extruded [thick] along z
  function gearGeo(rOut, teeth, thick) {
    const s = new THREE.Shape();
    const rRoot = rOut - 0.9;
    for (let i = 0; i < teeth; i++) {
      const a0 = i / teeth * Math.PI * 2, step = Math.PI * 2 / teeth;
      const pts = [[rRoot, a0], [rOut, a0 + step * 0.2], [rOut, a0 + step * 0.45], [rRoot, a0 + step * 0.65]];
      pts.forEach(([r, a], k) => { const x = r * Math.cos(a), y = r * Math.sin(a); if (i === 0 && k === 0) s.moveTo(x, y); else s.lineTo(x, y); });
    }
    s.closePath();
    const bore = new THREE.Path(); bore.absarc(0, 0, rOut * 0.18, 0, Math.PI * 2, true); s.holes.push(bore);
    for (let k = 0; k < 4; k++) { // lightening holes
      const h = new THREE.Path(), a = k * Math.PI / 2 + 0.4, rr = rOut * 0.55;
      h.absarc(rr * Math.cos(a), rr * Math.sin(a), rOut * 0.16, 0, Math.PI * 2, true); s.holes.push(h);
    }
    return new THREE.ExtrudeGeometry(s, { depth: thick, bevelEnabled: true, bevelThickness: 0.15, bevelSize: 0.12, bevelSegments: 2, curveSegments: 12 });
  }
  function screw(x, y, z, r, mat) {
    const g = new THREE.Group();
    const head = new THREE.Mesh(new THREE.CylinderGeometry(r, r * 0.9, r * 0.5, 32), mat || satin);
    head.position.y = r * 0.25; g.add(head);
    const slot = new THREE.Mesh(new THREE.BoxGeometry(r * 1.8, r * 0.3, r * 0.28), flock);
    slot.position.y = r * 0.5; slot.rotation.y = rnd(x * 3 + z) * Math.PI; g.add(slot);
    g.position.set(x, y, z); scene.add(g);
  }

  // ------------------------------------------------------------------ the body
  const FL = 4;           // the flange's top, above the film plane (y = 0)
  placeCamera();
  const OX = 80, OZ = 40; // the body's outline
  const IX = 74, IZ = 24; // the opening in the back
  const outline = rrect(-OX, -OZ, OX, OZ, 11);
  const opening = rrect(-IX, -IZ, IX, IZ, 3.5);
  // the flange: black paint, its arrises worn to metal
  const flangeShape = rrect(-OX, -OZ, OX, OZ, 11); flangeShape.holes.push(holeOf(opening));
  scene.add(plate(flangeShape, 3.2, FL, [paint, worn], 0.55));
  // the shell's sides below it: leatherette, a paint band at its top
  const shellShape = rrect(-OX + 0.6, -OZ + 0.6, OX - 0.6, OZ - 0.6, 10.5); shellShape.holes.push(holeOf(rrect(-IX - 2, -IZ - 2, IX + 2, IZ + 2, 4)));
  scene.add(plate(shellShape, 30, FL - 4.4, [leather, leather], 0.3));
  // the inside: flocked black walls and floor
  const pit = new THREE.Mesh(new THREE.BoxGeometry(IX * 2, 34, IZ * 2), flockSide);
  pit.position.set(0, FL - 17, 0); pit.receiveShadow = true; scene.add(pit);

  // the film channel between the chambers: a plate at the film plane with the 36 x 24 gate in it
  const CH0 = -35, CH1 = 37;
  const channel = rrect(CH0, -IZ + 0.4, CH1, IZ - 0.4, 1.2);
  const gate = rrect(-18.1, -12.15, 18.1, 12.15, 0.6);
  channel.holes.push(holeOf(gate));
  scene.add(plate(channel, 1.2, -0.7, [paint, worn], 0.25));
  // its front and back walls down into the chambers
  for (const x of [CH0, CH1]) scene.add(box(1.4, 26, IZ * 2 - 1, paint, x + (x < 0 ? 0.7 : -0.7), -14, 0));
  // the shutter behind the gate: titanium foil, its fine ribs across the travel
  const foil = box(40, 0.4, 28, titanium, 0, -7.5, 0); scene.add(foil);
  for (let i = 0; i < 34; i++) scene.add(box(0.35, 0.25, 27, satin, -19 + i * 1.15, -7.15, 0));
  // the gate's own frame, a hairline of bright metal round the opening
  const gateRim = rrect(-19.0, -13.0, 19.0, 13.0, 1.0); gateRim.holes.push(holeOf(gate));
  scene.add(plate(gateRim, 0.2, -0.45, [satin, satin], 0.1));

  // the rails: outer ones carry the film's edges, the gate rails just inside, both polished
  for (const z of [-16.9, 16.9]) scene.add(box(CH1 - CH0 - 2, 0.9, 1.6, polished, (CH0 + CH1) / 2, -0.45, z));
  for (const z of [-13.4, 13.4]) scene.add(box(44, 0.8, 1.3, polished, 0, -0.55, z));
  // guide rollers either side of the gate
  for (const x of [CH0 + 3.5, CH1 - 4.5]) {
    const roller = new THREE.Mesh(new THREE.CylinderGeometry(1.4, 1.4, 38, 32), satin);
    roller.rotation.x = Math.PI / 2; roller.position.set(x, -1.6, 0); roller.castShadow = true; scene.add(roller);
  }
  // screws: the flange's corners, the channel's
  for (const [x, z] of [[-OX + 6, -OZ + 6], [OX - 6, -OZ + 6], [-OX + 6, OZ - 6], [OX - 6, OZ - 6]]) screw(x, FL, z, 1.4, satin);
  for (const [x, z] of [[CH0 + 2.2, -20.5], [CH0 + 2.2, 20.5], [CH1 - 2.2, -20.5], [CH1 - 2.2, 20.5]]) screw(x, -0.7, z, 0.9, darkMetal);

  // ------------------------------------------------------------------ the body's outside, seen past the back's edges
  const glass = new THREE.MeshPhysicalMaterial({ color: 0x05070a, metalness: 0.0, roughness: 0.04, clearcoat: 1.0, envMapIntensity: 1.6 });
  // a knurled cylinder standing on the top plate (its axis along z, the camera's up)
  function knob(r, h, mat, x, y, z, ribs) {
    const g = new THREE.CylinderGeometry(r, r, h, 160, 1);
    const pos = g.attributes.position;
    for (let i = 0; i < pos.count; i++) {
      const px = pos.getX(i), pz = pos.getZ(i), a = Math.atan2(pz, px);
      const k = 1 + 0.03 * Math.max(0, Math.cos(a * (ribs || 48)));
      pos.setX(i, px * k); pos.setZ(i, pz * k);
    }
    g.computeVertexNormals();
    const m = new THREE.Mesh(g, mat); m.rotation.x = Math.PI / 2; m.position.set(x, y, z);
    m.castShadow = true; m.receiveShadow = true; scene.add(m);
    return m;
  }
  (function () {
    const TOP = -OZ, BASE = OZ;
    // the top plate, brushed chrome, the full width; the base plate likewise
    scene.add(plate(rrect(-OX + 4, TOP - 7, OX - 4, TOP + 4, 3.5), 24, FL - 2.0, [satin, worn], 0.6));
    scene.add(plate(rrect(-OX + 4, BASE - 4, OX - 4, BASE + 4, 3.5), 24, FL - 2.0, [satin, worn], 0.6));
    // the prism's housing over the middle, black, its slopes catching the light
    const prism = new THREE.Shape();
    prism.moveTo(-27, -(TOP - 5)); prism.lineTo(-18, -(TOP - 19)); prism.quadraticCurveTo(-15, -(TOP - 22.5), -10, -(TOP - 22.5));
    prism.lineTo(10, -(TOP - 22.5)); prism.quadraticCurveTo(15, -(TOP - 22.5), 18, -(TOP - 19)); prism.lineTo(27, -(TOP - 5));
    prism.closePath();
    scene.add(plate(prism, 26, FL + 0.5, [paint, worn], 0.8));
    // the accessory shoe on its top
    scene.add(plate(rrect(-9.5, TOP - 27, 9.5, TOP - 22, 1), 3, FL - 3, [satin, worn], 0.3));
    for (const x of [-8, 8]) scene.add(box(1.2, 1.2, 5, polished, x, FL - 2.6, TOP - 24.5));
    // the eyepiece toward us: a rubber cup, its dark glass
    scene.add(plate(rrect(-10, TOP - 18, 10, TOP - 4, 3), 4, FL + 5, [foam, foam], 0.8));
    scene.add(plate(rrect(-6.5, TOP - 15, 6.5, TOP - 7, 1.2), 0.3, FL + 5.9, [glass, glass], 0));
    // left: the rewind knob, its crank folded on top
    knob(9.5, 9, satin, -55, FL - 9, TOP - 11.5, 60);
    scene.add(box(14, 1.6, 2.2, satin, -55, FL - 6, TOP - 16.6));
    scene.add(box(2.2, 2.4, 3.2, darkMetal, -61, FL - 5, TOP - 18));
    // right: the shutter speed dial, the release, the advance lever standing off
    knob(8.5, 5.5, darkMetal, 34, FL - 9, TOP - 9.6, 40);
    const dialTop = new THREE.Mesh(new THREE.CylinderGeometry(7.6, 7.6, 0.6, 48), satin);
    dialTop.rotation.x = Math.PI / 2; dialTop.position.set(34, FL - 9, TOP - 12.5); scene.add(dialTop);
    knob(4.2, 3.2, satin, 49, FL - 8, TOP - 8.6, 36);
    const release = new THREE.Mesh(new THREE.CylinderGeometry(2.4, 2.4, 2.4, 32), polished);
    release.rotation.x = Math.PI / 2; release.position.set(49, FL - 8, TOP - 11.2); release.castShadow = true; scene.add(release);
    knob(6, 3.4, satin, 64, FL - 9, TOP - 9.2, 30);
    const lever = new THREE.Group(); lever.position.set(64, FL - 9, TOP - 11.6);
    const arm = box(26, 2.4, 1.8, satin, -13, 0, 0); lever.add(arm);
    const tip = box(7, 3.6, 2.6, darkMetal, -25, 0.4, 0); lever.add(tip);
    lever.rotation.z = -0.28; // off the body, toward the thumb
    scene.add(lever);
    // the strap lugs at the ends, chrome eyelets
    for (const sx of [-1, 1]) {
      scene.add(box(4, 6, 6, satin, sx * (OX + 1.2), FL - 10, TOP + 11));
      const ring = new THREE.Mesh(new THREE.TorusGeometry(2.6, 0.75, 16, 40), polished);
      ring.position.set(sx * (OX + 3.8), FL - 10, TOP + 11); ring.castShadow = true; scene.add(ring);
    }
    // the self-timer lever on the front's left, peeking past the end
    scene.add(box(3, 3, 9, darkMetal, -OX - 1.2, FL - 14, BASE - 18));
  })();

  // ------------------------------------------------------------------ the sprocket and the train
  const SPX = 31.5, SPR = 5.8, SPY = -SPR;
  const sprocket = new THREE.Group();
  sprocket.position.set(SPX, SPY, 0);
  scene.add(sprocket);
  (function () {
    const drum = new THREE.Mesh(new THREE.CylinderGeometry(SPR - 0.6, SPR - 0.6, 36, 48), satin);
    drum.rotation.x = Math.PI / 2; drum.castShadow = true; sprocket.add(drum);
    for (const z of [-14.6, 14.6]) {
      const ring = new THREE.Mesh(new THREE.CylinderGeometry(SPR, SPR, 3.2, 48), polished);
      ring.rotation.x = Math.PI / 2; ring.position.z = z; sprocket.add(ring);
      for (let i = 0; i < 8; i++) { // teeth: one to a perforation, 8 to a frame
        const a = i / 8 * Math.PI * 2;
        const t = new THREE.Mesh(new THREE.BoxGeometry(1.5, 1.6, 2.2), polished);
        t.position.set(Math.cos(a) * (SPR + 0.6), Math.sin(a) * (SPR + 0.6), z);
        t.rotation.z = a; sprocket.add(t);
      }
    }
  })();
  const SPOOL_X = 55, SPOOL_R = 7.4, SPOOL_Y = -SPOOL_R - 0.2;
  const spool = new THREE.Group();
  spool.position.set(SPOOL_X, SPOOL_Y, 0);
  scene.add(spool);
  (function () {
    const core = new THREE.Mesh(new THREE.CylinderGeometry(SPOOL_R, SPOOL_R, 35.4, 64), darkMetal);
    core.rotation.x = Math.PI / 2; core.castShadow = true; core.receiveShadow = true; spool.add(core);
    for (let k = 0; k < 3; k++) { // the slots that catch the tongue
      const a = k / 3 * Math.PI * 2;
      const slot = new THREE.Mesh(new THREE.BoxGeometry(1.0, 2.2, 30), flock);
      slot.position.set(Math.cos(a) * (SPOOL_R - 0.6), Math.sin(a) * (SPOOL_R - 0.6), 0); slot.rotation.z = a; spool.add(slot);
    }
    for (const z of [-18.6, 18.6]) { // knurled flanges
      const g = new THREE.CylinderGeometry(SPOOL_R + 1.6, SPOOL_R + 1.6, 1.4, 120, 1);
      const pos = g.attributes.position;
      for (let i = 0; i < pos.count; i++) {
        const x = pos.getX(i), zz = pos.getZ(i), a = Math.atan2(zz, x), r = Math.hypot(x, zz);
        if (r > SPOOL_R + 1) { const k = 1 + 0.025 * Math.max(0, Math.cos(a * 60)); pos.setX(i, x * k); pos.setZ(i, zz * k); }
      }
      g.computeVertexNormals();
      const f = new THREE.Mesh(g, satin); f.rotation.x = Math.PI / 2; f.position.z = z; f.castShadow = true; spool.add(f);
    }
  })();
  // the train, in a recess at the top of the opening: the sprocket's gear, an idler, the spool's gear
  const train = [];
  function gear(r, teeth, x, y, ratio) {
    const m = new THREE.Mesh(gearGeo(r, teeth, 1.6), brass);
    const g = new THREE.Group(); g.position.set(x, y, -IZ + 0.6); g.add(m);
    m.castShadow = true; m.receiveShadow = true;
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(r * 0.24, r * 0.24, 2.4, 24), polished);
    hub.rotation.x = Math.PI / 2; hub.position.z = 1.0; g.add(hub);
    scene.add(g); train.push({ g, ratio });
  }
  gear(7.2, 30, SPX, SPY, 1);
  gear(4.6, 19, (SPX + SPOOL_X) / 2 + 0.6, SPY - 3.2, -30 / 19);
  gear(7.6, 32, SPOOL_X, SPOOL_Y, 30 / 32);

  // ------------------------------------------------------------------ the chamber: the cartridge sits here, the fork above it
  const CHX = -55; // the chamber's middle
  const fork = new THREE.Group();
  fork.position.set(CHX, -10.5, -IZ + 2);
  for (const dx of [-1.6, 1.6]) { const p = new THREE.Mesh(new THREE.BoxGeometry(0.9, 2.6, 5.5), satin); p.position.set(dx, 0, 2.6); p.castShadow = true; fork.add(p); }
  const forkHub = new THREE.Mesh(new THREE.CylinderGeometry(3.4, 3.4, 2.4, 32), satin); forkHub.rotation.x = Math.PI / 2; fork.add(forkHub);
  scene.add(fork);

  // ------------------------------------------------------------------ the back door, hinged on the left, open
  const door = new THREE.Group();
  door.position.set(-OX, FL, 0);
  scene.add(door);
  (function () {
    // in its own frame the door lies over the back (x 0 .. 160) when shut; its inside faces -y
    const shell = plate(rrect(0, -OZ, 2 * OX, OZ, 11), 2.6, 0, [paint, worn], 0.5);
    shell.position.y = 0.3; door.add(shell);
    const skin = plate(rrect(1.2, -OZ + 1.2, 2 * OX - 1.2, OZ - 1.2, 10), 2.0, 5.4, [leather, leather], 0.2);
    door.add(skin);
    // outside: the memo holder (a film box's end slides in), its chrome frame and clips
    const memo = rrect(OX - 27, -13, OX + 27, 13, 2); memo.holes.push(holeOf(rrect(OX - 24, -10, OX + 24, 10, 1.2)));
    const holder = plate(memo, 0.8, 6.4, [satin, worn], 0.25); door.add(holder);
    const well = plate(rrect(OX - 24, -10, OX + 24, 10, 1.2), 0.4, 5.6, [darkMetal, darkMetal], 0); door.add(well);
    for (const x of [OX - 27, OX + 27]) door.add(box(3, 1.4, 16, satin, x, 6.4, 0));
    // the door's own edge: a thin chrome bead along the latch side
    door.add(box(1.6, 1.2, 2 * OZ - 22, satin, 2 * OX - 0.8, 4.2, 0));
    // inside: the pressure plate over the gate, on its springs; the light seals round the edge
    const pp = plate(rrect(OX - 31, -14.5, OX + 31, 14.5, 2.5), 1.0, -0.2, [polished, polished], 0.3);
    pp.rotation.x = Math.PI / 2; pp.position.y = -0.1; door.add(pp);
    for (const x of [OX - 22, OX + 22]) {
      const spring = new THREE.Mesh(new THREE.BoxGeometry(6, 0.3, 3), satin); spring.position.set(x, -1.6, 0); door.add(spring);
    }
    for (const [x0, x1, z] of [[3, 2 * OX - 3, -OZ + 3], [3, 2 * OX - 3, OZ - 3]]) {
      const s = new THREE.Mesh(new THREE.BoxGeometry(x1 - x0, 1.2, 2), foam); s.position.set((x0 + x1) / 2, -0.6, z); door.add(s);
    }
    // the hinge's knuckles
    for (const z of [-30, -10, 10, 30]) {
      const k = new THREE.Mesh(new THREE.CylinderGeometry(2, 2, 8, 24), satin); k.rotation.x = Math.PI / 2; k.position.set(0, 1, z); door.add(k);
    }
  })();
  const DOOR_OPEN = Math.PI; // open flat, out past the left edge of the screen
  door.rotation.z = DOOR_OPEN;

  // a soft floor to throw shadows on
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(1200, 800), new THREE.ShadowMaterial({ opacity: 0.35 }));
  floor.rotation.x = -Math.PI / 2; floor.position.y = FL - 34.6; floor.receiveShadow = true; scene.add(floor);

  // ------------------------------------------------------------------ the cartridge
  const cart = CartridgeModel(THREE, renderer, look);
  const spin = new THREE.Group();         // turned on its own axis so its lips face the gate
  const holder = new THREE.Group();       // lies it down: its axis along the camera's height (z)
  spin.add(cart.group); holder.add(spin); scene.add(holder);
  holder.rotation.x = -Math.PI / 2;
  {
    const e = cart.exitDir;
    spin.rotation.y = Math.PI / 2 - Math.atan2(e.x, e.z);
  }
  cart.setLeader(16);
  holder.updateMatrixWorld(true);
  // where the film leaves the lips, with the cartridge at the origin
  const exitLocal = cart.mouth.clone().addScaledVector(cart.exitDir, 6.0);
  function exitWorld() { holder.updateMatrixWorld(true); return cart.group.localToWorld(exitLocal.clone()); }
  const e0 = exitWorld();
  // seated: the exit on the film plane, at the channel's left edge
  // its top (the caps' rims, 13.05 mm off the axis) under the closed door's inside
  const SEAT = new THREE.Vector3(CH0 - 1.5 - e0.x, FL - 1.2 - 13.05, -e0.z);
  const START = new THREE.Vector3(CHX + 6, 40, 14);
  holder.position.copy(START);
  holder.rotation.z = 0.5;
  cart.group.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });

  // ------------------------------------------------------------------ the film through the camera
  const N = 220;
  const filmGeo = new THREE.PlaneGeometry(1, 1, N, 1);
  const perfs = tex(256, 236, (g, w, h) => { // one 8-perforation length (38 mm) of a 35 mm film
    const mx = w / 38, my = h / 35;
    g.fillStyle = '#fff'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#000';
    for (let k = 0; k < 8; k++) for (const e of [2.01, 35 - 2.01 - 2.794]) {
      const x = (k * 4.75 + 1.4) * mx, y = e * my, ww = 1.98 * mx, hh = 2.794 * my, r = 0.5 * mx;
      g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + ww, y, x + ww, y + hh, r); g.arcTo(x + ww, y + hh, x, y + hh, r); g.arcTo(x, y + hh, x, y, r); g.arcTo(x, y, x + ww, y, r); g.fill();
    }
  }, false);
  perfs.wrapS = THREE.RepeatWrapping;
  const filmMat = new THREE.MeshPhysicalMaterial({
    color: 0x5a2a0c, roughness: 0.22, clearcoat: 0.8, clearcoatRoughness: 0.1, alphaMap: perfs, alphaTest: 0.5,
    transparent: true, opacity: 0.95, side: THREE.DoubleSide, sheen: 0.3, sheenColor: new THREE.Color(0xffb070),
  });
  const film = new THREE.Mesh(filmGeo, filmMat);
  film.castShadow = true;
  film.customDepthMaterial = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking, alphaMap: perfs, alphaTest: 0.5 });
  film.visible = false;
  film.frustumCulled = false;
  scene.add(film);
  let filmLen = 16;   // mm of film out of the cartridge
  let hooked = false;
  const W2 = 17.5;
  function filmPoint(s, out) {
    // from the lips along the film plane; once hooked, over the spool's top and round it
    const E = filmStart;
    const straight = SPOOL_X - E.x;
    if (!hooked || s <= straight) {
      const tip = hooked ? 0 : Math.max(0, s - (filmLen - 18)) * 0.07; // a free end lifts a little
      const ramp = Math.min(1, s / 7);                                 // from the lips' height to the rails'
      out.set(E.x + s, E.y + (0.15 - E.y) * (ramp * ramp * (3 - 2 * ramp)) + tip * tip, 0);
      return;
    }
    let rem = s - straight, phi = 0, r = SPOOL_R + 0.35;
    while (rem > 0) { const ds = Math.min(rem, 1.5); phi += ds / r; r += 0.14 * ds / (2 * Math.PI * r); rem -= ds; }
    out.set(SPOOL_X + r * Math.sin(phi), SPOOL_Y + r * Math.cos(phi), 0);
  }
  let filmStart = new THREE.Vector3();
  const tmp = new THREE.Vector3();
  function shapeFilm() {
    const pos = filmGeo.attributes.position, uv = filmGeo.attributes.uv;
    for (let i = 0; i < pos.count; i++) {
      const col = i % (N + 1), row = Math.floor(i / (N + 1));
      const s = col / N * filmLen;
      filmPoint(s, tmp);
      // the leader's tongue: its first 60 mm cut to half the width, a sloped shoulder
      const fromEnd = filmLen - s;
      let zEdge = row === 0 ? -W2 : W2, v = row === 0 ? 1 : 0;
      if (row === 1) {
        const k = THREE.MathUtils.clamp((62 - fromEnd) / 6, 0, 1);
        zEdge = THREE.MathUtils.lerp(W2, 0, k); v = THREE.MathUtils.lerp(0, 0.5, k);
      }
      pos.setXYZ(i, tmp.x, tmp.y, zEdge);
      uv.setXY(i, (filmLen - s) / 38, v);
    }
    pos.needsUpdate = true; uv.needsUpdate = true;
    filmGeo.computeVertexNormals();
    // the train turns with the film: the sprocket by the film's travel, the rest geared to it
    const turn = (filmLen - 16) / SPR;
    sprocket.rotation.z = -turn;
    for (const t of train) t.g.rotation.z = -turn * t.ratio;
    spool.rotation.z = hooked ? -(filmLen - (SPOOL_X - filmStart.x)) / (SPOOL_R + 0.4) : 0;
    cart.spool.rotation.y = -filmLen / 4.6;
  }

  // ------------------------------------------------------------------ steps
  const ui = { step: document.getElementById('step'), hint: document.getElementById('hint'), act: document.getElementById('act') };
  let stage = 0, winds = 0, busy = false;
  const STEPS = [
    ['装入暗盒', '把暗盒拖进左边的片仓', '放入'],
    ['牵引片头', '按住片头向右拉，挂到收片轴的槽里', '挂上'],
    ['过片', '扳动过片杆两次，让齿轮咬住齿孔', '过片'],
    ['合上后盖', '一切就位：合上后盖', '合上'],
  ];
  function showStage() {
    const [t, h, a] = STEPS[stage];
    ui.step.innerHTML = t + '<small>' + (stage + 1) + ' / 4 · ' + look.name + '</small>';
    ui.hint.textContent = stage === 2 ? h + '（' + winds + ' / 2）' : h;
    ui.act.textContent = a; ui.act.style.display = 'block';
  }
  showStage();

  const tweens = [];
  function tween(ms, step, done) { tweens.push({ t0: performance.now(), ms, step, done }); wake(); }
  const ease = (k) => k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;

  function seat() {
    if (busy) return; busy = true;
    const from = holder.position.clone(), r0 = holder.rotation.z;
    tween(900, (k) => {
      const e = ease(k);
      holder.position.lerpVectors(from, SEAT, e);
      holder.position.y += Math.sin(k * Math.PI) * 12;   // lifted over the rim, then down in
      holder.rotation.z = r0 * (1 - e);
    }, () => {
      holder.position.copy(SEAT); holder.rotation.z = 0;
      // the film now runs through the camera: from the lips, along the plane
      filmStart = exitWorld();
      cart.setLeader(0); film.visible = true; filmLen = 16; shapeFilm();
      busy = false; stage = 1; showStage();
    });
  }
  function hook() {
    if (busy) return; busy = true;
    const from = filmLen, to = SPOOL_X - filmStart.x;
    tween(700, (k) => { filmLen = from + (to - from) * ease(k); shapeFilm(); }, () => {
      hooked = true;
      const a = filmLen;
      tween(600, (k) => { filmLen = a + 26 * ease(k); shapeFilm(); }, () => { busy = false; stage = 2; showStage(); });
    });
  }
  function wind() {
    if (busy) return; busy = true;
    const a = filmLen;
    tween(650, (k) => { filmLen = a + 38 * ease(k); shapeFilm(); }, () => {
      busy = false; winds++;
      if (winds >= 2) stage = 3;
      showStage();
    });
  }
  function close() {
    if (busy) return; busy = true;
    ui.act.style.display = 'none';
    tween(1100, (k) => { door.rotation.z = DOOR_OPEN * (1 - ease(k)); }, () => {
      document.getElementById('veil').style.opacity = '1';
      setTimeout(() => { if (bridge) bridge.done(); }, 650);
    });
  }
  ui.act.addEventListener('click', () => { [seat, hook, wind, close][stage](); });
  document.getElementById('skip').addEventListener('click', () => { if (bridge) bridge.skip(); });

  // ------------------------------------------------------------------ touch: the cartridge, then the leader
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2(), hit = new THREE.Vector3();
  function onPlane(e, y) {
    ndc.set(e.clientX / vw() * 2 - 1, -(e.clientY / vh()) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    return ray.ray.intersectPlane(new THREE.Plane(new THREE.Vector3(0, 1, 0), -y), hit);
  }
  function screenOf(v) { const p = v.clone().project(camera); return [(p.x + 1) / 2 * vw(), (1 - p.y) / 2 * vh()]; }
  let drag = null;
  const el = renderer.domElement;
  el.addEventListener('pointerdown', (e) => {
    if (busy) return;
    if (stage === 0) {
      const [sx, sy] = screenOf(holder.position);
      if (Math.hypot(e.clientX - sx, e.clientY - sy) < Math.max(90, vh() * 0.18)) drag = 'cart';
    } else if (stage === 1) {
      tmp.set(filmStart.x + filmLen, 0, 0);
      const [sx, sy] = screenOf(tmp);
      if (Math.hypot(e.clientX - sx, e.clientY - sy) < 80) drag = 'film';
    } else if (stage === 2) drag = 'lever';
    if (drag) { el.setPointerCapture(e.pointerId); dragStart = e.clientX; }
  });
  let dragStart = 0;
  el.addEventListener('pointermove', (e) => {
    if (drag === 'cart' && onPlane(e, 30)) { holder.position.set(hit.x, 30, hit.z); wake(); }
    else if (drag === 'film' && onPlane(e, 0)) { filmLen = THREE.MathUtils.clamp(hit.x - filmStart.x, 16, SPOOL_X - filmStart.x + 6); shapeFilm(); wake(); }
  });
  el.addEventListener('pointerup', (e) => {
    const was = drag; drag = null;
    if (was === 'cart') {
      if (Math.hypot(holder.position.x - (SEAT.x), holder.position.z - SEAT.z) < 34) seat();
      else { const from = holder.position.clone(); tween(450, (k) => holder.position.lerpVectors(from, START, ease(k))); }
    } else if (was === 'film') {
      if (filmLen > SPOOL_X - filmStart.x - 12) hook();
      else { const a = filmLen; tween(400, (k) => { filmLen = a + (16 - a) * ease(k); shapeFilm(); }); }
    } else if (was === 'lever') {
      if (e.clientX - dragStart > 40 || Math.abs(e.clientX - dragStart) < 8) wind(); // a swipe across, or a tap
    }
  });

  // ------------------------------------------------------------------ the loop: draws only while something moves
  let awake = false;
  function wake() { if (!awake) { awake = true; requestAnimationFrame(frame); } }
  function frame(now) {
    for (let i = tweens.length - 1; i >= 0; i--) {
      const t = tweens[i];
      const k = Math.min(1, (performance.now() - t.t0) / t.ms);
      t.step(k);
      if (k >= 1) { tweens.splice(i, 1); if (t.done) t.done(); }
    }
    // the view eases toward the phone's lean
    tiltX += (wantX - tiltX) * 0.12; tiltZ += (wantZ - tiltZ) * 0.12;
    aim();
    renderer.render(scene, camera);
    const leaning = Math.abs(wantX - tiltX) > 1e-4 || Math.abs(wantZ - tiltZ) > 1e-4;
    if (tweens.length || drag || leaning) requestAnimationFrame(frame); else awake = false;
  }
  window.addEventListener('resize', () => { renderer.setSize(vw(), vh()); placeCamera(); wake(); });
  let ref = null, wantX = 0, wantZ = 0;
  renderer.domElement.addEventListener('webglcontextlost', (e) => { e.preventDefault(); });
  renderer.domElement.addEventListener('webglcontextrestored', () => location.reload());
  if (!window.innerWidth || !window.innerHeight) {
    const poll = () => { if (window.innerWidth && window.innerHeight) window.dispatchEvent(new Event('resize')); else requestAnimationFrame(poll); };
    requestAnimationFrame(poll);
  }
  window.addEventListener('deviceorientation', (e) => {
    if (e.beta == null || e.gamma == null) return;
    // landscape: the phone's tilt left/right is beta, toward/away gamma
    if (!ref) ref = { b: e.beta, g: e.gamma };
    const lim = THREE.MathUtils.degToRad(8);
    wantX = THREE.MathUtils.clamp(THREE.MathUtils.degToRad(e.beta - ref.b) * 0.35, -lim, lim);
    wantZ = THREE.MathUtils.clamp(THREE.MathUtils.degToRad(e.gamma - ref.g) * 0.35, -lim, lim);
    // the rest position drifts toward how it is held now, so the view always comes home
    ref.b += (e.beta - ref.b) * 0.01; ref.g += (e.gamma - ref.g) * 0.01;
    wake();
  });
  // the cartridge settles in the air on arrival
  const r0 = holder.rotation.z;
  // (not once it is being put in: the seating sets its own angle)
  tween(900, (k) => { if (stage === 0 && !busy) holder.rotation.z = r0 * (1 - 0.4 * ease(k)); });
})();
