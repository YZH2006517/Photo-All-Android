// A 135 film cartridge, modelled to measure (millimetres) and rendered physically.
// Everything is built here from its real parts: the tinplate shell with its slot, the
// printed label under varnish, the crimped steel end caps with their turned faces, the
// spool's knurled knob with the fork inside it, the bottom stub, the light trap's lips
// lined with black plush, and the film's leader with its tongue and KS perforations.
// One finger turns it all the way round, two fingers bring it nearer, a double tap draws
// the film out (the spool turns as it goes) and back.
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  // still mode: no page, no touch; the app asks for pictures of a cartridge (renderStill below)
  const STILL = q.get('mode') === 'still';
  let look = {
    name: q.get('name') || 'Kodak Portra 400',
    maker: (q.get('maker') || 'KODAK').toUpperCase(),
    title: (q.get('title') || 'PORTRA').toUpperCase(),
    iso: q.get('iso') || '400',
    base: '#' + (q.get('base') || 'e9dfc6'),
    band: '#' + (q.get('band') || '5d4a8f'),
    ink: '#' + (q.get('ink') || '1c1a1a'),
  };
  if (STILL) for (const id of ['name', 'pull', 'hint']) document.getElementById(id).style.display = 'none';
  else document.getElementById('name').innerHTML = look.name + '<small>135 · 36 EXP · ISO ' + look.iso + '</small>';
  if (STILL) { document.body.style.background = 'transparent'; document.documentElement.style.background = 'transparent'; }

  // ------------------------------------------------------------------ renderer and scene
  // the page's size: a WebView may run this before it is laid out (0 x 0), which would put
  // the camera at NaN and leave the screen blank for good
  function vw() { return window.innerWidth || document.documentElement.clientWidth || screen.width || 1; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight || screen.height || 1; }
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance', preserveDrawingBuffer: STILL });
  if (STILL) renderer.setClearColor(0x000000, 0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(vw(), vh());
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  document.body.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(28, vw() / vh(), 1, 4000);
  // far enough that the cartridge and its film fit across the screen, whichever way it is held
  function fitDistance() {
    const halfV = Math.tan(THREE.MathUtils.degToRad(14));
    const aspect = vw() / vh();
    return Math.max(34 / halfV, 40 / (halfV * aspect));
  }
  let base = fitDistance();
  let distance = base;
  camera.position.set(0, 4, distance); // eye level: straight on, not from above
  camera.lookAt(0, 2, 0);

  // ------------------------------------------------------------------ light: a room to reflect, a lamp to cast shadows
  function roomEnvironment() {
    // a soft studio: a dim box with a few bright panels, prefiltered for PBR reflections
    const room = new THREE.Scene();
    const box = new THREE.Mesh(new THREE.BoxGeometry(12, 8, 12), new THREE.MeshBasicMaterial({ color: 0x2a2826, side: THREE.BackSide }));
    room.add(box);
    function panel(w, h, x, y, z, ry, rx, intensity, color) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(intensity) }));
      m.position.set(x, y, z); m.rotation.set(rx || 0, ry || 0, 0); room.add(m);
    }
    panel(5, 2.5, -3.5, 2.5, 3, Math.PI * 0.75, -0.3, 6, 0xfff3e2);  // key softbox, upper left front
    panel(3, 5, 5.9, 0.5, 0, -Math.PI / 2, 0, 2.2, 0xdde8ff);         // cool strip on the right
    panel(10, 2, 0, 3.9, 0, 0, Math.PI / 2, 1.5, 0xffffff);           // ceiling bounce
    panel(4, 1.2, 0, -1, 5.9, Math.PI, 0, 0.9, 0xffe6c8);             // warm card low in front
    const pm = new THREE.PMREMGenerator(renderer);
    const env = pm.fromScene(room, 0.03).texture;
    pm.dispose();
    return env;
  }
  scene.environment = roomEnvironment();

  const key = new THREE.DirectionalLight(0xfff2e0, 2.2);
  key.position.set(-60, 90, 70);
  key.castShadow = true;
  key.shadow.mapSize.set(1024, 1024);
  key.shadow.camera.left = -60; key.shadow.camera.right = 60; key.shadow.camera.top = 60; key.shadow.camera.bottom = -60;
  key.shadow.camera.near = 20; key.shadow.camera.far = 300;
  key.shadow.radius = 6;
  key.shadow.bias = -0.0006;
  scene.add(key);
  scene.add(new THREE.HemisphereLight(0xfff6ea, 0x3a332c, 0.35));

  // ------------------------------------------------------------------ the cartridge itself (cartmodel.js)
  const cart = CartridgeModel(THREE, renderer, look);
  const model = cart.group;
  scene.add(model);
  const R = cart.R, L = cart.L, CAP = cart.CAP, FILM_MAX = cart.FILM_MAX;
  const film = cart.film, spool = cart.spool;
  let filmOut = 24, filmTarget = 24;
  function shapeFilm() { cart.setLeader(filmOut); }
  shapeFilm();

  // ------------------------------------------------------------------ the ground and its shadows
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(600, 600), new THREE.ShadowMaterial({ opacity: 0.38 }));
  ground.rotation.x = -Math.PI / 2; ground.position.y = -L / 2 - CAP - 2.6; ground.receiveShadow = true;
  scene.add(ground);
  const contact = new THREE.Mesh(new THREE.PlaneGeometry(46, 46), new THREE.MeshBasicMaterial({
    map: canvasTexture(256, 256, (g, w, h) => {
      const gr = g.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, w / 2);
      gr.addColorStop(0, 'rgba(0,0,0,0.75)'); gr.addColorStop(0.45, 'rgba(0,0,0,0.35)'); gr.addColorStop(1, 'rgba(0,0,0,0)');
      g.fillStyle = gr; g.fillRect(0, 0, w, h);
    }, false), transparent: true, depthWrite: false,
  }));
  contact.rotation.x = -Math.PI / 2; contact.position.y = ground.position.y + 0.05;
  scene.add(contact);

  // the whole thing stands a little higher in the frame, its face to us
  model.rotation.y = 0;

  // ------------------------------------------------------------------ still mode: pictures for the app
  if (STILL) {
    ground.visible = false; contact.visible = false;
    // the frame: the cartridge's foot at 93 % down, its top at 13 %, its axis at 36 % across
    const TOP = L / 2 + CAP + 6.0, FOOT = -L / 2 - CAP - 2.6;
    const H_MODEL = TOP - FOOT;
    const viewH = H_MODEL / 0.8;
    const bottom = FOOT - viewH * 0.07;
    const ASPECT = 0.95;
    const ortho = new THREE.OrthographicCamera(-0.36 * viewH * ASPECT, 0.64 * viewH * ASPECT, bottom + viewH, bottom, 1, 1000);
    ortho.position.set(0, 0, 300);
    ortho.lookAt(0, 0, 0);
    // the ortho view cuts the frame at y = bottom .. bottom + viewH, centred on x = 0 (the axis)
    window.renderStill = function (id, json) {
      try {
        const o = JSON.parse(json);
        const next = {
          name: o.name || look.name, maker: (o.maker || look.maker).toUpperCase(), title: (o.title || look.title).toUpperCase(),
          iso: o.iso || look.iso, base: '#' + o.base, band: '#' + o.band, ink: '#' + o.ink,
        };
        if (JSON.stringify(next) !== JSON.stringify(look)) { look = next; cart.setLook(look); }
        const out = Math.max(0, o.leader || 0);
        filmOut = filmTarget = out; cart.setLeader(out);
        model.rotation.set(o.pitch || 0, o.yaw || 0, 0);
        const h = Math.max(32, Math.min(2048, o.h | 0));
        const w = Math.round(h * ASPECT);
        renderer.setPixelRatio(1);
        renderer.setSize(w, h, false);
        renderer.render(scene, ortho);
        const url = renderer.domElement.toDataURL('image/png');
        if (window.AndroidCart) window.AndroidCart.done(id, url);
      } catch (err) {
        if (window.AndroidCart) window.AndroidCart.done(id, '');
      }
    };
    if (window.AndroidCart) window.AndroidCart.ready();
    return;
  }

  // ------------------------------------------------------------------ touch: turn, zoom, draw the film
  let yaw = 0, pitch = 0, vyaw = 0, vpitch = 0;
  let dragging = false, lx = 0, ly = 0, lt = 0;
  let pinch0 = 0, dist0 = distance;
  let lastTap = 0;
  const el = renderer.domElement;
  el.addEventListener('touchstart', (e) => {
    if (e.touches.length === 1) {
      dragging = true; lx = e.touches[0].clientX; ly = e.touches[0].clientY; lt = performance.now(); vyaw = vpitch = 0;
      const now = performance.now();
      if (now - lastTap < 300) togglePull();
      lastTap = now;
    } else if (e.touches.length === 2) {
      dragging = false;
      pinch0 = spread(e.touches); dist0 = distance;
    }
    hideHint(); wake();
  }, { passive: true });
  el.addEventListener('touchmove', (e) => {
    if (e.touches.length === 1 && dragging) {
      const x = e.touches[0].clientX, y = e.touches[0].clientY, t = performance.now();
      const dx = (x - lx) / el.clientWidth, dy = (y - ly) / el.clientHeight;
      yaw += dx * Math.PI * 2.2; pitch = clamp(pitch + dy * Math.PI * 0.9, -0.55, 0.75);
      const dt = Math.max(8, t - lt);
      vyaw = dx * Math.PI * 2.2 / dt * 16; vpitch = dy * Math.PI * 0.9 / dt * 16;
      lx = x; ly = y; lt = t;
    } else if (e.touches.length === 2 && pinch0 > 0) {
      distance = clamp(dist0 * pinch0 / spread(e.touches), base * 0.45, base * 1.5);
    }
    wake();
  }, { passive: true });
  el.addEventListener('touchend', (e) => { if (e.touches.length === 0) dragging = false; wake(); }, { passive: true });
  document.getElementById('pull').addEventListener('click', () => { togglePull(); wake(); });

  function togglePull() {
    filmTarget = filmTarget < 50 ? FILM_MAX - 4 : 24;
    document.getElementById('pull').textContent = filmTarget > 50 ? '收回胶片' : '抽出胶片';
  }

  // ------------------------------------------------------------------ the loop: draws only while something moves
  let awake = false;
  function wake() { if (!awake) { awake = true; requestAnimationFrame(frame); } }
  function frame() {
    let moving = false;
    if (!dragging) {
      yaw += vyaw; pitch = clamp(pitch + vpitch, -0.55, 0.75);
      vyaw *= 0.94; vpitch *= 0.9;
      // with nothing holding it, the tilt eases back to eye level
      pitch *= 0.96;
      if (Math.abs(vyaw) > 1e-4 || Math.abs(vpitch) > 1e-4 || Math.abs(pitch) > 1e-3) moving = true;
    } else moving = true;
    if (Math.abs(filmTarget - filmOut) > 0.05) {
      filmOut += (filmTarget - filmOut) * 0.08;
      shapeFilm();
      moving = true;
    }
    model.rotation.set(pitch, yaw, 0);
    const cd = camera.position.length();
    if (Math.abs(cd - distance) > 0.1) moving = true;
    const d = cd + (distance - cd) * 0.25;
    camera.position.set(0, 4 * d / base, d);
    camera.lookAt(0, 2, 0);
    renderer.render(scene, camera);
    if (moving) requestAnimationFrame(frame); else awake = false;
  }

  window.addEventListener('resize', () => {
    camera.aspect = vw() / vh(); camera.updateProjectionMatrix();
    const k = Number.isFinite(distance / base) && base > 0 ? distance / base : 1;
    base = fitDistance(); distance = base * k;
    renderer.setSize(vw(), vh()); wake();
  });
  let hintGone = false;
  function hideHint() { if (!hintGone) { hintGone = true; document.getElementById('hint').style.opacity = '0'; } }
  // a lost GL context (too many at once, the GPU reset) comes back as a fresh page
  renderer.domElement.addEventListener('webglcontextlost', (e) => { e.preventDefault(); });
  renderer.domElement.addEventListener('webglcontextrestored', () => location.reload());
  // laid out late: size again once there is a size
  if (!window.innerWidth || !window.innerHeight) {
    const poll = () => { if (window.innerWidth && window.innerHeight) window.dispatchEvent(new Event('resize')); else requestAnimationFrame(poll); };
    requestAnimationFrame(poll);
  }
  // a slow quarter turn on arrival, to show it is a solid, then still
  vyaw = 0.045;
  wake();

  // ------------------------------------------------------------------ helpers
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  // a texture drawn on a canvas (the model has its own copy in cartmodel.js)
  function canvasTexture(w, h, draw, srgb) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c);
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    if (srgb) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }
  function spread(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }
  function rand(n) { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }
  function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }
  function luminance(hex) { const c = parseInt(hex.slice(1), 16); return ((c >> 16 & 255) * 0.2126 + (c >> 8 & 255) * 0.7152 + (c & 255) * 0.0722) / 255; }
  function fit(g, text, max) { const w = g.measureText(text).width; if (w > max) g.font = g.font.replace(/\d+px/, (m) => Math.floor(parseInt(m) * max / w) + 'px'); }
  function roundRect(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); g.fill(); }
})();
