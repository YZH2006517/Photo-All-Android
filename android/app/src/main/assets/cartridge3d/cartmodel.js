// The 135 cartridge, modelled to measure (mm): CartridgeModel(THREE, renderer, look) builds it
// and returns its group, its spool, its film, and setLook / setLeader to change its label and
// how much film is out. Shared by the cartridge viewer and the camera back.
window.CartridgeModel = function (THREE, renderer, look) {
  'use strict';
  function rand(n) { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }
  function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }
  function luminance(hex) { const c = parseInt(hex.slice(1), 16); return ((c >> 16 & 255) * 0.2126 + (c >> 8 & 255) * 0.7152 + (c & 255) * 0.0722) / 255; }
  function fit(g, text, max) { const w = g.measureText(text).width; if (w > max) g.font = g.font.replace(/\d+px/, (m) => Math.floor(parseInt(m) * max / w) + 'px'); }
  function roundRect(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); g.fill(); }
  // ------------------------------------------------------------------ dimensions (mm)
  const R = 12.45;        // the shell's radius (24.9 mm across)
  const L = 38.6;         // the shell's length between the caps
  const CAP = 2.3;        // a cap's thickness, its rolled rim included
  const RIM = 13.05;      // the caps' rolled rim radius
  const SLOT = 0.30;      // the shell's open slot, radians, where the lips leave it
  const SLOT_AT = 1.85;   // where the slot is round the shell: right and a little behind (0 = facing us, +x = right)
  // the shell runs from one side of the slot round to the other; where its face (toward us) falls on the label
  const THETA0 = SLOT_AT + SLOT / 2, THETA_LEN = Math.PI * 2 - SLOT;
  const FACE_U = ((Math.PI * 2 - THETA0) % (Math.PI * 2)) / THETA_LEN;
  const FILM_W = 35.0;

  const model = new THREE.Group();

  // ------------------------------------------------------------------ textures drawn here
  function canvasTexture(w, h, draw, srgb) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c);
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    if (srgb) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  // the printed label, all the way round the shell (u = angle round, v = up)
  function labelTexture(look) { return canvasTexture(2048, 1024, (g, w, h) => {
    const mid = w * FACE_U; // the label's face: the side toward us at rest
    g.fillStyle = look.base; g.fillRect(0, 0, w, h);
    // the bands, top and foot
    g.fillStyle = look.band;
    g.fillRect(0, 0, w, h * 0.17); g.fillRect(0, h * 0.93, w, h * 0.07);
    // a hairline under the top band
    g.fillStyle = look.ink; g.globalAlpha = 0.55; g.fillRect(w * 0.04, h * 0.205, w * 0.92, 3); g.globalAlpha = 1;
    // the maker's name round the band
    const light = luminance(look.band) < 0.5;
    g.fillStyle = light ? '#f4f1e8' : '#161616';
    g.font = 'bold ' + Math.round(h * 0.085) + 'px sans-serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    for (let k = 0; k < 6; k++) g.fillText(look.maker, (k + 0.5) * w / 6, h * 0.088);
    // the face: stock name, ISO, the line beneath
    g.fillStyle = look.ink;
    g.font = 'bold ' + Math.round(h * 0.15) + 'px "Roboto Condensed", sans-serif-condensed, sans-serif';
    fit(g, look.title, w * 0.3); g.fillText(look.title, mid, h * 0.36);
    g.font = 'bold ' + Math.round(h * 0.3) + 'px "Roboto Condensed", sans-serif-condensed, sans-serif';
    g.fillText(look.iso, mid, h * 0.62);
    g.font = Math.round(h * 0.065) + 'px sans-serif';
    g.fillText('135 · 36 EXP', mid, h * 0.82);
    g.font = Math.round(h * 0.034) + 'px sans-serif'; g.textAlign = 'left';
    g.fillText('FOR COLOR PRINTS · PROCESS C-41 · ISO ' + look.iso, mid - w * 0.19, h * 0.9);
    // a bar code on one side of the face, the DX contacts (bare tin and black paint) on the other
    g.fillStyle = look.ink;
    let x = mid - w * 0.28, i = 0;
    const code = hash(look.name);
    while (x < mid - w * 0.215) { const bw = 3 + ((code >> (i % 13)) & 3) * 2; g.fillRect(x, h * 0.3, bw, h * 0.46); x += bw + 5; i++; }
    const cell = h * 0.07, dx0 = mid + w * 0.205, dy0 = h * 0.28;
    g.fillStyle = '#c6c4bf'; g.fillRect(dx0, dy0, cell * 2, cell * 6);
    g.fillStyle = '#141415';
    for (let k = 0; k < 12; k++) if ((code >> k) & 1) g.fillRect(dx0 + (k % 2) * cell, dy0 + Math.floor(k / 2) * cell, cell, cell);
    // the back: the maker's notes in small type, as on a real shell
    g.fillStyle = look.ink; g.globalAlpha = 0.75;
    g.font = Math.round(h * 0.04) + 'px sans-serif'; g.textAlign = 'center';
    g.fillText('DAYLIGHT LOADING · KEEP AWAY FROM HEAT', ((FACE_U + 0.5) % 1) * w, h * 0.46);
    g.fillText('MADE FOR 135 CAMERAS · DEVELOP BEFORE ' + (2028 + (code % 3)), ((FACE_U + 0.5) % 1) * w, h * 0.54);
    g.globalAlpha = 1;
    // wear: fine scuffs through the print where fingers hold it
    g.strokeStyle = 'rgba(255,255,255,0.22)'; g.lineWidth = 1.2;
    for (let k = 0; k < 40; k++) {
      const sx = rand(k) * w, sy = h * (0.2 + 0.7 * rand(k + 99));
      g.beginPath(); g.moveTo(sx, sy); g.lineTo(sx + 20 + rand(k + 7) * 60, sy + (rand(k + 3) - 0.5) * 8); g.stroke();
    }
  }, true); }
  let labelTex = labelTexture(look);

  // the varnish's roughness: mostly smooth, finer scratches and handling marks
  const varnishRough = canvasTexture(1024, 512, (g, w, h) => {
    g.fillStyle = '#5a5a5a'; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#8a8a8a';
    for (let k = 0; k < 160; k++) {
      g.lineWidth = 0.6 + rand(k) * 1.2;
      const sx = rand(k + 1) * w, sy = rand(k + 2) * h;
      g.beginPath(); g.moveTo(sx, sy); g.lineTo(sx + (rand(k + 3) - 0.3) * 80, sy + (rand(k + 4) - 0.5) * 12); g.stroke();
    }
    for (let k = 0; k < 9; k++) { // fingerprints: soft rougher patches
      const gr = g.createRadialGradient(rand(k + 50) * w, rand(k + 60) * h, 0, rand(k + 50) * w, rand(k + 60) * h, 60);
      gr.addColorStop(0, 'rgba(140,140,140,0.5)'); gr.addColorStop(1, 'rgba(140,140,140,0)');
      g.fillStyle = gr; g.fillRect(0, 0, w, h);
    }
  }, false);

  // spun steel: rings round the turning, a few scratches across them (v runs along the lathe profile)
  const steelRough = canvasTexture(512, 512, (g, w, h) => {
    for (let y = 0; y < h; y++) { const v = 90 + 50 * rand(y * 3.1); g.fillStyle = 'rgb(' + v + ',' + v + ',' + v + ')'; g.fillRect(0, y, w, 1); }
    g.strokeStyle = 'rgba(200,200,200,0.7)';
    for (let k = 0; k < 40; k++) { g.lineWidth = 0.6; const sx = rand(k + 200) * w, sy = rand(k + 300) * h; g.beginPath(); g.moveTo(sx, sy); g.lineTo(sx + 30 * rand(k), sy + 40 * (rand(k + 9) - 0.5)); g.stroke(); }
  }, false);

  // the film's leader: its tongue and its perforations as an alpha map (u along the film, v across)
  const FILM_MAX = 90; // mm of film that can come out
  const filmAlpha = canvasTexture(2048, 512, (g, w, h) => {
    const mm = w / FILM_MAX;            // pixels per mm along
    const mv = h / FILM_W;              // pixels per mm across
    g.fillStyle = '#000'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#fff';
    // the tongue: the first 60 mm cut to half width, with a rounded end and a sloped shoulder
    g.beginPath();
    g.moveTo(0, 0); g.lineTo(w, 0); g.lineTo(w, h);
    g.lineTo(62 * mm, h);
    g.quadraticCurveTo(56 * mm, h * 0.5 + 2, 50 * mm, h * 0.5);
    g.lineTo(4 * mm, h * 0.5);
    g.quadraticCurveTo(0, h * 0.5, 0, h * 0.35);
    g.closePath(); g.fill();
    // KS perforations: 1.98 x 2.794 mm, 4.75 mm pitch, 2.01 mm from each edge
    g.fillStyle = '#000';
    for (let s = 3; s < FILM_MAX; s += 4.75) {
      for (const edge of [2.01, FILM_W - 2.01 - 2.794]) {
        roundRect(g, s * mm, edge * mv, 1.98 * mm, 2.794 * mv, 0.5 * mm);
      }
    }
  }, false);

  // ------------------------------------------------------------------ materials
  const labelMat = new THREE.MeshPhysicalMaterial({
    map: labelTex, roughness: 0.42, roughnessMap: varnishRough, metalness: 0.0,
    clearcoat: 0.75, clearcoatRoughness: 0.18, envMapIntensity: 0.9,
  });
  // satin tinplate: bright, a broad soft sheen, both faces drawn (a turned profile has only one side)
  const steelMat = new THREE.MeshPhysicalMaterial({
    color: 0xd9d7d2, metalness: 0.92, roughness: 0.42, roughnessMap: steelRough, envMapIntensity: 1.6,
    side: THREE.DoubleSide,
  });
  const darkSteel = new THREE.MeshPhysicalMaterial({ color: 0x3a3936, metalness: 0.9, roughness: 0.45, envMapIntensity: 0.8 });
  const blackMat = new THREE.MeshStandardMaterial({ color: 0x0b0b0c, roughness: 0.9 });
  const plush = new THREE.MeshPhysicalMaterial({
    color: 0x0a0a0b, roughness: 1.0, sheen: 1.0, sheenRoughness: 0.75, sheenColor: new THREE.Color(0x3a3838),
  });
  const tinMat = new THREE.MeshPhysicalMaterial({ color: 0x8f8d88, metalness: 1.0, roughness: 0.4, envMapIntensity: 0.9 });
  const filmMat = new THREE.MeshPhysicalMaterial({
    color: 0x5a2a0c, roughness: 0.22, metalness: 0.0, clearcoat: 0.8, clearcoatRoughness: 0.1,
    alphaMap: filmAlpha, alphaTest: 0.5, transparent: true, opacity: 0.93, side: THREE.DoubleSide,
    sheen: 0.3, sheenColor: new THREE.Color(0xffb070),
  });
  filmAlpha.wrapS = filmAlpha.wrapT = THREE.ClampToEdgeWrapping;

  // ------------------------------------------------------------------ the shell, with its slot
  const shellGeo = new THREE.CylinderGeometry(R, R, L, 192, 1, true, THETA0, THETA_LEN);
  // the label's u should run round from the slot: remap so the face (u = 0.5) looks at us
  const shell = new THREE.Mesh(shellGeo, labelMat);
  shell.castShadow = true; shell.receiveShadow = true;
  model.add(shell);
  labelTex.wrapS = THREE.RepeatWrapping;
  labelTex.offset.x = 0.0;
  // the inside of the shell, dark, seen through the slot
  const inner = new THREE.Mesh(new THREE.CylinderGeometry(R - 0.25, R - 0.25, L, 96, 1, true), new THREE.MeshStandardMaterial({ color: 0x18171a, roughness: 0.8, side: THREE.BackSide }));
  model.add(inner);
  // the film wound inside, a dark roll just visible in the slot
  const roll = new THREE.Mesh(new THREE.CylinderGeometry(R - 1.4, R - 1.4, FILM_W, 64), new THREE.MeshStandardMaterial({ color: 0x2a160a, roughness: 0.5 }));
  model.add(roll);

  // ------------------------------------------------------------------ the end caps: a turned profile, rolled at the rim
  function capGeometry() {
    // (radius, height) from the spool's hole out to the rolled rim, then back over its top
    const pts = [
      [4.9, 0.0], [5.2, 0.35], [9.6, 0.35], [10.2, 0.6], [11.6, 0.6], [12.2, 0.35], [12.6, 0.0],
      [13.0, -0.25], [RIM, -0.9], [RIM, -1.7], [12.85, -2.15], [12.5, -2.3], [12.35, -2.0],
    ].map(([r, y]) => new THREE.Vector2(r, y));
    return new THREE.LatheGeometry(pts, 192);
  }
  // each cap: the turned profile, and a solid disc closing it so nothing shows through
  function cap(top) {
    const g = new THREE.Group();
    const ring = new THREE.Mesh(capGeometry(), steelMat);
    ring.castShadow = true; ring.receiveShadow = true;
    g.add(ring);
    const disc = new THREE.Mesh(new THREE.RingGeometry(4.9, 12.7, 128), steelMat);
    disc.rotation.x = -Math.PI / 2; disc.position.y = 0.3; disc.receiveShadow = true;
    g.add(disc);
    const under = new THREE.Mesh(new THREE.CircleGeometry(12.7, 128), new THREE.MeshStandardMaterial({ color: 0x1a1918, roughness: 0.8, side: THREE.DoubleSide }));
    under.rotation.x = -Math.PI / 2; under.position.y = -0.9;
    g.add(under);
    if (top) g.position.y = L / 2 + CAP - 0.35;
    else { g.rotation.x = Math.PI; g.position.y = -L / 2 - CAP + 0.35; }
    model.add(g);
  }
  cap(true);
  cap(false);

  // ------------------------------------------------------------------ the spool: knurled knob on top, a stub below
  const spool = new THREE.Group();
  model.add(spool);
  (function knob() {
    const h = 6.2, r = 4.75;
    const geo = new THREE.CylinderGeometry(r, r, h, 144, 6, true);
    const pos = geo.attributes.position;
    for (let i = 0; i < pos.count; i++) { // the knurl: 30 ridges round it
      const x = pos.getX(i), z = pos.getZ(i);
      const a = Math.atan2(z, x);
      const k = 1 + 0.03 * Math.max(0, Math.cos(a * 30));
      pos.setX(i, x * k); pos.setZ(i, z * k);
    }
    geo.computeVertexNormals();
    const side = new THREE.Mesh(geo, darkSteel);
    side.position.y = L / 2 + CAP + h / 2 - 0.2; side.castShadow = true;
    spool.add(side);
    // its open end: a rim, a dark bore, the fork across it
    const rimGeo = new THREE.RingGeometry(3.3, r * 1.03, 96);
    const rim = new THREE.Mesh(rimGeo, steelMat);
    rim.rotation.x = -Math.PI / 2; rim.position.y = L / 2 + CAP + h - 0.2;
    spool.add(rim);
    const bore = new THREE.Mesh(new THREE.CylinderGeometry(3.3, 3.3, 4, 64, 1, true), new THREE.MeshStandardMaterial({ color: 0x0c0c0d, roughness: 0.9, side: THREE.BackSide }));
    bore.position.y = L / 2 + CAP + h - 2.2;
    spool.add(bore);
    const floor = new THREE.Mesh(new THREE.CircleGeometry(3.3, 48), blackMat);
    floor.rotation.x = -Math.PI / 2; floor.position.y = L / 2 + CAP + h - 4;
    spool.add(floor);
    const fork = new THREE.Mesh(new THREE.BoxGeometry(6.4, 2.6, 1.0), darkSteel);
    fork.position.y = L / 2 + CAP + h - 2.0;
    spool.add(fork);
  })();
  const stub = new THREE.Mesh(new THREE.CylinderGeometry(3.6, 3.6, 2.8, 64), darkSteel);
  stub.position.y = -L / 2 - CAP - 1.2; stub.castShadow = true;
  spool.add(stub);

  // ------------------------------------------------------------------ the light trap: two lips, plush inside
  // the slot's own frame: n out of the shell, t along it (toward the front), y up
  const n = new THREE.Vector3(Math.sin(SLOT_AT), 0, Math.cos(SLOT_AT));
  const t = new THREE.Vector3(Math.cos(SLOT_AT), 0, -Math.sin(SLOT_AT)).negate();
  const Y = new THREE.Vector3(0, 1, 0);
  // the lips leave the shell leaning forward, so the film comes out toward the front right
  const exitDir = n.clone().multiplyScalar(Math.cos(0.75)).addScaledVector(t, Math.sin(0.75)).normalize();
  const across = new THREE.Vector3().crossVectors(Y, exitDir).normalize();
  const mouth = n.clone().multiplyScalar(R - 0.4);
  const LIP = 6.0, GAP = 1.5, TH = 0.3;
  const lips = new THREE.Group();
  model.add(lips);
  const basis = new THREE.Matrix4().makeBasis(exitDir, Y, across);
  for (const side of [-1, 1]) {
    const plate = new THREE.Mesh(new THREE.BoxGeometry(LIP, L - 0.8, TH), tinMat);
    plate.quaternion.setFromRotationMatrix(basis);
    plate.position.copy(mouth).addScaledVector(exitDir, LIP / 2).addScaledVector(across, side * (GAP / 2 + TH / 2));
    plate.castShadow = true;
    lips.add(plate);
    // the plush on its inner face
    const pile = new THREE.Mesh(new THREE.BoxGeometry(LIP - 0.4, L - 1.6, 0.5), plush);
    pile.quaternion.copy(plate.quaternion);
    pile.position.copy(mouth).addScaledVector(exitDir, LIP / 2 + 0.1).addScaledVector(across, side * (GAP / 2 - 0.12));
    lips.add(pile);
    // the lip's rolled outer edge
    const roll = new THREE.Mesh(new THREE.CylinderGeometry(TH * 0.9, TH * 0.9, L - 0.8, 16), tinMat);
    roll.position.copy(mouth).addScaledVector(exitDir, LIP).addScaledVector(across, side * (GAP / 2 + TH / 2));
    lips.add(roll);
  }

  // ------------------------------------------------------------------ the film coming out between the lips
  const FILM_SEG = 90;
  const filmGeo = new THREE.PlaneGeometry(1, FILM_W, FILM_SEG, 1);
  const film = new THREE.Mesh(filmGeo, filmMat);
  film.castShadow = true;
  film.customDepthMaterial = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking, alphaMap: filmAlpha, alphaTest: 0.5 });
  model.add(film);
  let filmOut = 24, filmTarget = 24;
  const tmpP = new THREE.Vector3(), tmpD = new THREE.Vector3();
  function shapeFilm() {
    const pos = filmGeo.attributes.position, uv = filmGeo.attributes.uv;
    // walk along the film from just inside the mouth, the direction curling gently as it goes
    const pts = [];
    tmpP.copy(mouth).addScaledVector(exitDir, 0.6);
    tmpD.copy(exitDir);
    const ds = filmOut / FILM_SEG;
    for (let k = 0; k <= FILM_SEG; k++) {
      pts.push(tmpP.clone());
      const s = k * ds;
      const bend = (s > LIP ? 0.0045 : 0) * ds; // free of the lips it begins to curl toward us
      tmpD.applyAxisAngle(Y, -bend).normalize();
      tmpP.addScaledVector(tmpD, ds);
    }
    for (let i = 0; i < pos.count; i++) {
      const col = i % (FILM_SEG + 1);
      const row = Math.floor(i / (FILM_SEG + 1)); // 0 top edge, 1 bottom edge
      const p = pts[col];
      pos.setXYZ(i, p.x, row === 0 ? FILM_W / 2 : -FILM_W / 2, p.z);
      uv.setXY(i, (filmOut - col * ds) / FILM_MAX, row === 0 ? 1 : 0);
    }
    pos.needsUpdate = true; uv.needsUpdate = true;
    filmGeo.computeVertexNormals();
    filmGeo.computeBoundingSphere();
    spool.rotation.y = -filmOut / 4.6; // the spool turns as the film leaves it
  }
  shapeFilm();


  return {
    group: model, spool: spool, film: film, R: R, L: L, CAP: CAP, FILM_W: FILM_W, FILM_MAX: FILM_MAX,
    mouth: mouth, exitDir: exitDir,
    /** How much film is out of the lips, mm (0 hides it). */
    setLeader: function (mm) { filmOut = filmTarget = mm; film.visible = mm > 0.5; if (film.visible) shapeFilm(); else spool.rotation.y = 0; },
    /** Another stock's label. */
    setLook: function (l) {
      look = l; labelTex.dispose(); labelTex = labelTexture(look); labelTex.wrapS = THREE.RepeatWrapping;
      labelMat.map = labelTex; labelMat.needsUpdate = true;
    },
  };
};
