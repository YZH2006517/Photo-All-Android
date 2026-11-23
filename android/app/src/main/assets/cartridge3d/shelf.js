// The film shelf: a wooden cabinet against a panelled wall, every stock's cartridge on its upper
// boards, every paper's box (or can) below. As many to a board as the screen is wide, so it works
// upright and on its side; drag up and down to look. A tap opens the detail page
// (AndroidShelf.inspect / inspectPaper). Units are millimetres.
//
// Kept light for a phone: the things never move (the camera does), so the shadows are drawn once
// per arrangement; the pixel ratio is held to 1.5; frames are drawn only while something moves.
(function () {
  'use strict';
  const bridge = window.AndroidShelf;
  let awake = false;
  let sharpen = 0; // the timer for the sharp frame at rest
  let data;
  try { data = JSON.parse(bridge ? bridge.items() : 'null'); } catch (e) { data = null; }
  if (!data || !data.films) data = {   // the page on its own (a desk preview)
    films: [
      { id: 'kodak portra 400', name: 'Kodak Portra 400', maker: 'KODAK', title: 'PORTRA', iso: '400', base: 'e9dfc6', band: '5d4a8f', ink: '1c1a1a' },
      { id: 'kodak ektar 100', name: 'Kodak Ektar 100', maker: 'KODAK', title: 'EKTAR', iso: '100', base: 'ecb220', band: 'b9262b', ink: '1a1a1a' },
      { id: 'fujifilm velvia 100 (positive)', name: 'Fujifilm Velvia 100', maker: 'FUJIFILM', title: 'VELVIA', iso: '100', base: '1f7a45', band: 'a82a66', ink: 'f7f4ea' },
      { id: 'kodak vision3 500t', name: 'Kodak Vision3 500t', maker: 'KODAK', title: 'VISION3 T', iso: '500', base: '1b1b1c', band: 'ecb220', ink: 'f2eedf' },
      { id: 'kodak double-x (b/w)', name: 'Kodak Double-x', maker: 'KODAK', title: 'DOUBLE-X', iso: '250', base: 'ecb220', band: '161616', ink: '161616' },
      { id: 'kodak gold 200', name: 'Kodak Gold 200', maker: 'KODAK', title: 'GOLD', iso: '200', base: 'e2a31c', band: '7f1d1d', ink: '1a1a1a' },
      { id: 'kodak portra 160', name: 'Kodak Portra 160', maker: 'KODAK', title: 'PORTRA', iso: '160', base: 'e9dfc6', band: '5d4a8f', ink: '1c1a1a' },
      { id: 'fujifilm c200', name: 'Fujifilm C200', maker: 'FUJIFILM', title: 'C', iso: '200', base: '1f7a45', band: 'f1efe8', ink: 'f7f4ea' },
    ],
    papers: [{ id: 'kodak endura premier' }, { id: 'kodak portra endura' }, { id: 'fujifilm crystal archive type ii' }, { id: 'kodak 2383' }, { id: 'kodak 2302 (b/w)' }],
  };
  document.getElementById('count').textContent = data.films.length + ' 卷胶片 · ' + data.papers.length + ' 种相纸';

  function vw() { return window.innerWidth || document.documentElement.clientWidth || screen.width || 1; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight || screen.height || 1; }

  // ------------------------------------------------------------------ renderer, camera, light
  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
  // Moving, the cabinet is drawn at 1.5x (smooth); once it rests, one frame at the screen's own
  // density (up to 3x) leaves it sharp. A still shelf costs nothing more.
  const LOW = Math.min(window.devicePixelRatio || 1, 1.5);
  const HIGH = Math.min(window.devicePixelRatio || 1, 3);
  renderer.setPixelRatio(LOW);
  renderer.setSize(vw(), vh());
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.shadowMap.autoUpdate = false;           // drawn again only when things are placed
  document.body.appendChild(renderer.domElement);
  renderer.domElement.addEventListener('webglcontextlost', (e) => e.preventDefault());
  renderer.domElement.addEventListener('webglcontextrestored', () => location.reload());

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x141210);
  const camera = new THREE.PerspectiveCamera(26, vw() / vh(), 10, 6000);
  const HALF = THREE.MathUtils.degToRad(13);
  let dist = 700, camY = 0, leanX = 0, leanY = 0, wantX = 0, wantY = 0;
  // upright: 300 mm across the screen; on its side: 340 mm top to bottom
  function fitDist() { const a = vw() / vh(); return a < 1 ? 150 / (Math.tan(HALF) * a) : 170 / Math.tan(HALF); }
  function viewW() { return 2 * Math.tan(HALF) * dist * (vw() / vh()); }
  function viewH() { return 2 * Math.tan(HALF) * dist; }
  function aim() {
    camera.position.set(leanX * 50, camY + 50 + leanY * 30, dist);
    camera.lookAt(leanX * 15, camY, 0);
  }

  (function environment() {
    const room = new THREE.Scene();
    room.add(new THREE.Mesh(new THREE.BoxGeometry(12, 8, 12), new THREE.MeshBasicMaterial({ color: 0x2a2622, side: THREE.BackSide })));
    function panel(w, h, x, y, z, ry, rx, k, color) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(k) }));
      m.position.set(x, y, z); m.rotation.set(rx || 0, ry || 0, 0); room.add(m);
    }
    panel(6, 2.5, -2, 3, 4, Math.PI * 0.85, -0.4, 5, 0xfff1dc);
    panel(10, 2, 0, 3.9, 0, 0, Math.PI / 2, 1.6, 0xffffff);
    panel(3, 4, 5.9, 0, 1, -Math.PI / 2, 0, 1.4, 0xe2ecff);
    const pm = new THREE.PMREMGenerator(renderer);
    scene.environment = pm.fromScene(room, 0.03).texture;
    pm.dispose();
  })();
  const key = new THREE.DirectionalLight(0xfff0dc, 2.4);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.radius = 4; key.shadow.bias = -0.0005;
  scene.add(key, key.target);
  scene.add(new THREE.HemisphereLight(0xfff4e6, 0x2a2420, 0.45));

  // ------------------------------------------------------------------ wood
  function tex(w, h, draw) {
    const c = document.createElement('canvas'); c.width = w; c.height = h; draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    return t;
  }
  function woodTex(base, dark, seams) {
    return tex(1024, 256, (g, w, h) => {
      g.fillStyle = base; g.fillRect(0, 0, w, h);
      for (let i = 0; i < 140; i++) {
        g.strokeStyle = 'rgba(' + dark + ',' + (0.08 + Math.random() * 0.18) + ')';
        g.lineWidth = 0.6 + Math.random() * 2.2;
        const y0 = Math.random() * h;
        g.beginPath();
        for (let x = 0; x <= w; x += 16) { const y = y0 + Math.sin(x * 0.006 + i) * 6 + Math.sin(x * 0.03 + i * 3) * 1.5; if (x === 0) g.moveTo(x, y); else g.lineTo(x, y); }
        g.stroke();
      }
      if (seams) for (let x = 0; x < w; x += w / 6) { g.fillStyle = 'rgba(0,0,0,0.35)'; g.fillRect(x, 0, 3, h); }
    });
  }
  const oak = new THREE.MeshStandardMaterial({ map: woodTex('#7a5634', '40,24,10'), roughness: 0.5, metalness: 0 });
  const wallTex = woodTex('#3b2a1c', '10,6,2', true);
  wallTex.rotation = Math.PI / 2; wallTex.repeat.set(1, 8);
  const wall = new THREE.Mesh(new THREE.PlaneGeometry(4000, 6000), new THREE.MeshStandardMaterial({ map: wallTex, roughness: 0.85 }));
  wall.position.set(0, -1500, -45); wall.receiveShadow = true; scene.add(wall);

  // ------------------------------------------------------------------ what stands on the shelves
  const FILM_GAP = 50, PAPER_GAP = 126, FILM_SCALE = 1.5;
  const FILM_ROW = 112, PAPER_ROW = 190;           // from one board's top to the next one's
  const items = [];                                // {obj, kind, id, tag}
  function tagMesh(text, wide) {
    const t = tex(512, 96, (g, w, h) => {
      g.fillStyle = '#efe7d3'; g.fillRect(0, 0, w, h);
      g.fillStyle = '#2a2420'; g.textBaseline = 'middle'; g.textAlign = 'center';
      let f = 40; g.font = '600 ' + f + 'px sans-serif';
      while (g.measureText(text).width > w - 30 && f > 18) { f -= 2; g.font = '600 ' + f + 'px sans-serif'; }
      g.fillText(text, w / 2, h / 2 + 2);
    });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(wide ? 74 : 44, wide ? 13 : 8.2), new THREE.MeshStandardMaterial({ map: t, roughness: 0.7 }));
    scene.add(m);
    return m;
  }
  const short = (id) => id.replace(/^(kodak|fujifilm|fuji|ilford)\s+/i, '').replace(/\s*\((positive|b\/w)\)/i, '').replace(/\btype ii\b/i, 'II')
    .replace(/\b\w/g, (c) => c.toUpperCase());

  data.papers.forEach((p) => {
    const m = window.PaperModel(THREE, renderer, p.id);
    const holder = new THREE.Group();
    holder.add(m.group);
    if (m.kind === 'box') { m.group.position.set(0, m.height / 2 + 2, 0); holder.rotation.x = -0.12; }
    else m.group.position.set(0, m.height / 2, 0);
    holder.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    scene.add(holder);
    items.push({ obj: holder, kind: 'paper', id: p.id, tag: tagMesh(short(p.id), true) });
  });
  let next = 0;
  function buildFilm() {                           // one at a time: the shelf shows at once and fills in
    if (next >= data.films.length) { renderer.shadowMap.needsUpdate = true; wake(); return; }
    const f = data.films[next];
    const cart = window.CartridgeModel(THREE, renderer, { name: f.name, maker: f.maker, title: f.title, iso: f.iso,
      base: '#' + f.base, band: '#' + f.band, ink: '#' + f.ink, lite: true });
    cart.setLeader(0);
    const box = new THREE.Box3().setFromObject(cart.group);
    const holder = new THREE.Group();
    holder.add(cart.group);
    cart.group.position.y = -box.min.y;
    holder.scale.setScalar(FILM_SCALE);
    holder.rotation.y = -0.18 + (next % 3) * 0.12;
    holder.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    scene.add(holder);
    items.splice(next, 0, { obj: holder, kind: 'film', id: f.id, tag: tagMesh(short(f.name)) });
    next++;
    arrange();
    setTimeout(buildFilm, 16);
  }

  // ------------------------------------------------------------------ the cabinet: boards as wide as the view
  const boards = [];
  let top = 0, bottom = 0;                          // the cabinet's extent, for scrolling
  function board(y, width) {
    let b = boards.find((x) => !x.visible);
    if (!b) { b = new THREE.Mesh(new THREE.BoxGeometry(1, 12, 80), oak); b.castShadow = true; b.receiveShadow = true; scene.add(b); boards.push(b); }
    b.visible = true; b.scale.x = width; b.position.set(0, y - 6, -5);
  }
  function arrange() {
    dist = fitDist();
    const w = viewW() * 0.92;
    boards.forEach((b) => { b.visible = false; });
    const films = items.filter((i) => i.kind === 'film');
    const papers = items.filter((i) => i.kind === 'paper');
    // rowH is the room ABOVE a board, for what stands on it: the first paper board goes
    // PAPER_ROW below the last film board (it went FILM_ROW, and the 127 mm boxes poked up
    // through the film board's underside)
    let y = 0, first = true, lastH = FILM_ROW;
    function lay(list, gap, rowH) {
      const per = Math.max(2, Math.floor(w / gap));
      for (let r = 0; r * per < list.length; r++) {
        const row = list.slice(r * per, r * per + per);
        const x0 = -(row.length - 1) * gap / 2;
        if (!first) y -= rowH;
        first = false; lastH = rowH;
        board(y, Math.max(w, per * gap) + 30);
        row.forEach((it, i) => {
          it.obj.position.set(x0 + i * gap, y, 0);
          it.tag.position.set(x0 + i * gap, y - 6, 35.3);
        });
      }
    }
    lay(films, FILM_GAP, FILM_ROW);
    lay(papers, PAPER_GAP, PAPER_ROW);
    // room above the top board for the title and below the last one for the hint, so neither
    // sits on the cartridges when the cabinet is scrolled to an end (large display sizes too)
    const perPx = viewH() / vh();
    const head = (document.getElementById('title').getBoundingClientRect().bottom || 60) + 14;
    const foot = vh() - (document.getElementById('hint').getBoundingClientRect().top || vh() - 30) + 10;
    top = 90 + head * perPx; bottom = y - lastH + 40 - foot * perPx;
    camY = clampY(camY);
    // the lamp's shadows cover the whole cabinet; drawn now, then left alone
    const h = top - bottom, cy = (top + bottom) / 2;
    key.position.set(-250, cy + 380, 420); key.target.position.set(0, cy, 0);
    Object.assign(key.shadow.camera, { left: -w / 2 - 80, right: w / 2 + 80, top: h / 2 + 200, bottom: -h / 2 - 200, near: 100, far: 2000 });
    key.shadow.camera.updateProjectionMatrix();
    renderer.shadowMap.needsUpdate = true;
    camera.aspect = vw() / vh(); camera.updateProjectionMatrix();
    aim(); wake();
  }
  function clampY(y) {
    const half = viewH() / 2;
    const hi = top - half, lo = bottom + half;     // camera centre limits
    return lo > hi ? (top + bottom) / 2 : Math.min(hi, Math.max(lo, y));
  }
  camY = 1e9;                                       // start at the top
  arrange();
  camY = clampY(1e9);

  // ------------------------------------------------------------------ touch: drag along, tap to open
  let drag = null, moved = 0, vy = 0;
  const el = renderer.domElement;
  el.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY }; moved = 0; vy = 0; });
  el.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dy = e.clientY - drag.y;
    moved += Math.abs(dy) + Math.abs(e.clientX - drag.x);
    const step = dy * viewH() / vh();               // mm per pixel at the boards
    camY = clampY(camY + step); vy = step;
    drag.x = e.clientX; drag.y = e.clientY;
    document.getElementById('hint').style.opacity = '0';
    document.getElementById('fadeb').style.opacity = '0';
    aim(); wake();
  });
  el.addEventListener('pointerup', (e) => { const was = drag; drag = null; if (was && moved < 8) pick(e); wake(); });
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
  const lifts = [];
  function pick(e) {
    ndc.set(e.clientX / vw() * 2 - 1, -(e.clientY / vh()) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    const hits = ray.intersectObjects(items.map((i) => i.obj), true);
    if (!hits.length) return;
    const o = hits[0].object;
    const it = items.find((i) => { let p = o; while (p) { if (p === i.obj) return true; p = p.parent; } return false; });
    if (!it) return;
    // lifted a little toward you, then opened on its own page
    lifts.push({ obj: it.obj, t0: performance.now(), y0: it.obj.position.y,
      done: () => { if (!bridge) return; if (it.kind === 'film') bridge.inspect(it.id); else bridge.inspectPaper(it.id); } });
    wake();
  }

  let ref = null;
  window.addEventListener('deviceorientation', (e) => {
    if (e.beta == null || e.gamma == null) return;
    if (!ref) ref = { b: e.beta, g: e.gamma };
    wantX = THREE.MathUtils.clamp((e.gamma - ref.g) / 25, -1, 1);
    wantY = THREE.MathUtils.clamp((e.beta - ref.b) / 25, -1, 1);
    ref.b += (e.beta - ref.b) * 0.01; ref.g += (e.gamma - ref.g) * 0.01;
    // a hand's tremor is not a lean: only a real tilt wakes the drawing
    if (Math.abs(wantX - leanX) > 0.02 || Math.abs(wantY - leanY) > 0.02) wake();
  });

  // ------------------------------------------------------------------ the loop: only while something moves
  function ratio(r) { if (renderer.getPixelRatio() !== r) { renderer.setPixelRatio(r); renderer.setSize(vw(), vh()); } }
  function wake() { if (!awake) { awake = true; clearTimeout(sharpen); requestAnimationFrame(frame); } }
  function frame() {
    let moving = false;
    // scrolling, a lift or the shelf still being stocked: the light 1.5x
    if (drag || Math.abs(vy) > 0.05 || lifts.length || next < data.films.length) ratio(LOW);
    if (!drag && Math.abs(vy) > 0.05) { camY = clampY(camY + vy); vy *= 0.92; moving = true; }
    for (let i = lifts.length - 1; i >= 0; i--) {
      const l = lifts[i]; const k = Math.min(1, (performance.now() - l.t0) / 380);
      l.obj.position.y = l.y0 + Math.sin(k * Math.PI) * 16;
      if (k >= 1) { l.obj.position.y = l.y0; lifts.splice(i, 1); if (l.done) l.done(); } else moving = true;
    }
    leanX += (wantX - leanX) * 0.1; leanY += (wantY - leanY) * 0.1;
    if (Math.abs(wantX - leanX) > 2e-3 || Math.abs(wantY - leanY) > 2e-3) moving = true;
    aim();
    renderer.render(scene, camera);
    if (moving || drag || next < data.films.length) requestAnimationFrame(frame);
    else {
      awake = false;
      // at rest: one sharp frame
      clearTimeout(sharpen);
      if (renderer.getPixelRatio() !== HIGH) sharpen = setTimeout(() => { if (!awake) { ratio(HIGH); renderer.render(scene, camera); } }, 150);
    }
  }
  window.addEventListener('resize', () => { renderer.setSize(vw(), vh()); arrange(); });
  if (!window.innerWidth || !window.innerHeight) {
    const poll = () => { if (window.innerWidth && window.innerHeight) window.dispatchEvent(new Event('resize')); else requestAnimationFrame(poll); };
    requestAnimationFrame(poll);
  }
  buildFilm();
  wake();
})();
