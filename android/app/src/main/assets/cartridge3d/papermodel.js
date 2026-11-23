// Photographic paper as it comes (mm): PaperModel(THREE, renderer, id) builds a two-piece box of
// 3.5 x 5 in sheets, its lid printed in the maker's dress (Kodak Professional's yellow and black, a
// band in the paper's own colour; Fujifilm's green), or for the motion-picture print films a steel
// can with a strip of taped label. Returns {group, width, height, kind, open(t)}.
window.PaperModel = function (THREE, renderer, id) {
  'use strict';
  const n = (id || '').toLowerCase();
  const group = new THREE.Group();

  function canvasTexture(w, h, draw) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    return t;
  }
  function fit(g, text, max) { const w = g.measureText(text).width; if (w > max) g.font = g.font.replace(/(\d+)px/, (m, px) => Math.floor(px * max / w) + 'px'); }
  function grain(g, w, h, a) {        // card stock: a faint fibre
    for (let i = 0; i < w * h / 60; i++) {
      g.fillStyle = Math.random() < 0.5 ? 'rgba(0,0,0,' + a + ')' : 'rgba(255,255,255,' + a + ')';
      g.fillRect(Math.random() * w, Math.random() * h, 1, 1);
    }
  }

  // ------------------------------------------------------------------ the print films: a steel can
  const can = /2383|2393|2302/.test(n);
  if (can) {
    const R = 52, T = 15;
    const steel = new THREE.MeshPhysicalMaterial({ color: 0x9a9b97, metalness: 0.95, roughness: 0.32, clearcoat: 0.3 });
    const body = new THREE.Mesh(new THREE.CylinderGeometry(R, R, T, 96), steel);
    body.rotation.x = Math.PI / 2; group.add(body);
    for (const z of [T / 2 - 1.2, -T / 2 + 1.2]) {           // the rolled rims
      const rim = new THREE.Mesh(new THREE.TorusGeometry(R - 0.2, 1.1, 12, 96), steel);
      rim.position.z = z; group.add(rim);
    }
    const ring = new THREE.Mesh(new THREE.TorusGeometry(R * 0.62, 0.7, 10, 96), steel);  // a pressed ring in the lid
    ring.position.z = T / 2; group.add(ring);
    const stock = (n.match(/\d{4}/) || ['2383'])[0];
    const what = stock === '2302' ? 'B&W PRINT FILM' : stock === '2393' ? 'VISION PREMIER PRINT' : 'VISION COLOR PRINT FILM';
    const tape = canvasTexture(1024, 256, (g, w, h) => {
      g.fillStyle = '#e9c63a'; g.fillRect(0, 0, w, h);
      grain(g, w, h, 0.06);
      g.fillStyle = '#181512';
      g.font = 'bold 92px sans-serif'; g.textBaseline = 'middle';
      g.fillText('EASTMAN ' + stock, 40, h * 0.36);
      g.font = '600 46px sans-serif'; fit(g, what + '  ·  35 mm × 305 m', w - 80);
      g.fillText(what + '  ·  35 mm × 305 m', 40, h * 0.75);
      g.strokeStyle = 'rgba(0,0,0,0.25)'; g.lineWidth = 6; g.strokeRect(3, 3, w - 6, h - 6);
    });
    const label = new THREE.Mesh(new THREE.PlaneGeometry(R * 1.55, R * 0.39),
      new THREE.MeshStandardMaterial({ map: tape, roughness: 0.55 }));
    label.position.z = T / 2 + 0.25; label.rotation.z = -0.06; group.add(label);
    group.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    return { group, width: R * 2, height: R * 2, kind: 'can', open: (t) => { group.rotation.z = -t * 0.6; } };
  }

  // ------------------------------------------------------------------ the papers: a two-piece box
  const W = 89, H = 127, D = 22, LID = 9;
  const fuji = /fuji|crystal/.test(n);
  const name = fuji ? 'CRYSTAL ARCHIVE' :
    /premier/.test(n) ? 'ENDURA PREMIER' : /portra/.test(n) ? 'PORTRA ENDURA' : /supra/.test(n) ? 'SUPRA ENDURA' :
    /ultra/.test(n) ? 'ULTRA ENDURA' : /edge/.test(n) ? 'EKTACOLOR EDGE' : n.toUpperCase();
  const band = fuji ? '#f2f0e8' : /portra/.test(n) ? '#5d4a8f' : /supra/.test(n) ? '#1f4fa8' : /ultra/.test(n) ? '#b9262b' :
    /edge/.test(n) ? '#e07a1f' : '#161616';
  const face = fuji ? '#1f7a45' : '#f4f1e8';
  const side = fuji ? '#1a6a3c' : '#e8b21f';
  const ink = fuji ? '#f6f4ec' : '#151515';

  const lidFace = canvasTexture(512, Math.round(512 * H / W), (g, w, h) => {
    g.fillStyle = face; g.fillRect(0, 0, w, h);
    grain(g, w, h, 0.04);
    if (fuji) {
      g.fillStyle = '#f2f0e8'; g.fillRect(0, h * 0.08, w, h * 0.12);
      g.fillStyle = '#1f7a45'; g.font = 'bold 54px sans-serif'; g.textBaseline = 'middle';
      g.fillText('FUJIFILM', w * 0.08, h * 0.14);
      g.fillStyle = ink; g.font = '600 30px sans-serif'; g.fillText('FUJICOLOR', w * 0.08, h * 0.32);
      g.font = 'bold 58px sans-serif'; fit(g, 'CRYSTAL', w * 0.84); g.fillText('CRYSTAL', w * 0.08, h * 0.42);
      g.font = 'bold 58px sans-serif'; fit(g, 'ARCHIVE', w * 0.84); g.fillText('ARCHIVE', w * 0.08, h * 0.51);
      g.font = '600 28px sans-serif'; g.fillText('PAPER  TYPE II', w * 0.08, h * 0.6);
    } else {
      // Kodak Professional's dress: a yellow head, a black rule, the name, the paper's colour band
      g.fillStyle = '#e8b21f'; g.fillRect(0, 0, w, h * 0.2);
      g.fillStyle = '#151515'; g.fillRect(0, h * 0.2, w, h * 0.012);
      g.fillStyle = '#151515'; g.font = 'bold 50px sans-serif'; g.textBaseline = 'middle';
      g.fillText('KODAK', w * 0.08, h * 0.075);
      g.font = '600 26px sans-serif'; g.fillText('PROFESSIONAL', w * 0.08, h * 0.145);
      g.fillStyle = band; g.fillRect(0, h * 0.26, w * 0.06, h * 0.36);
      g.fillStyle = ink; g.font = 'bold 54px sans-serif';
      const words = name.split(' ');
      words.forEach((wd, i) => { g.font = 'bold 56px sans-serif'; fit(g, wd, w * 0.8); g.fillText(wd, w * 0.12, h * (0.33 + i * 0.09)); });
      g.font = '600 26px sans-serif'; g.fillText('COLOR NEGATIVE PAPER', w * 0.12, h * 0.55);
    }
    g.fillStyle = fuji ? 'rgba(246,244,236,0.85)' : 'rgba(21,21,21,0.75)';
    g.font = '500 24px sans-serif';
    g.fillText('8.9 × 12.7 cm  ·  3.5 × 5 in', w * 0.08, h * 0.82);
    g.fillText('100 SHEETS  ·  LUSTRE  E', w * 0.08, h * 0.87);
    g.font = '500 18px sans-serif';
    g.fillText('HANDLE IN TOTAL DARKNESS', w * 0.08, h * 0.93);
  });
  const sideMat = new THREE.MeshPhysicalMaterial({ color: new THREE.Color(side), roughness: 0.55, clearcoat: 0.25, clearcoatRoughness: 0.4 });
  const baseMat = new THREE.MeshPhysicalMaterial({ color: new THREE.Color(fuji ? '#174f30' : '#2a2724'), roughness: 0.7 });
  const faceMat = new THREE.MeshPhysicalMaterial({ map: lidFace, roughness: 0.5, clearcoat: 0.35, clearcoatRoughness: 0.35 });

  const base = new THREE.Mesh(new THREE.BoxGeometry(W - 1.2, H - 1.2, D), baseMat);
  group.add(base);
  const lid = new THREE.Group();
  const lidBox = new THREE.Mesh(new THREE.BoxGeometry(W, H, LID), [sideMat, sideMat, sideMat, sideMat, faceMat, sideMat]);
  lid.add(lidBox);
  lid.position.z = D / 2 - LID / 2 + 0.8;
  group.add(lid);
  group.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  const z0 = lid.position.z;
  return {
    group, width: W, height: H, kind: 'box',
    /** The lid lifted off by [t] 0..1: forward, then tipped back. */
    open: (t) => { lid.position.z = z0 + 26 * t; lid.position.y = 14 * t * t; lid.rotation.x = -0.35 * t; },
  };
};
