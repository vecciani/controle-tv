const $ = (id) => document.getElementById(id);

const store = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch {} },
  remove(key) { try { localStorage.removeItem(key); } catch {} },
};

let token = store.get('token');
const native = window.ControleNative || null;
const PHONE_APP_URL = 'https://github.com/vecciani/controle-tv/releases/latest/download/controle-tv-celular.apk';
const state = {
  apps: [], favorites: [], running: [], status: null,
  tab: 'home', appsMode: 'all', editingFavs: false,
};

// ---------- API ----------

async function api(path, body) {
  const res = await fetch(path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Token': token || '' },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) { token = null; store.remove('token'); connect(); throw new Error('Reconectando…'); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Erro');
  return data;
}

const iconUrl = (pkg) => `/api/icon?pkg=${encodeURIComponent(pkg)}&token=${encodeURIComponent(token)}`;
const buzz = (ms = 10) => navigator.vibrate?.(ms);

function toast(message, bad = false) {
  const el = $('toast');
  el.textContent = message;
  el.classList.toggle('bad', bad);
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 2200);
}

// ---------- Pairing ----------

function logout() {
  token = null;
  store.remove('token');
  native?.setToken(null);
  $('app').hidden = true;
  $('pair').hidden = false;
}

async function pair(pin, { silent = false } = {}) {
  $('pair-error').textContent = '';
  try {
    token = (await api('/api/pair', { pin })).token;
    store.set('token', token);
    history.replaceState(null, '', '/' + location.hash);
    start();
    return true;
  } catch (e) {
    if (!silent) $('pair-error').textContent = e.message;
    return false;
  }
}

// Connects straight away when the TV doesn't require a PIN
async function connect() {
  if (!(await pair('', { silent: true }))) logout();
}

$('pair-form').addEventListener('submit', (e) => {
  e.preventDefault();
  pair($('pin').value.trim());
});

// ---------- Keys ----------
// Sent one request at a time so arrows arrive in order; repeats of the same key are merged.

const keyQueue = [];
let keyBusy = false;

function sendKey(key, times = 1) {
  buzz();
  const last = keyQueue[keyQueue.length - 1];
  if (last && last.key === key) last.times += times;
  else keyQueue.push({ key, times });
  pumpKeys();
}

async function pumpKeys() {
  if (keyBusy) return;
  keyBusy = true;
  while (keyQueue.length) {
    const { key, times } = keyQueue.shift();
    try {
      await api('/api/key', { key, times });
    } catch (e) {
      toast(e.message, true);
      keyQueue.length = 0;
    }
  }
  keyBusy = false;
}

// Buttons with data-key; data-repeat ones keep firing while held.
let repeatTimer = null;
const stopRepeat = () => { clearTimeout(repeatTimer); clearInterval(repeatTimer); repeatTimer = null; };

document.addEventListener('pointerdown', (e) => {
  const btn = e.target.closest('[data-key][data-repeat]');
  if (!btn) return;
  sendKey(btn.dataset.key);
  repeatTimer = setTimeout(() => {
    repeatTimer = setInterval(() => sendKey(btn.dataset.key), 140);
  }, 420);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach((type) => document.addEventListener(type, stopRepeat));

document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-key]:not([data-repeat])');
  if (btn) sendKey(btn.dataset.key);
});

// ---------- Mouse ----------
// One finger moves the TV pointer (with acceleration), a tap clicks, holding long-clicks,
// two fingers scroll. Movement is sent as a fraction of the pad width, batched every frame.

const pad = $('touchpad');
let padMode = 'mouse';
const PAD_HINTS = {
  mouse: 'Arraste para mover · toque para clicar · segure para clique longo · 2 dedos para rolar',
};
const SPEED = 1.3;
const TV_ASPECT = 16 / 9;
const fingers = new Map();
let gesture = null;
let pendingMove = { x: 0, y: 0 };
let pendingScroll = { x: 0, y: 0 };
let mouseBusy = false;
let scrollBusy = false;

async function flushMouse() {
  if (!mouseBusy && (pendingMove.x || pendingMove.y)) {
    const move = pendingMove;
    pendingMove = { x: 0, y: 0 };
    mouseBusy = true;
    try { await api('/api/mouse/move', move); } catch (e) { toast(e.message, true); }
    mouseBusy = false;
  }
  if (!scrollBusy && Math.hypot(pendingScroll.x, pendingScroll.y) > 0.03) {
    const { x, y } = pendingScroll;
    pendingScroll = { x: 0, y: 0 };
    scrollBusy = true;
    try { await api('/api/mouse/scroll', { x, y }); } catch (e) { toast(e.message, true); }
    scrollBusy = false;
  }
}
setInterval(flushMouse, 33);

function mouseClick(long) {
  buzz(long ? 35 : 12);
  pad.classList.add('flash');
  setTimeout(() => pad.classList.remove('flash'), 150);
  api('/api/mouse/click', { long }).catch((e) => toast(e.message, true));
}

// No context menu or text selection on the pad: a long hold is a gesture here
pad.addEventListener('contextmenu', (e) => e.preventDefault());
pad.addEventListener('selectstart', (e) => e.preventDefault());

pad.addEventListener('pointerdown', (e) => {
  pad.setPointerCapture(e.pointerId);
  const box = pad.getBoundingClientRect();
  const dot = document.createElement('span');
  dot.className = 'touch-dot';
  pad.append(dot);
  fingers.set(e.pointerId, { x: e.clientX, y: e.clientY, t: performance.now(), dot, box });
  moveDot(fingers.get(e.pointerId), e);
  if (fingers.size === 1) {
    gesture = { x0: e.clientX, y0: e.clientY, t0: Date.now(), moved: false, multi: false, held: false };
    gesture.hold = setTimeout(() => {
      if (gesture && !gesture.moved && !gesture.multi) { gesture.held = true; mouseClick(true); }
    }, 600);
  } else if (gesture) {
    gesture.multi = true;
    clearTimeout(gesture.hold);
  }
});

pad.addEventListener('pointermove', (e) => {
  const f = fingers.get(e.pointerId);
  if (!f || !gesture) return;
  moveDot(f, e);
  const now = performance.now();
  const dx = e.clientX - f.x;
  const dy = e.clientY - f.y;
  const dt = Math.max(1, now - f.t);
  f.x = e.clientX; f.y = e.clientY; f.t = now;
  if (Math.hypot(e.clientX - gesture.x0, e.clientY - gesture.y0) > 8) {
    gesture.moved = true;
    clearTimeout(gesture.hold);
  }
  const width = f.box.width;
  if (gesture.multi) {
    // Two fingers: scroll in any direction by the average movement (content follows the fingers)
    pendingScroll.x += dx / f.box.width / fingers.size * 1.5;
    pendingScroll.y += dy / f.box.height / fingers.size * 1.5;
    return;
  }

  // Faster flicks travel further: gentle acceleration on top of the base speed
  const speed = Math.hypot(dx, dy) / dt;
  const accel = 1 + Math.min(speed, 3) * 0.7;
  pendingMove.x += dx / width * SPEED * accel;
  pendingMove.y += dy / width * SPEED * accel * TV_ASPECT;
});

function moveDot(f, e) {
  f.dot.style.left = `${e.clientX - f.box.left}px`;
  f.dot.style.top = `${e.clientY - f.box.top}px`;
}

function endFinger(e) {
  const f = fingers.get(e.pointerId);
  if (!f) return;
  f.dot.remove();
  fingers.delete(e.pointerId);
  if (fingers.size || !gesture) return;
  clearTimeout(gesture.hold);

  if (!gesture.moved && !gesture.multi && !gesture.held && Date.now() - gesture.t0 < 300) mouseClick(false);
  gesture = null;
}
pad.addEventListener('pointerup', endFinger);
pad.addEventListener('pointercancel', endFinger);

$('pad-mode').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  setPadMode(btn.dataset.mode);
  store.set('padMode', btn.dataset.mode);
});

function setPadMode(mode) {
  $('pad-mode').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  padMode = mode;
  if (mode !== 'air') setAirMode('off');
  $('touchpad').hidden = mode !== 'mouse';
  $('air-panel').hidden = mode !== 'air';
  if (PAD_HINTS[mode]) $('pad-hint').textContent = PAD_HINTS[mode];
  $('dpad').hidden = mode !== 'dpad';
  // The air panel has its own big Voltar: the small one gives its place to the keyboard
  $('row-back').hidden = mode === 'air';
  $('row-keys').hidden = mode !== 'air';
}

// ---------- Air mouse: "Mover" switch, scroll pad, select / back ----------

let airMode = 'off';

function setAirMode(mode) {
  if (mode === airMode) return;
  if (native?.airMode) {
    let problem = '';
    try { problem = native.airMode(mode); } catch { problem = 'Falha no sensor do celular'; }
    if (problem) { toast(problem, true); mode = 'off'; try { native.airMode('off'); } catch {} }
  } else if (mode !== 'off') {
    toast('Atualize o app do celular: abra tinyurl.com/2bhywg3r no Chrome', true);
    mode = 'off';
  }
  airMode = mode;
  $('air-move').classList.toggle('on', mode === 'move');
  $('air-status').textContent = mode === 'move' ? 'Ligado: aponte o celular para mover a setinha' : 'Sensor desligado: toque para ligar';
}

$('air-move').addEventListener('click', () => { buzz(15); setAirMode(airMode === 'move' ? 'off' : 'move'); });
// Never leave the sensor running in the background
document.addEventListener('visibilitychange', () => { if (document.visibilityState !== 'visible') setAirMode('off'); });

// Select: tap = click, hold = long click
(() => {
  const btn = $('air-select');
  let timer = null;
  let held = false;
  btn.addEventListener('pointerdown', () => {
    held = false;
    timer = setTimeout(() => { held = true; mouseClick(true); }, 600);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => btn.addEventListener(t, () => clearTimeout(timer)));
  btn.addEventListener('click', () => { if (!held) mouseClick(false); });
  btn.addEventListener('contextmenu', (e) => e.preventDefault());
})();

// Scroll pad: the TV content follows the finger in any direction, like a touch screen
(() => {
  const area = $('scroll-pad');
  const SCROLL_GAIN = 1.4;
  let last = null;
  let pending = { x: 0, y: 0 };

  area.addEventListener('contextmenu', (e) => e.preventDefault());
  area.addEventListener('pointerdown', (e) => {
    area.setPointerCapture(e.pointerId);
    area.classList.add('active');
    last = { x: e.clientX, y: e.clientY, box: area.getBoundingClientRect() };
  });
  area.addEventListener('pointermove', (e) => {
    if (!last) return;
    pending.x += (e.clientX - last.x) / last.box.width * SCROLL_GAIN;
    pending.y += (e.clientY - last.y) / last.box.height * SCROLL_GAIN;
    last.x = e.clientX;
    last.y = e.clientY;
  });
  const end = () => { last = null; area.classList.remove('active'); };
  area.addEventListener('pointerup', end);
  area.addEventListener('pointercancel', end);

  let httpBusy = false;
  setInterval(async () => {
    if (Math.hypot(pending.x, pending.y) < 0.015) return;
    const { x, y } = pending;
    // Fast UDP channel in the phone app; plain HTTP otherwise
    if (native?.scrollBy) {
      pending = { x: 0, y: 0 };
      try { native.scrollBy(x, y); } catch {}
    } else if (!httpBusy) {
      pending = { x: 0, y: 0 };
      httpBusy = true;
      try { await api('/api/mouse/scroll', { x, y }); } catch (err) { toast(err.message, true); }
      httpBusy = false;
    }
  }, 40);
})();

// ---------- Tabs ----------

document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-tab]');
  if (btn) showTab(btn.dataset.tab);
});

function showTab(tab) {
  if (state.tab === 'keyboard' && tab !== 'keyboard') setKeyboard(false);
  if (tab !== 'remote') setAirMode('off');
  state.tab = tab;
  for (const name of ['home', 'remote', 'apps', 'movies', 'week', 'youtube', 'keyboard']) {
    $(`view-${name}`).hidden = name !== tab;
  }
  document.querySelectorAll('.tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  window.scrollTo(0, 0);
  if (tab === 'apps') loadApps();
  if (tab === 'movies') loadMovies();
  if (tab === 'youtube') loadYouTube();
  if (tab === 'week') loadWeek();
  if (tab === 'home' && kind === 'projetor') loadProjector();
  if (tab === 'keyboard') {
    setKeyboard(true);
    setTimeout(() => $('kb-input').focus(), 50);
  }
}

// ---------- Status ----------

const STATUS = {
  ok: ['ok', 'Controle total'],
  waiting: ['warn', 'Aceite na TV'],
  off: ['off', 'ADB indisponível'],
};

async function refreshStatus() {
  try {
    const s = await api('/api/status');
    state.status = s;
    const [dot, text] = STATUS[s.adb] || STATUS.off;
    $('status-dot').className = `dot ${dot}`;
    $('status-text').textContent = text;
    $('device-name').textContent = s.kind === 'projetor' ? 'Projetor' : s.kind === 'tv' ? 'TV' : '';
    applyKind(s.kind);
    renderNow(s.foreground);
    renderWatching(s.watching);
  } catch {
    $('status-dot').className = 'dot off';
    $('status-text').textContent = 'TV fora do ar';
  }
}

// ---------- Projector mode: no music or YouTube; brightness, Bluetooth and more film rows ----------

let kind = null;

function applyKind(next) {
  const projector = next === 'projetor';
  $('music-card').hidden = projector;
  $('projector-card').hidden = !projector;
  document.querySelector('.tabbar [data-tab="youtube"]').hidden = projector;
  if (projector && state.tab === 'youtube') showTab('home');
  if (projector && state.tab === 'home') loadProjector();
  kind = next;
}

async function loadProjector() {
  let p;
  try { p = await api('/api/projector'); } catch { return; }
  const slider = $('proj-bright');
  if (document.activeElement !== slider) slider.value = p.brightness;
  $('proj-bright-val').textContent = `${p.brightness}%`;
  const bt = p.bluetooth || {};
  const toggle = $('proj-bt-toggle');
  toggle.hidden = !bt.available;
  toggle.textContent = bt.on ? 'Ligado' : 'Desligado';
  toggle.classList.toggle('glow', !!bt.on);
  toggle.dataset.on = bt.on ? '1' : '';
  $('proj-bt-list').replaceChildren(...(bt.devices || []).map((d) => {
    const row = document.createElement('div');
    row.className = 'proj-device';
    row.innerHTML = '<span></span><small></small>';
    row.querySelector('span').textContent = d.name;
    const small = row.querySelector('small');
    small.textContent = d.connected ? 'conectado' : d.kind;
    small.classList.toggle('on', d.connected);
    return row;
  }));
}

$('proj-bright').addEventListener('input', () => { $('proj-bright-val').textContent = `${$('proj-bright').value}%`; });
$('proj-bright').addEventListener('change', async () => {
  try {
    const r = await api('/api/projector/brightness', { value: Number($('proj-bright').value) });
    $('proj-bright-val').textContent = `${r.brightness}%`;
  } catch (e) { toast(e.message, true); }
});
$('proj-bt-toggle').addEventListener('click', async () => {
  const on = !$('proj-bt-toggle').dataset.on;
  try { await api('/api/projector/bluetooth', { on }); toast(on ? 'Ligando o Bluetooth' : 'Desligando o Bluetooth'); setTimeout(loadProjector, 1500); }
  catch (e) { toast(e.message, true); }
});
$('proj-bt-pair').addEventListener('click', async () => {
  try { await api('/api/projector/bluetooth/settings', {}); toast('Abrindo o Bluetooth no projetor: escolha a caixa ou o fone com o controle'); }
  catch (e) { toast(e.message, true); }
});

/** Stremio title on screen (opened from here): one tap shows its cast and story. */
function renderWatching(w) {
  const btn = $('now-film');
  btn.hidden = !w;
  if (!w) return;
  $('now-film-label').textContent = w.name ? `Elenco e curiosidades de ${w.name}` : 'Elenco e curiosidades do que está passando';
  btn.onclick = () => filmSheet(w);
}

function renderNow(fg) {
  const art = $('now-art');
  const current = art.dataset.pkg || '';
  if ((fg?.pkg || '') === current) return;
  art.dataset.pkg = fg?.pkg || '';
  art.innerHTML = fg ? `<img src="${iconUrl(fg.pkg)}" alt="">` : icon('tv');
  $('now-label').textContent = fg ? fg.label : 'Tela inicial';
  $('now-close').hidden = !fg;
}

$('now-close').addEventListener('click', async () => {
  const fg = state.status?.foreground;
  if (!fg) return;
  buzz(20);
  try {
    await api('/api/stop', { pkg: fg.pkg });
    toast(`${fg.label} fechado`);
    refreshStatus();
  } catch (e) { toast(e.message, true); }
});

function greet() {
  const now = new Date();
  const h = now.getHours();
  $('hello-title').textContent = h < 5 ? 'Boa noite' : h < 12 ? 'Bom dia' : h < 18 ? 'Boa tarde' : 'Boa noite';
  const date = now.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });
  $('hello-date').textContent = date.charAt(0).toUpperCase() + date.slice(1);
}

// ---------- Apps ----------

async function loadApps() {
  try {
    const data = await api('/api/apps');
    state.apps = data.apps;
    state.favorites = data.favorites;
    state.running = data.running;
    renderFavs();
    renderApps();
  } catch (e) {
    toast(e.message, true);
  }
}

const appByPkg = (pkg) => state.apps.find((a) => a.pkg === pkg);

function tile(app, { edit = false, badge = false } = {}) {
  const el = document.createElement('div');
  el.className = 'tile' + (state.running.includes(app.pkg) ? ' running' : '');
  el.innerHTML = `
    <button class="tile-art"><img src="${iconUrl(app.pkg)}" alt="" draggable="false"></button>
    <span class="tile-label"></span>`;
  el.querySelector('.tile-label').textContent = app.label;
  const art = el.querySelector('.tile-art');

  if (edit) {
    const tools = document.createElement('div');
    tools.className = 'tile-edit';
    tools.innerHTML = `<button class="left" aria-label="Mover para a esquerda">${icon('left')}</button>
                       <button class="remove" aria-label="Remover">${icon('x')}</button>`;
    tools.querySelector('.left').onclick = () => moveFav(app.pkg, -1);
    tools.querySelector('.remove').onclick = () => toggleFav(app.pkg);
    el.append(tools);
    return el;
  }

  if (badge && app.favorite) {
    const star = document.createElement('span');
    star.className = 'badge';
    star.innerHTML = icon('star');
    el.append(star);
  }
  onTapOrHold(art, () => launch(app), () => { buzz(25); appSheet(app); });
  return el;
}

// Tap runs onTap; holding for half a second runs onHold instead.
function onTapOrHold(el, onTap, onHold) {
  let timer = null;
  let held = false;
  let start = null;
  el.addEventListener('pointerdown', (e) => {
    held = false;
    start = [e.clientX, e.clientY];
    timer = setTimeout(() => { held = true; onHold(); }, 500);
  });
  el.addEventListener('pointermove', (e) => {
    if (start && Math.hypot(e.clientX - start[0], e.clientY - start[1]) > 10) clearTimeout(timer);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => el.addEventListener(t, () => clearTimeout(timer)));
  el.addEventListener('click', (e) => { if (held) { e.preventDefault(); return; } onTap(); });
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

function renderFavs() {
  const favApps = state.favorites.map(appByPkg).filter(Boolean);
  $('favs').replaceChildren(...favApps.map((a) => tile(a, { edit: state.editingFavs })));
  $('favs-empty').hidden = favApps.length > 0;
  $('edit-favs').hidden = favApps.length === 0 && !state.editingFavs;
}

function renderApps() {
  const query = $('apps-search').value.trim().toLowerCase();
  const list = state.apps.filter((a) => a.label.toLowerCase().includes(query));
  $('apps-all').replaceChildren(...list.map((a) => tile(a, { badge: true })));

  $('running-count').textContent = state.running.length;
  const rows = state.running.map(appByPkg).filter(Boolean).map((app) => {
    const row = document.createElement('div');
    row.className = 'list-row';
    row.innerHTML = `<img src="${iconUrl(app.pkg)}" alt=""><strong></strong>
      <button class="round" aria-label="Abrir">${icon('play')}</button>
      <button class="round danger" aria-label="Fechar">${icon('x')}</button>`;
    row.querySelector('strong').textContent = app.label;
    const [open, close] = row.querySelectorAll('button');
    open.onclick = () => launch(app);
    close.onclick = () => stopApp(app);
    return row;
  });
  if (rows.length) {
    const all = document.createElement('button');
    all.className = 'pill close-all';
    all.innerHTML = `${icon('close')}Fechar todos`;
    all.onclick = closeAll;
    rows.push(all);
  } else {
    const empty = document.createElement('p');
    empty.className = 'hint';
    empty.textContent = state.status?.adb === 'ok' ? 'Nenhum app aberto em segundo plano.' : 'Precisa do ADB ativo para ver os apps abertos.';
    rows.push(empty);
  }
  $('apps-running').replaceChildren(...rows);
}

$('apps-search').addEventListener('input', renderApps);

$('apps-mode').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  state.appsMode = btn.dataset.mode;
  $('apps-mode').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === btn));
  $('apps-all').hidden = state.appsMode !== 'all';
  $('apps-running').hidden = state.appsMode !== 'running';
  document.querySelector('.search').hidden = state.appsMode !== 'all';
  if (state.appsMode === 'running') loadApps();
});

async function launch(app) {
  buzz(15);
  try {
    const { ok } = await api('/api/launch', { pkg: app.pkg });
    toast(ok ? `Abrindo ${app.label}` : `Não deu para abrir ${app.label}`, !ok);
    setTimeout(refreshStatus, 1200);
  } catch (e) { toast(e.message, true); }
}

async function stopApp(app) {
  buzz(20);
  try {
    await api('/api/stop', { pkg: app.pkg });
    toast(`${app.label} fechado`);
    await loadApps();
    refreshStatus();
  } catch (e) { toast(e.message, true); }
}

async function closeAll() {
  buzz(30);
  try {
    for (const pkg of state.running) await api('/api/stop', { pkg });
    toast('Todos os apps foram fechados');
    await loadApps();
    refreshStatus();
  } catch (e) { toast(e.message, true); }
}

async function saveFavs(next) {
  state.favorites = next;
  state.apps.forEach((a) => { a.favorite = next.includes(a.pkg); });
  renderFavs();
  renderApps();
  try { await api('/api/favorites', { pkgs: next }); } catch (e) { toast(e.message, true); }
}

function toggleFav(pkg) {
  const has = state.favorites.includes(pkg);
  saveFavs(has ? state.favorites.filter((p) => p !== pkg) : [...state.favorites, pkg]);
}

function moveFav(pkg, dir) {
  const list = [...state.favorites];
  const i = list.indexOf(pkg);
  const j = i + dir;
  if (j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  saveFavs(list);
}

$('edit-favs').addEventListener('click', () => {
  state.editingFavs = !state.editingFavs;
  const btn = $('edit-favs');
  btn.classList.toggle('on', state.editingFavs);
  btn.innerHTML = state.editingFavs ? `${icon('check')}<span>Pronto</span>` : `${icon('pencil')}<span>Editar</span>`;
  renderFavs();
});

// ---------- Sheets ----------

function openSheet(html) {
  const sheet = $('sheet');
  sheet.innerHTML = html;
  sheet.hidden = false;
  $('sheet-backdrop').hidden = false;
  return sheet;
}

function closeSheet() {
  $('sheet').hidden = true;
  $('sheet-backdrop').hidden = true;
}
$('sheet-backdrop').addEventListener('click', closeSheet);

// Experiment: can the Fire TV wake the Xbox over HDMI? Reads what the TV's HDMI service offers
// and, if it has a way to power on another device, lets the user try each possible address.
async function cecSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Teste do HDMI-CEC</h3><p>Deixe o Xbox desligado e toque em uma tentativa por vez, olhando se ele liga</p></div></div>
    <div class="sheet-actions" id="cec-tries"></div>
    <pre class="report" id="cec-report">Lendo a TV… pode levar alguns segundos.</pre>
    <div class="sheet-actions"><button class="sheet-btn" id="cec-close">${icon('x')}Fechar</button></div>`);
  sheetButton(sheet, 'cec-close', settingsSheet);
  const out = sheet.querySelector('#cec-report');
  const tries = sheet.querySelector('#cec-tries');
  try {
    const r = await api('/api/debug/cec');
    const log = [];
    const show = () => {
      out.textContent = [
        ...log,
        `A TV está mostrando a Fire TV agora: ${r.onScreen}`,
        `Tentativas disponíveis: ${r.attempts.length || 'nenhuma: esta Fire TV não oferece como ligar outro aparelho'}`,
        `\nServiço: ${r.service}`,
        `\nComando cmd: ${r.cmd}`,
        `\nChamadas de leitura:\n${r.calls.join('\n') || 'nenhuma'}`,
        `\nFunções do HDMI (${r.methods.length}):\n${r.methods.join('\n')}`,
        `\nPropriedades:\n${r.props}`,
        `\nDispositivos: ${r.nodes}`,
        `\nEstado do HDMI:\n${r.dumpsys}`,
      ].join('\n');
    };
    show();
    for (const a of r.attempts) {
      const btn = document.createElement('button');
      btn.className = 'sheet-btn';
      btn.innerHTML = icon('power');
      btn.append(a.label);
      btn.onclick = async () => {
        toast('Enviando… olhe o Xbox');
        try {
          const res = await api('/api/debug/cec/try', { id: a.id });
          log.unshift(`[${a.label}]\n${res.output}\n`);
        } catch (e) { log.unshift(`[${a.label}] erro: ${e.message}\n`); }
        show();
      };
      tries.append(btn);
    }
  } catch (e) { out.textContent = e.message; }
}

// ---------- TV or projector: switching goes back to the phone app's start screen ----------

function devicesSheet() {
  if (native?.chooseDevice) native.chooseDevice();
  else toast('Use o app Controle TV do celular para trocar de aparelho', true);
}

$('device-switch').addEventListener('click', devicesSheet);

// ---------- Playlists on the TV home: choose, order, search YouTube Music, paste a link ----------

async function playlistsSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Playlists da tela inicial</h3><p>Aparecem na TV, embaixo da música. Toque nas setas para mudar a ordem.</p></div></div>
    <div id="pl-list"><p class="hint">Carregando…</p></div>
    <h4>Adicionar</h4>
    <label class="search">${icon('search')}<input id="pl-query" type="search" placeholder="Pesquisar playlists (samba, worship, rock…)" autocomplete="off" enterkeyhint="search"></label>
    <div id="pl-results"></div>
    <div class="paste-row">
      <input class="name-input" id="pl-link" autocomplete="off" inputmode="url" placeholder="ou cole o link de uma playlist sua">
      <button class="pill" id="pl-paste">${icon('plus')}</button>
    </div>
    <div class="sheet-actions"><button class="sheet-btn" id="pl-done">${icon('check')}Pronto</button></div>`);
  const listBox = sheet.querySelector('#pl-list');
  const results = sheet.querySelector('#pl-results');
  let list = [];
  const save = async () => {
    try { ({ playlists: list } = await api('/api/playlists', { playlists: list })); paint(); }
    catch (e) { toast(e.message, true); }
  };
  const paint = () => {
    if (!list.length) { listBox.replaceChildren(hintText('Nenhuma playlist: a fileira some da TV. Adicione abaixo.')); return; }
    listBox.replaceChildren(...list.map((p, i) => {
      const row = document.createElement('div');
      row.className = 'channel-row';
      row.innerHTML = `<span class="pl-icon">${icon('music')}</span><div><strong></strong><small></small></div>
        <button class="round" aria-label="Subir"${i === 0 ? ' disabled' : ''}>${icon('up')}</button>
        <button class="round" aria-label="Tirar">${icon('trash')}</button>`;
      row.querySelector('strong').textContent = p.title;
      row.querySelector('small').textContent = p.id ? 'Playlist escolhida' : 'Muda toda semana (YouTube Music)';
      const [upBtn, delBtn] = row.querySelectorAll('button');
      upBtn.onclick = () => { if (i > 0) { [list[i - 1], list[i]] = [list[i], list[i - 1]]; save(); } };
      delBtn.onclick = () => { list.splice(i, 1); save(); };
      return row;
    }));
  };
  const add = (entry) => {
    if (list.some((p) => (entry.id && p.id === entry.id) || p.title === entry.title)) { toast('Já está na lista'); return; }
    list.push(entry);
    save();
    toast(`${entry.title} adicionada`);
  };
  try { ({ playlists: list } = await api('/api/playlists')); } catch (e) { toast(e.message, true); }
  paint();

  const input = sheet.querySelector('#pl-query');
  let timer;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    const q = input.value.trim();
    if (q.length < 2) { results.replaceChildren(); return; }
    timer = setTimeout(async () => {
      results.replaceChildren(hintText('Pesquisando…'));
      try {
        const { results: found } = await api(`/api/playlists/search?q=${encodeURIComponent(q)}`);
        if (input.value.trim() !== q) return;
        results.replaceChildren(...(found.length ? found.map((p) => {
          const row = document.createElement('div');
          row.className = 'channel-row';
          row.innerHTML = `<img alt=""><div><strong></strong><small></small></div><button class="round" aria-label="Adicionar">${icon('plus')}</button>`;
          if (p.thumb) row.querySelector('img').src = p.thumb;
          row.querySelector('img').style.borderRadius = '10px';
          row.querySelector('strong').textContent = p.title;
          row.querySelector('small').textContent = p.subtitle || '';
          row.querySelector('button').onclick = () => add({ title: p.title.slice(0, 40), id: p.id });
          return row;
        }) : [hintText('Nada encontrado')]));
      } catch (e) { results.replaceChildren(hintText(e.message)); }
    }, 450);
  });
  sheetButton(sheet, 'pl-paste', async () => {
    const url = sheet.querySelector('#pl-link').value.trim();
    if (!url) { toast('Cole o link da playlist', true); return; }
    try {
      const { id, title } = await api(`/api/playlists/link?url=${encodeURIComponent(url)}`);
      add({ title: (title || 'Minha playlist').slice(0, 40), id });
      sheet.querySelector('#pl-link').value = '';
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'pl-done', settingsSheet);
}

function sheetButton(sheet, id, handler) {
  sheet.querySelector(`#${id}`)?.addEventListener('click', handler);
}

function appSheet(app) {
  const fav = state.favorites.includes(app.pkg);
  const sheet = openSheet(`
    <div class="sheet-head">
      <img src="${iconUrl(app.pkg)}" alt="">
      <div><h3></h3><p></p></div>
    </div>
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="s-open">${icon('play')}Abrir na TV</button>
      <button class="sheet-btn" id="s-fav">${icon('star')}${fav ? 'Remover do início' : 'Fixar no início'}</button>
      <button class="sheet-btn" id="s-stop">${icon('close')}Fechar (forçar parada)</button>
      <button class="sheet-btn danger" id="s-uninstall" ${app.system ? 'disabled' : ''}>${icon('trash')}${app.system ? 'App do sistema' : 'Desinstalar'}</button>
    </div>`);
  sheet.querySelector('h3').textContent = app.label;
  sheet.querySelector('.sheet-head p').textContent = app.pkg;
  sheetButton(sheet, 's-open', () => { closeSheet(); launch(app); });
  sheetButton(sheet, 's-fav', () => { closeSheet(); toggleFav(app.pkg); toast(fav ? 'Removido do início' : 'Fixado no início'); });
  sheetButton(sheet, 's-stop', () => { closeSheet(); stopApp(app); });
  sheetButton(sheet, 's-uninstall', () => confirmUninstall(app));
}

function confirmUninstall(app) {
  const sheet = openSheet(`
    <div class="sheet-head">
      <img src="${iconUrl(app.pkg)}" alt="">
      <div><h3>Desinstalar?</h3><p></p></div>
    </div>
    <p class="body">O app e os dados dele serão apagados do Fire TV.</p>
    <div class="sheet-actions">
      <button class="sheet-btn danger" id="s-yes">${icon('trash')}Sim, desinstalar</button>
      <button class="sheet-btn" id="s-no">${icon('x')}Cancelar</button>
    </div>`);
  sheet.querySelector('.sheet-head p').textContent = app.label;
  sheetButton(sheet, 's-no', closeSheet);
  sheetButton(sheet, 's-yes', async () => {
    closeSheet();
    try {
      await api('/api/uninstall', { pkg: app.pkg });
      toast(`${app.label} desinstalado`);
      if (state.favorites.includes(app.pkg)) await saveFavs(state.favorites.filter((p) => p !== app.pkg));
      loadApps();
    } catch (e) { toast(e.message, true); }
  });
}

$('q-mirror').addEventListener('click', async () => {
  buzz(15);
  try {
    await api('/api/mirror', {});
    mirrorSheet();
  } catch (e) {
    mirrorDebugSheet(e.message);
  }
});

async function mirrorDebugSheet(reason) {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Espelhamento não abriu</h3><p></p></div></div>
    <p class="body">Estas telas do Fire TV parecem de espelhamento. Teste uma por uma e olhe a TV: a que abrir a tela de espelhamento fica salva no botão.</p>
    <div class="list" id="s-candidates"><p class="hint">Procurando…</p></div>
    <pre class="report" id="s-mirror-out" hidden></pre>
    <p class="body" style="margin-top:14px">Se nenhuma funcionar, ative pela TV: <b>Configurações → Tela e sons → Ativar espelhamento de tela</b>.</p>
    <div class="sheet-actions"><button class="sheet-btn" id="s-close">${icon('x')}Fechar</button></div>`);
  sheet.querySelector('.sheet-head p').textContent = reason;
  sheetButton(sheet, 's-close', closeSheet);
  const list = sheet.querySelector('#s-candidates');
  const out = sheet.querySelector('#s-mirror-out');
  try {
    const { candidates } = await api('/api/mirror/candidates');
    if (!candidates.length) { list.innerHTML = '<p class="hint">Nenhuma tela com nome de espelhamento encontrada.</p>'; return; }
    list.replaceChildren(...candidates.map((component) => {
      const row = document.createElement('div');
      row.className = 'candidate';
      row.innerHTML = '<code></code><button>Testar</button>';
      row.querySelector('code').textContent = component;
      row.querySelector('button').onclick = async () => {
        out.hidden = false;
        out.textContent = 'Abrindo…';
        try { out.textContent = (await api('/api/mirror/try', { component })).output || 'Abriu (sem mensagem)'; }
        catch (err) { out.textContent = err.message; }
      };
      return row;
    }));
  } catch (err) {
    list.innerHTML = '';
    out.hidden = false;
    out.textContent = err.message;
  }
}

function mirrorSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Espelhamento aberto na TV</h3><p>Agora conecte pelo celular</p></div></div>
    <ol>
      <li>Puxe a <b>barra de notificações</b> do celular.</li>
      <li>Toque em <b>Transmitir</b> ou <b>Espelhamento de tela</b>.<br>No Xiaomi também fica em <b>Configurações → Conexão e compartilhamento → Transmitir</b>.</li>
      <li>Escolha o <b>Fire TV</b> na lista.</li>
    </ol>
    <p class="body">Para sair, use <b>Voltar</b> no Controle. O app continua funcionando durante o espelhamento.</p>
    <div class="sheet-actions"><button class="sheet-btn primary" id="s-ok">${icon('check')}Entendi</button></div>`);
  sheetButton(sheet, 's-ok', closeSheet);
}

$('q-power').addEventListener('click', () => {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Energia</h3><p>A TV liga e desliga junto pelo HDMI</p></div></div>
    <div class="sheet-actions">
      <button class="sheet-btn danger" id="s-sleep">${icon('moon')}Desligar TV</button>
      <button class="sheet-btn primary" id="s-wake">${icon('sun')}Ligar TV</button>
    </div>`);
  sheetButton(sheet, 's-sleep', () => { closeSheet(); sendKey('sleep'); toast('Desligando'); });
  sheetButton(sheet, 's-wake', () => { closeSheet(); sendKey('wakeup'); toast('Ligando'); });
});

const ADB_HELP = {
  off: native
    ? `<ol>
      <li>No Fire TV: <b>Configurações → Minha Fire TV → Opções do desenvolvedor → Depuração ADB</b> ligada.</li>
      <li>Quando aparecer <b>Permitir depuração?</b> na TV, marque <b>Sempre permitir</b> e confirme.</li>
    </ol>`
    : `<p class="body">A Amazon bloqueia o controle completo vindo da própria TV. Ele precisa passar pelo <b>app Controle TV do celular</b>:</p>
    <ol>
      <li>Toque em <b>Baixar app do celular</b> abaixo e instale (permita instalar desta fonte se o celular pedir).</li>
      <li>Abra o app: ele acha a TV sozinho.</li>
      <li>Na TV, aceite <b>Permitir depuração?</b> marcando <b>Sempre permitir</b>.</li>
    </ol>`,
  waiting: `<ol><li>Olhe a TV: a janela <b>Permitir depuração?</b> está aberta.</li>
      <li>Marque <b>Sempre permitir deste computador</b> e confirme.</li></ol>`,
};

function settingsSheet() {
  const s = state.status || {};
  const [dot, text] = STATUS[s.adb] || STATUS.off;
  const projector = s.kind === 'projetor';
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Ajustes</h3><p>${projector ? 'Projetor' : 'TV'} · <span class="dot ${dot}"></span> ${text}</p></div></div>
    ${ADB_HELP[s.adb] ? `<p class="body" style="margin-top:4px">Para liberar o controle completo:</p>${ADB_HELP[s.adb]}` : ''}
    <div class="sheet-actions">
      ${s.adb !== 'ok' ? `<button class="sheet-btn primary" id="s-retry">${icon('refresh')}Tentar conectar de novo</button>` : ''}
      ${native ? `<button class="sheet-btn" id="s-forget">${icon('tv')}Trocar entre TV e projetor</button>` : ''}
      ${s.adb !== 'ok' && !native ? `<a class="sheet-btn primary" href="${PHONE_APP_URL}">${icon('download')}Baixar app do celular</a>` : ''}
    </div>

    <h4>Tela ${projector ? 'do projetor' : 'da TV'}</h4>
    <div class="sheet-actions">
      <button class="sheet-btn${s.homeLauncher ? ' on' : ''}" id="s-home">${icon('house')}${s.homeLauncher ? 'Controle TV é a tela inicial (desligar)' : 'Usar o Controle TV como tela inicial'}</button>
      <button class="sheet-btn" id="s-name">${icon('pencil')}Nome na saudação: <span id="s-name-value"></span></button>
      <button class="sheet-btn" id="s-saver">${icon('image')}Tela de descanso</button>
      <button class="sheet-btn" id="s-player">${icon('play')}Player de vídeo</button>
      ${projector ? '' : `<button class="sheet-btn" id="s-playlists">${icon('music')}Playlists da tela inicial</button>`}
      ${projector ? '' : `<button class="sheet-btn" id="s-musicapp">${icon('music')}Música toca no: ${s.musicApp === 'smarttube' ? 'SmartTube' : 'AuraMusic'}</button>`}
      ${projector ? '' : `<button class="sheet-btn${s.musicClip !== false ? ' on' : ''}" id="s-clip">${icon('music')}Clipe da música no fundo: ${s.musicClip !== false ? 'ligado' : 'desligado'}</button>`}
    </div>

    <h4>Atualização</h4>
    <div class="kv"><span>Versões</span><span>${projector ? 'projetor' : 'TV'} ${s.version ?? '–'} · telas ${s.webVersion ?? '–'}${native ? ` · celular ${native.version?.() ?? '?'}` : ''}</span></div>
    <div class="kv"><span>Situação</span><span id="s-update-text"></span></div>
    <div class="sheet-actions">
      ${s.updatePending ? `<button class="sheet-btn primary" id="s-install">${icon('download')}Instalar atualização baixada</button>` : ''}
      <button class="sheet-btn primary" id="s-update">${icon('download')}Procurar atualização</button>
    </div>

    <h4>Segurança e ajuda</h4>
    <div class="sheet-actions">
      <button class="sheet-btn" id="s-pin">${icon('lock')}${s.requirePin ? 'Parar de pedir PIN' : 'Pedir PIN para novos celulares'}</button>
      <button class="sheet-btn" id="s-diag">${icon('search')}Diagnóstico</button>
      <button class="sheet-btn danger" id="s-logout">${icon('logout')}Desconectar este celular</button>
    </div>
    <p class="hint small" style="margin-top:16px">${icon('info')}Legenda automática: modelo Kroko ASR (Banafo, licença CC-BY-SA) rodando com sherpa-onnx (Apache-2.0).</p>`);
  sheet.querySelector('#s-update-text').textContent = s.update || '–';
  sheetButton(sheet, 's-retry', async () => {
    toast('Tentando conectar…');
    try {
      await api('/api/adb/retry', {});
      setTimeout(async () => { await refreshStatus(); settingsSheet(); }, 4000);
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-player', playerSheet);
  sheetButton(sheet, 's-playlists', playlistsSheet);
  sheetButton(sheet, 's-musicapp', async () => {
    const next = s.musicApp === 'smarttube' ? 'aura' : 'smarttube';
    if (next === 'smarttube' && s.smartTube === false) { toast('Instale o SmartTube na TV primeiro', true); return; }
    try {
      await api('/api/settings', { musicApp: next });
      toast(next === 'smarttube'
        ? 'Música no SmartTube. Dica: no SmartTube, ligue "Reprodução em segundo plano: somente áudio"'
        : 'Música no AuraMusic');
      await refreshStatus();
      settingsSheet();
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-clip', async () => {
    const on = s.musicClip === false;
    try {
      await api('/api/settings', { musicClip: on });
      toast(on ? 'Clipe no fundo ligado' : 'Clipe no fundo desligado (para cima ainda abre o clipe no SmartTube)');
      await refreshStatus();
      settingsSheet();
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-saver', screensaverSheet);
  sheetButton(sheet, 's-diag', diagnosticsSheet);
  const nameValue = sheet.querySelector('#s-name-value');
  if (nameValue) nameValue.textContent = s.homeName || '–';
  sheetButton(sheet, 's-name', () => {
    const edit = openSheet(`
      <div class="sheet-head"><div><h3>Nome na tela da TV</h3><p>Aparece em "Boa tarde, …"</p></div></div>
      <input class="name-input" id="h-name" autocomplete="off" maxlength="40">
      <div class="sheet-actions">
        <button class="sheet-btn primary" id="h-save">${icon('check')}Salvar</button>
        <button class="sheet-btn" id="h-cancel">${icon('x')}Cancelar</button>
      </div>`);
    const input = edit.querySelector('#h-name');
    input.value = s.homeName || '';
    setTimeout(() => input.focus(), 50);
    sheetButton(edit, 'h-cancel', settingsSheet);
    sheetButton(edit, 'h-save', async () => {
      try {
        await api('/api/settings', { homeName: input.value.trim() || 'Família Vecciani' });
        await refreshStatus();
        toast('Nome atualizado na TV');
        settingsSheet();
      } catch (e) { toast(e.message, true); }
    });
  });
  sheetButton(sheet, 's-home', async () => {
    const enable = !s.homeLauncher;
    toast(enable ? 'Ativando…' : 'Desativando…');
    try {
      await api('/api/settings', { homeLauncher: enable });
      // The service takes a moment to start or stop
      setTimeout(async () => {
        await refreshStatus();
        toast(enable
          ? (state.status?.homeLauncher ? 'Pronto: o Home do controle agora abre o Controle TV' : 'A TV não ativou. Veja se o controle completo (ADB) está ativo.')
          : 'Desligado: o Home volta a abrir a tela da Amazon');
        settingsSheet();
      }, 1500);
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-pin', async () => {
    try {
      await api('/api/settings', { requirePin: !s.requirePin });
      toast(s.requirePin ? 'PIN desligado: celulares conectam direto' : 'Novos celulares vão precisar do PIN da TV');
      await refreshStatus();
      settingsSheet();
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-forget', devicesSheet);
  sheetButton(sheet, 's-install', async () => {
    try {
      await api('/api/update/install', {});
      toast('Confirme a instalação na TV');
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-update', async () => {
    toast('Procurando…');
    try {
      const { result } = await api('/api/update', {});
      toast(result);
      // The phone also installs the newest TV app itself (no OK needed on the TV)
      native?.updateTv?.();
      sheet.querySelector('#s-update-text').textContent = result;
      if (/Telas atualizadas/.test(result)) setTimeout(() => location.reload(), 1200);
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-logout', () => { closeSheet(); logout(); });
}
/** Connection details and the tests used to fix problems, out of the way of everyday settings. */
function diagnosticsSheet() {
  const s = state.status || {};
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Diagnóstico</h3><p>Para resolver problemas</p></div></div>
    <div class="kv"><span>Aparelho</span><span id="d-device"></span></div>
    ${s.via ? `<div class="kv"><span>Controle via</span><span>${s.via}</span></div>` : ''}
    ${s.adbError ? `<div class="kv"><span>Erro do ADB</span><span class="mono" id="d-adb-error"></span></div>` : ''}
    ${native ? `<div class="kv"><span>Mouse aéreo</span><span class="mono" id="d-air"></span></div>` : ''}
    ${s.lastPlay ? `<div class="kv"><span>Último vídeo</span><span class="mono" id="d-last-play"></span></div>` : ''}
    ${s.lastFields ? `<div class="kv"><span>Campos do app</span><span class="mono" id="d-last-fields"></span></div>` : ''}
    <div class="sheet-actions" style="margin-top:12px">
      <button class="sheet-btn" id="d-probe">${icon('search')}Teste da conexão (ADB)</button>
      <button class="sheet-btn" id="d-system">${icon('settings')}Telas de configuração e entradas</button>
      <button class="sheet-btn" id="d-cec">${icon('power')}Teste do HDMI-CEC</button>
      <button class="sheet-btn" id="d-back">${icon('back')}Voltar aos ajustes</button>
    </div>`);
  sheet.querySelector('#d-device').textContent = s.device || '–';
  const set = (id, value) => { const el = sheet.querySelector(id); if (el) el.textContent = value; };
  set('#d-adb-error', s.adbError);
  set('#d-last-play', s.lastPlay);
  set('#d-last-fields', s.lastFields);
  if (native) {
    set('#d-air', `celular enviou ${native.airMouseSent?.() ?? 0} · TV recebeu ${s.airReceived ?? '?'}` +
      (s.airRejected ? ` · recusados ${s.airRejected}` : '') + (s.pointerProblem ? ` · ${s.pointerProblem}` : ''));
  }
  sheetButton(sheet, 'd-probe', probeSheet);
  sheetButton(sheet, 'd-cec', cecSheet);
  sheetButton(sheet, 'd-back', settingsSheet);
  sheetButton(sheet, 'd-system', async () => {
    const report = openSheet(`
      <div class="sheet-head"><div><h3>Configurações e entradas</h3><p>Mande um print disto se algum atalho não abrir</p></div></div>
      <pre class="report" id="s-system-report">Lendo…</pre>
      <div class="sheet-actions"><button class="sheet-btn" id="s-system-close">${icon('x')}Fechar</button></div>`);
    sheetButton(report, 's-system-close', diagnosticsSheet);
    try {
      const r = await api('/api/debug/system');
      report.querySelector('#s-system-report').textContent =
        `App de configurações: ${r.settingsPackage || 'nenhum'}\n\nAtalhos padrão:\n${r.standard.join('\n')}\n\n` +
        `Entradas (${r.inputs.length}):\n${r.inputs.join('\n') || 'nenhuma'}\n\nTelas do app de configurações (${r.screens.length}):\n${r.screens.join('\n')}`;
    } catch (e) { report.querySelector('#s-system-report').textContent = e.message; }
  });
}

// ---------- Screensaver ----------

const SAVER_MINUTES = [1, 2, 5, 10];
const SAVER_DIM = [[0.15, 'Leve'], [0.35, 'Médio'], [0.55, 'Forte']];

async function screensaverSheet() {
  await refreshStatus();
  const saver = state.status?.screensaver || { enabled: true, minutes: 2, dim: 0.35, photos: 0 };
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Tela de descanso</h3><p>Fotos, relógio e música quando a TV fica parada</p></div></div>
    <button class="sheet-btn${saver.enabled ? ' on' : ''}" id="v-toggle">${icon(saver.enabled ? 'check' : 'x')}${saver.enabled ? 'Ligada' : 'Desligada'}</button>
    <h4>Entrar depois de</h4>
    <div class="lang-row" id="v-minutes"></div>
    <h4>Escurecer as fotos</h4>
    <div class="lang-row" id="v-dim"></div>
    <h4>Fotos (${saver.photos} na TV)</h4>
    <div class="sheet-actions">
      ${native ? `<button class="sheet-btn primary" id="v-upload">${icon('upload')}Enviar fotos do celular</button>` : ''}
      ${native ? `<button class="sheet-btn" id="v-folder">${icon('folder')}Ver e apagar fotos</button>` : ''}
      <button class="sheet-btn" id="v-test">${icon('play')}Testar agora</button>
    </div>
    <h4>Álbum do Google Fotos</h4>
    <p class="body" id="v-album-state"></p>
    <input class="name-input" id="v-album" autocomplete="off" inputmode="url" placeholder="Cole o link do álbum (photos.app.goo.gl/…)">
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="v-album-save">${icon('image')}Usar este álbum</button>
      <button class="sheet-btn" id="v-album-off" hidden>${icon('trash')}Parar de usar o álbum</button>
    </div>
    <p class="hint small">${icon('info')}No app Google Fotos: abra o álbum → <b>Compartilhar</b> → <b>Criar link</b> (ou Copiar link) e cole aqui. A TV confere o álbum a cada 6 horas: foto nova entra, foto apagada sai.</p>
    <p class="hint small" style="margin-top:12px">${icon('info')}Não entra enquanto um filme ou canal estiver na tela. Qualquer botão do controle sai do descanso.</p>`);
  const save = async (body, message) => {
    try { await api('/api/settings', body); toast(message); await refreshStatus(); screensaverSheet(); }
    catch (e) { toast(e.message, true); }
  };
  // Shared Google Photos album
  const album = saver.album || {};
  const albumState = sheet.querySelector('#v-album-state');
  sheet.querySelector('#v-album').value = album.url || '';
  sheet.querySelector('#v-album-off').hidden = !album.url;
  albumState.textContent = !album.url ? 'Nenhum álbum ligado.'
    : album.syncing ? 'Baixando as fotos do álbum…'
    : album.error ? `Problema no álbum: ${album.error}`
    : `${album.photos} fotos do álbum na TV.`;
  if (album.syncing) setTimeout(() => { if (sheet.isConnected) screensaverSheet(); }, 5000);
  sheetButton(sheet, 'v-album-save', async () => {
    const url = sheet.querySelector('#v-album').value.trim();
    if (!url) { toast('Cole o link do álbum', true); return; }
    try { await api('/api/screensaver/album', { url }); toast('Baixando as fotos do álbum…'); setTimeout(screensaverSheet, 1500); }
    catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'v-album-off', async () => {
    try { await api('/api/screensaver/album', { url: '' }); toast('Álbum desligado'); screensaverSheet(); }
    catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'v-toggle', () => save({ screensaver: !saver.enabled }, saver.enabled ? 'Tela de descanso desligada' : 'Tela de descanso ligada'));
  sheet.querySelector('#v-minutes').replaceChildren(...SAVER_MINUTES.map((m) => {
    const b = document.createElement('button');
    b.textContent = `${m} min`;
    b.classList.toggle('on', m === saver.minutes);
    b.onclick = () => save({ screensaverMinutes: m }, `Entra depois de ${m} min parado`);
    return b;
  }));
  sheet.querySelector('#v-dim').replaceChildren(...SAVER_DIM.map(([value, label]) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.classList.toggle('on', Math.abs(value - saver.dim) < 0.05);
    b.onclick = () => save({ screensaverDim: value }, `Escurecimento: ${label.toLowerCase()}`);
    return b;
  }));
  sheetButton(sheet, 'v-upload', async () => {
    toast('Escolha as fotos…');
    try {
      const count = await nativeCall('uploadPhotos', { folder: saver.folder || '/sdcard/Pictures/DescansoTV' },
        (done, total) => { toast(`Enviando fotos: ${done + 1} de ${total}`); clearTimeout(toast.timer); });
      toast(`${count} foto(s) enviada(s) para a TV`);
      screensaverSheet();
    } catch (e) { if (e.message !== 'cancelado') toast(e.message, true); }
  });
  sheetButton(sheet, 'v-folder', () => filesSheet(saver.folder || '/sdcard/Pictures/DescansoTV'));
  sheetButton(sheet, 'v-test', async () => {
    try { await api('/api/screensaver/start', {}); toast('Descanso aberto na TV: qualquer botão sai'); closeSheet(); }
    catch (e) { toast(e.message, true); }
  });
}

async function playerSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Player da TV</h3><p>Onde os vídeos do "Tocar na TV" abrem</p></div></div>
    <div class="sheet-actions" id="s-players"><p class="hint">Carregando…</p></div>
    <p class="hint small" style="margin-top:14px">${icon('info')}O player do Controle TV repassa os dados de acesso do app de filmes e, se falhar, tenta o VLC sozinho. Alguns links só funcionam nele.</p>`);
  try {
    const { players, preferred } = await api('/api/players');
    const options = [{ pkg: '', label: 'Controle TV (com VLC de reserva)' }, ...players];
    sheet.querySelector('#s-players').replaceChildren(...options.map((p) => {
      const btn = document.createElement('button');
      btn.className = 'sheet-btn' + (p.pkg === preferred ? ' on' : '');
      btn.innerHTML = p.pkg === preferred ? icon('check') : icon('play');
      btn.append(p.label);
      btn.onclick = async () => {
        try {
          await api('/api/settings', { player: p.pkg });
          toast(`Vídeos vão abrir no ${p.label.replace(/ \(.*\)/, '')}`);
          closeSheet();
        } catch (e) { toast(e.message, true); }
      };
      return btn;
    }));
  } catch (e) {
    sheet.querySelector('#s-players').innerHTML = '';
    toast(e.message, true);
  }
}

async function probeSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Diagnóstico do ADB</h3><p>Olhe a TV: se aparecer "Permitir depuração?", aceite</p></div></div>
    <pre class="report" id="s-report">Testando… pode levar até 1 minuto.</pre>
    <div class="sheet-actions"><button class="sheet-btn" id="s-close">${icon('x')}Fechar</button></div>`);
  sheetButton(sheet, 's-close', closeSheet);
  try {
    const { report } = await api('/api/adb/probe', {});
    sheet.querySelector('#s-report').textContent = report;
  } catch (e) {
    sheet.querySelector('#s-report').textContent = e.message;
  }
}

$('open-settings').addEventListener('click', settingsSheet);
$('status-pill').addEventListener('click', settingsSheet);

// ---------- Native calls (phone app only) ----------
// Long operations (file copies) answer later through window.nativeResult / nativeProgress.

const nativeWaiting = new Map();
let nativeSeq = 0;

function nativeCall(method, args = {}, onProgress = null) {
  if (!native) return Promise.reject(new Error('Use o app Controle TV do celular'));
  const id = `c${++nativeSeq}`;
  return new Promise((resolve, reject) => {
    nativeWaiting.set(id, { resolve, reject, onProgress });
    native.call(id, method, JSON.stringify(args));
  });
}
window.nativeResult = (id, ok, payload) => {
  const waiting = nativeWaiting.get(id);
  if (!waiting) return;
  nativeWaiting.delete(id);
  ok ? waiting.resolve(payload) : waiting.reject(new Error(payload));
};
window.nativeProgress = (id, done, total) => nativeWaiting.get(id)?.onProgress?.(done, total);

// ---------- TV files ----------

const fmtSize = (bytes) => {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v < 10 ? 1 : 0).replace('.', ',')} ${units[i]}`;
};
const ext = (name) => name.includes('.') ? name.split('.').pop().toLowerCase() : '';
const VIDEO_EXT = ['mp4', 'mkv', 'avi', 'mov', 'webm', 'm4v', 'ts', 'm3u8', 'mp3', 'm4a', 'aac', 'flac', 'wav', 'ogg'];
const IMAGE_EXT = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'];
const MIME = { pdf: 'application/pdf', txt: 'text/plain', srt: 'text/plain', html: 'text/html', zip: 'application/zip' };
const kindOf = (e) => e.dir ? 'dir' : VIDEO_EXT.includes(ext(e.name)) ? 'video' : ext(e.name) === 'apk' ? 'apk' : IMAGE_EXT.includes(ext(e.name)) ? 'image' : 'file';
const ICON_OF = { dir: 'folder', video: 'film', apk: 'package', image: 'image', file: 'file' };

let filesPath = '/sdcard';
let transfer = null; // { label, done, total }

$('q-files').addEventListener('click', () => {
  if (!native) { toast('O gerenciador de arquivos funciona no app Controle TV do celular', true); return; }
  filesSheet(filesPath);
});

async function filesSheet(path) {
  filesPath = path;
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Arquivos da TV</h3><p>Armazenamento interno</p></div></div>
    <p class="files-path" id="f-path"></p>
    <div class="space-bar"><span id="f-used" style="width:0"></span></div>
    <p class="space-text" id="f-space">…</p>
    <div class="transfer" id="f-transfer" hidden><span id="f-transfer-text"></span><div class="space-bar"><span id="f-transfer-bar" style="width:0"></span></div></div>
    <div class="files-tools">
      <button id="f-up">${icon('parent')}Voltar pasta</button>
      <button id="f-mkdir">${icon('folderPlus')}Nova pasta</button>
      <button id="f-upload">${icon('upload')}Enviar do celular</button>
    </div>
    <div class="list" id="f-list"><p class="hint">Carregando…</p></div>`);
  sheet.querySelector('#f-path').textContent = path;
  sheet.querySelector('#f-up').disabled = path === '/sdcard';
  sheet.querySelector('#f-up').onclick = () => filesSheet(path.substring(0, path.lastIndexOf('/')) || '/sdcard');
  sheet.querySelector('#f-mkdir').onclick = () => nameSheet('Nova pasta', '', async (name) => {
    await nativeCall('mkdir', { path, name });
    filesSheet(path);
  });
  sheet.querySelector('#f-upload').onclick = () => startTransfer('Enviando', nativeCall('upload', { folder: path }, onTransferProgress), path);
  renderTransfer();

  try {
    // The phone app answers with the object itself (older versions sent it as text)
    const raw = await nativeCall('list', { path });
    const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (filesPath !== path || $('sheet').hidden) return;
    const { total, free } = data.space || {};
    if (total) {
      sheet.querySelector('#f-used').style.width = `${Math.round((total - free) / total * 100)}%`;
      sheet.querySelector('#f-space').textContent = `${fmtSize(free)} livres de ${fmtSize(total)}`;
    }
    const list = sheet.querySelector('#f-list');
    if (!data.entries.length) { list.innerHTML = '<p class="hint">Pasta vazia.</p>'; return; }
    list.replaceChildren(...data.entries.map((entry) => {
      const kind = kindOf(entry);
      const row = document.createElement('button');
      row.className = `file-row ${kind}`;
      row.innerHTML = `${icon(ICON_OF[kind])}<span><strong></strong><small></small></span>`;
      row.querySelector('strong').textContent = entry.name;
      const date = new Date(entry.mtime).toLocaleDateString('pt-BR');
      row.querySelector('small').textContent = entry.dir ? date : `${fmtSize(entry.size)} · ${date}`;
      const full = `${path}/${entry.name}`;
      row.onclick = () => entry.dir ? filesSheet(full) : fileActions(entry, full, kind);
      return row;
    }));
  } catch (e) {
    sheet.querySelector('#f-list').innerHTML = '';
    toast(e.message, true);
  }
}

function fileActions(entry, full, kind) {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3></h3><p>${fmtSize(entry.size)}</p></div></div>
    <div class="sheet-actions">
      ${kind === 'video' ? `<button class="sheet-btn primary" id="a-play">${icon('play')}Tocar na TV</button>` : ''}
      ${kind === 'apk' ? `<button class="sheet-btn primary" id="a-install">${icon('package')}Instalar na TV</button>` : ''}
      ${kind !== 'video' && kind !== 'apk' ? `<button class="sheet-btn primary" id="a-open">${icon('tv')}Abrir na TV</button>` : ''}
      <button class="sheet-btn" id="a-download">${icon('download')}Baixar para o celular</button>
      <button class="sheet-btn" id="a-rename">${icon('pencil')}Renomear</button>
      <button class="sheet-btn danger" id="a-delete">${icon('trash')}Apagar</button>
      <button class="sheet-btn" id="a-back">${icon('parent')}Voltar à pasta</button>
    </div>`);
  sheet.querySelector('h3').textContent = entry.name;
  const folder = full.substring(0, full.lastIndexOf('/'));
  sheetButton(sheet, 'a-back', () => filesSheet(folder));
  sheetButton(sheet, 'a-play', async () => {
    try {
      await nativeCall('allowRead');
      await api('/api/play', { url: `file://${full}`, title: entry.name });
      closeSheet();
      toast('Tocando na TV');
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'a-install', async () => {
    toast(`Instalando ${entry.name}…`);
    clearTimeout(toast.timer);
    try { await nativeCall('install', { path: full }); toast(`${entry.name} instalado`); loadApps(); }
    catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'a-open', async () => {
    const e2 = ext(entry.name);
    const mime = MIME[e2] || (IMAGE_EXT.includes(e2) ? 'image/*' : '*/*');
    try { await nativeCall('open', { path: full, mime }); closeSheet(); }
    catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 'a-download', () => {
    startTransfer('Baixando', nativeCall('download', { path: full, size: entry.size }, onTransferProgress), folder, entry.name, entry.size);
  });
  sheetButton(sheet, 'a-rename', () => nameSheet('Renomear', entry.name, async (name) => {
    await nativeCall('rename', { path: full, name });
    filesSheet(folder);
  }));
  sheetButton(sheet, 'a-delete', () => {
    const confirm = openSheet(`
      <div class="sheet-head"><div><h3>Apagar?</h3><p></p></div></div>
      <p class="body">${entry.dir ? 'A pasta e tudo dentro dela serão apagados.' : 'O arquivo será apagado da TV.'} Não dá para desfazer.</p>
      <div class="sheet-actions">
        <button class="sheet-btn danger" id="d-yes">${icon('trash')}Sim, apagar</button>
        <button class="sheet-btn" id="d-no">${icon('x')}Cancelar</button>
      </div>`);
    confirm.querySelector('.sheet-head p').textContent = entry.name;
    sheetButton(confirm, 'd-no', () => filesSheet(folder));
    sheetButton(confirm, 'd-yes', async () => {
      try { await nativeCall('delete', { path: full }); toast('Apagado'); filesSheet(folder); }
      catch (e) { toast(e.message, true); }
    });
  });
}

function nameSheet(title, value, save) {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3></h3></div></div>
    <input class="name-input" id="n-name" autocomplete="off">
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="n-save">${icon('check')}Salvar</button>
      <button class="sheet-btn" id="n-cancel">${icon('x')}Cancelar</button>
    </div>`);
  sheet.querySelector('h3').textContent = title;
  const input = sheet.querySelector('#n-name');
  input.value = value;
  setTimeout(() => input.focus(), 50);
  sheetButton(sheet, 'n-cancel', () => filesSheet(filesPath));
  sheetButton(sheet, 'n-save', async () => {
    const name = input.value.trim();
    if (!name) return;
    try { await save(name); } catch (e) { toast(e.message, true); }
  });
}

function onTransferProgress(done, total) {
  if (!transfer) return;
  transfer.done = done;
  if (total > 0) transfer.total = total;
  renderTransfer();
}

function renderTransfer() {
  const box = document.getElementById('f-transfer');
  if (!box) return;
  box.hidden = !transfer;
  if (!transfer) return;
  const pct = transfer.total ? Math.min(100, Math.round(transfer.done / transfer.total * 100)) : 0;
  document.getElementById('f-transfer-text').textContent =
    `${transfer.label}… ${fmtSize(transfer.done)}${transfer.total ? ` de ${fmtSize(transfer.total)} (${pct}%)` : ''}`;
  document.getElementById('f-transfer-bar').style.width = `${pct}%`;
}

async function startTransfer(label, promise, folder, name = '', size = 0) {
  if (transfer) { toast('Espere a cópia atual terminar', true); return; }
  transfer = { label: name ? `${label} ${name}` : label, done: 0, total: size };
  if (!document.getElementById('f-transfer')) filesSheet(folder); else renderTransfer();
  try {
    const result = await promise;
    transfer = null;
    buzz(40);
    toast(label === 'Baixando' ? `Salvo no celular em ${result}` : `${result} enviado para a TV`);
    if (!$('sheet').hidden && document.getElementById('f-list')) filesSheet(filesPath);
  } catch (e) {
    transfer = null;
    renderTransfer();
    if (e.message !== 'cancelado') toast(e.message, true);
  }
}

// ---------- Video: seeking and quality ----------

const fmtTime = (ms) => {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const sec = String(t % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
};

// "1:25:00", "25:30" or just "85" (minutes) -> milliseconds
function parseTime(text) {
  const clean = text.trim().replace(',', '.');
  if (!clean) return null;
  if (!clean.includes(':')) {
    const minutes = Number(clean);
    return Number.isFinite(minutes) ? Math.round(minutes * 60000) : null;
  }
  const parts = clean.split(':').map(Number);
  if (parts.some((n) => !Number.isFinite(n))) return null;
  return parts.reduce((acc, n) => acc * 60 + n, 0) * 1000;
}

let videoTimer = null;

$('video-btn').addEventListener('click', videoSheet);

async function videoSheet() {
  clearInterval(videoTimer);
  let player;
  try { player = await api('/api/player'); } catch (e) { toast(e.message, true); return; }
  if (player.closed) { toast('Nenhum vídeo tocando no player do Controle TV', true); return; }

  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Vídeo</h3><p id="v-title"></p></div></div>
    <div class="time-now"><strong id="v-pos">0:00</strong><span id="v-dur">0:00</span></div>
    <input type="range" class="seekbar" id="v-seek" min="0" max="1000" value="0">
    <h4>Pular minutos</h4>
    <div class="jump-row">
      <button id="v-back">${icon('rewind')} Voltar</button>
      <input id="v-minutes" inputmode="decimal" placeholder="min" value="5">
      <button class="fwd" id="v-fwd">Avançar ${icon('forward')}</button>
    </div>
    <h4>Ir para</h4>
    <div class="goto-row">
      <input id="v-goto" inputmode="text" placeholder="ex.: 1:25:00 ou 85">
      <button id="v-go">Ir</button>
    </div>
    <h4>Qualidade</h4>
    <div class="quality-row" id="v-quality"></div>
    <p class="hint small" id="v-playing"></p>`);
  sheet.querySelector('#v-title').textContent = player.title || 'Vídeo na TV';

  const seek = async (body) => {
    buzz(15);
    try { await api('/api/player/seek', body); setTimeout(refresh, 400); } catch (e) { toast(e.message, true); }
  };
  const minutes = () => {
    const ms = parseTime(sheet.querySelector('#v-minutes').value);
    if (!ms) toast('Digite quantos minutos pular', true);
    return ms;
  };
  sheet.querySelector('#v-back').onclick = () => { const ms = minutes(); if (ms) seek({ deltaMs: -ms }); };
  sheet.querySelector('#v-fwd').onclick = () => { const ms = minutes(); if (ms) seek({ deltaMs: ms }); };
  sheet.querySelector('#v-go').onclick = () => {
    const ms = parseTime(sheet.querySelector('#v-goto').value);
    if (ms == null) { toast('Use o formato 1:25:00, 25:30 ou só os minutos', true); return; }
    seek({ positionMs: ms });
  };

  const bar = sheet.querySelector('#v-seek');
  let dragging = false;
  bar.addEventListener('input', () => {
    dragging = true;
    sheet.querySelector('#v-pos').textContent = fmtTime(bar.value / 1000 * player.duration);
  });
  bar.addEventListener('change', () => {
    dragging = false;
    seek({ positionMs: Math.round(bar.value / 1000 * player.duration) });
  });

  const renderQuality = () => {
    const options = [['auto', 'Automática'], ['max', 'Sempre a máxima'],
      ...player.heights.map((h) => [`h${h}`, `${h}p`])];
    sheet.querySelector('#v-quality').replaceChildren(...options.map(([policy, label]) => {
      const btn = document.createElement('button');
      btn.textContent = label;
      btn.classList.toggle('on', policy === player.quality);
      btn.onclick = async () => {
        try {
          await api('/api/player/quality', { quality: policy });
          player.quality = policy;
          renderQuality();
          toast(`Qualidade: ${label}`);
        } catch (e) { toast(e.message, true); }
      };
      return btn;
    }));
    sheet.querySelector('#v-playing').textContent = player.currentHeight
      ? `Tocando agora em ${player.currentHeight}p. A escolha fica salva para os próximos vídeos.`
      : 'A escolha fica salva para os próximos vídeos.';
  };

  const render = () => {
    sheet.querySelector('#v-dur').textContent = player.duration ? fmtTime(player.duration) : 'ao vivo';
    if (!dragging) {
      sheet.querySelector('#v-pos').textContent = fmtTime(player.position);
      bar.value = player.duration ? Math.round(player.position / player.duration * 1000) : 0;
    }
    bar.disabled = !player.duration;
  };

  async function refresh() {
    if ($('sheet').hidden || !sheet.querySelector('#v-seek')) { clearInterval(videoTimer); return; }
    try {
      const next = await api('/api/player');
      if (next.closed) return;
      const heightsChanged = JSON.stringify(next.heights) !== JSON.stringify(player.heights) || next.currentHeight !== player.currentHeight;
      player = next;
      render();
      if (heightsChanged) renderQuality();
    } catch {}
  }

  render();
  renderQuality();
  videoTimer = setInterval(refresh, 1000);
}

// ---------- Subtitles ----------

const LANGS = [['en', 'Inglês'], ['auto', 'Automática (CC)'], ['pt', 'Português'], ['es', 'Espanhol'], ['off', 'Desligada']];

const AUTO_STATUS = {
  downloading: (p) => `baixando modelo ${p}%`,
  loading: () => 'carregando…',
  failed: () => 'falhou',
};

let subsRefresh = null;

$('subs-btn').addEventListener('click', subtitlesSheet);

async function subtitlesSheet() {
  clearTimeout(subsRefresh);
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Legendas</h3><p id="s-sub-title">Carregando…</p></div></div>
    <div class="sheet-actions" id="s-tracks"></div>
    <div id="s-sync" hidden>
      <h4>Sincronia</h4>
      <div class="sync-row">
        <button data-delta="-1000">−1 s</button>
        <button data-delta="-250">−0,25</button>
        <strong id="s-offset">0,00 s</strong>
        <button data-delta="250">+0,25</button>
        <button data-delta="1000">+1 s</button>
      </div>
      <p class="hint small">${icon('info')}Legenda <b>antes</b> da fala: toque em <b>+</b>. Legenda <b>depois</b> da fala: toque em <b>−</b>.</p>
      <button class="link" id="s-sync-reset">${icon('refresh')}<span>Zerar ajuste</span></button>
    </div>
    <div class="sheet-actions" style="margin-top:8px">
      <button class="sheet-btn primary" id="s-sub-file">${icon('download')}Carregar legenda do celular (.srt)</button>
    </div>
    <h4>Idioma padrão</h4>
    <div class="lang-row" id="s-langs"></div>
    <p class="hint small">${icon('info')}A legenda nesse idioma liga sozinha quando o vídeo tiver uma. <b>Automática (CC)</b> escreve a legenda em inglês a partir do áudio do filme, sempre sincronizada (baixa 55 MB na primeira vez).</p>`);
  sheetButton(sheet, 's-sub-file', () => $('sub-file').click());

  const current = state.status?.subtitleLanguage || 'en';
  sheet.querySelector('#s-langs').replaceChildren(...LANGS.map(([code, label]) => {
    const btn = document.createElement('button');
    btn.textContent = label;
    btn.classList.toggle('on', code === current);
    btn.onclick = async () => {
      try {
        await api('/api/settings', { subtitleLanguage: code });
        await refreshStatus();
        toast(`Idioma padrão: ${label}. Vale a partir do próximo vídeo.`);
        sheet.querySelectorAll('#s-langs button').forEach((b) => b.classList.toggle('on', b === btn));
      } catch (e) { toast(e.message, true); }
    };
    return btn;
  }));

  const tracksBox = sheet.querySelector('#s-tracks');
  try {
    const player = await api('/api/player');
    if (player.closed) {
      sheet.querySelector('#s-sub-title').textContent = 'Nenhum vídeo tocando no player do Controle TV';
      return;
    }
    sheet.querySelector('#s-sub-title').textContent = player.title || 'Vídeo na TV';
    const anyOn = player.subtitles.some((t) => t.selected);
    const options = [...player.subtitles, { id: 'off', label: 'Sem legenda', selected: !anyOn }];
    setupSync(sheet, player);
    // While the speech model downloads or loads, keep the progress fresh
    if (['downloading', 'loading'].includes(player.autoCc?.status)) {
      subsRefresh = setTimeout(() => { if (!$('sheet').hidden && sheet.querySelector('#s-tracks')) subtitlesSheet(); }, 2000);
    }
    const auto = player.autoCc || {};
    tracksBox.replaceChildren(...options.map((t) => {
      const btn = document.createElement('button');
      btn.className = 'sheet-btn' + (t.selected ? ' on' : '');
      btn.innerHTML = t.selected ? icon('check') : icon(t.id === 'off' ? 'x' : t.id === 'auto' ? 'zap' : 'captions');
      const status = t.id === 'auto' && AUTO_STATUS[auto.status] ? ` · ${AUTO_STATUS[auto.status](auto.progress)}` : '';
      btn.append(t.label + status);
      btn.onclick = async () => {
        try {
          await api('/api/player/subtitle', { id: t.id });
          toast(t.id === 'off' ? 'Legenda desligada' : `Legenda: ${t.label}`);
          subtitlesSheet();
        } catch (e) { toast(e.message, true); }
      };
      return btn;
    }));
    if (!player.subtitles.some((t) => t.id !== 'auto')) {
      const empty = document.createElement('p');
      empty.className = 'hint';
      empty.textContent = 'Este vídeo chegou sem legendas. Use a Automática (CC) ou carregue um .srt do celular.';
      tracksBox.prepend(empty);
    }
  } catch (e) {
    sheet.querySelector('#s-sub-title').textContent = e.message;
  }
}

const formatOffset = (ms) => `${ms > 0 ? '+' : ms < 0 ? '−' : ''}${(Math.abs(ms) / 1000).toFixed(2).replace('.', ',')} s`;

// Sync buttons only make sense for subtitle files (we draw those and can shift them)
function setupSync(sheet, player) {
  const box = sheet.querySelector('#s-sync');
  box.hidden = !player.canSync;
  if (!player.canSync) return;
  let offset = player.offsetMs;
  const label = sheet.querySelector('#s-offset');
  label.textContent = formatOffset(offset);
  const send = async (body) => {
    try { await api('/api/player/sync', body); } catch (e) { toast(e.message, true); }
  };
  box.querySelectorAll('[data-delta]').forEach((btn) => {
    btn.onclick = () => {
      buzz(10);
      const delta = Number(btn.dataset.delta);
      offset += delta;
      label.textContent = formatOffset(offset);
      send({ deltaMs: delta });
    };
  });
  sheet.querySelector('#s-sync-reset').onclick = () => {
    offset = 0;
    label.textContent = formatOffset(0);
    send({ reset: true });
  };
}

$('sub-file').addEventListener('change', async (e) => {
  const file = e.target.files?.[0];
  e.target.value = '';
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) { toast('Arquivo grande demais para legenda', true); return; }
  toast('Enviando legenda…');
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    await api('/api/player/subtitle-file', { name: file.name, data: btoa(binary) });
    toast('Legenda carregada na TV');
    closeSheet();
  } catch (err) { toast(err.message, true); }
});

// ---------- Music on the TV ----------
// What's playing (any app), controls, and search that plays straight away in AuraMusic.

let music = null;
let musicArtKey = null;
let seeking = false;

async function refreshMusic() {
  if (state.tab !== 'home' || document.visibilityState !== 'visible' || !token) return;
  try { music = await api('/api/media'); } catch { return; }
  const playingSomething = !!music.title;
  $('music-app').textContent = playingSomething ? `Tocando na TV · ${music.appLabel}` : 'Música na TV';
  $('music-title').textContent = playingSomething ? music.title : 'Nada tocando';
  $('music-artist').textContent = playingSomething ? (music.artist || '') :
    (!music.enabled ? 'Ative o controle completo para ver o que toca' : music.aura ? `Pesquise abaixo para tocar no ${music.appName || 'AuraMusic'}` : 'Instale o AuraMusic ou o SmartTube na TV para pesquisar');
  $('music-query').placeholder = `Tocar no ${music.appName || 'AuraMusic'}…`;
  $('music-controls').hidden = !playingSomething;
  $('music-progress').hidden = !playingSomething || !music.duration;
  $('music-play').innerHTML = icon(music.playing ? 'pause' : 'play');
  if (music.artKey !== musicArtKey) {
    musicArtKey = music.artKey;
    $('music-art').innerHTML = music.artKey
      ? `<img src="/api/media/art?token=${encodeURIComponent(token)}&k=${music.artKey}" alt="">`
      : icon('music');
  }
  if (music.duration && !seeking) {
    $('music-seek').value = Math.round(music.position / music.duration * 1000);
    $('music-pos').textContent = fmtTime(music.position);
    $('music-dur').textContent = fmtTime(music.duration);
  }
}
setInterval(refreshMusic, 2000);

document.querySelectorAll('[data-media]').forEach((btn) => btn.addEventListener('click', async () => {
  buzz(12);
  try { await api('/api/media/control', { action: btn.dataset.media }); setTimeout(refreshMusic, 400); }
  catch (e) { toast(e.message, true); }
}));

$('music-seek').addEventListener('input', () => {
  seeking = true;
  if (music?.duration) $('music-pos').textContent = fmtTime($('music-seek').value / 1000 * music.duration);
});
$('music-seek').addEventListener('change', async () => {
  seeking = false;
  if (!music?.duration) return;
  try { await api('/api/media/control', { action: 'seek', value: Math.round($('music-seek').value / 1000 * music.duration) }); }
  catch (e) { toast(e.message, true); }
  setTimeout(refreshMusic, 400);
});

async function musicSearch(play) {
  const query = $('music-query').value.trim();
  if (!query) { $('music-query').focus(); return; }
  buzz(15);
  toast(play ? 'Procurando…' : 'Abrindo na TV…');
  try {
    const { songs = [] } = await api('/api/media/search', { query, play });
    toast(play ? `Tocando ${songs[0] ? songs[0].title : `"${query}"`} na TV` : 'Resultados abertos na TV');
    $('music-query').blur();
    renderSongs(songs);
    setTimeout(refreshMusic, 3000);
  } catch (e) { toast(e.message, true); }
}

// Other matches, in case the first one wasn't the right song
function renderSongs(songs) {
  const box = $('music-songs');
  box.hidden = songs.length < 2;
  if (box.hidden) return;
  const title = document.createElement('p');
  title.textContent = 'Não era essa? Escolha:';
  box.replaceChildren(title, ...songs.map((song, i) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'song' + (i === 0 ? ' on' : '');
    btn.innerHTML = '<img alt=""><span><strong></strong><small></small></span>';
    if (song.thumb) btn.querySelector('img').src = song.thumb;
    btn.querySelector('strong').textContent = song.title;
    btn.querySelector('small').textContent = song.subtitle;
    btn.onclick = async () => {
      buzz(12);
      box.querySelectorAll('.song').forEach((b) => b.classList.toggle('on', b === btn));
      try { await api('/api/media/song', { id: song.id }); toast(`Tocando ${song.title}`); setTimeout(refreshMusic, 3000); }
      catch (e) { toast(e.message, true); }
    };
    return btn;
  }));
}
$('music-search').addEventListener('submit', (e) => { e.preventDefault(); musicSearch(true); });
$('music-results').addEventListener('click', () => musicSearch(false));

// ---------- Filmes (Stremio) ----------
// Library and "continue watching" from the user's Stremio account, plus Stremio's public
// catalog. Titles open in the Stremio app (on the TV or on the phone).

let stremio = null;
let moviesQueryTimer = null;

async function loadMovies() {
  try { stremio = await api('/api/stremio'); } catch (e) { $('movies-body').innerHTML = ''; toast(e.message, true); return; }
  $('stremio-dot').className = `dot ${stremio.loggedIn ? 'ok' : ''}`;
  $('stremio-label').textContent = stremio.loggedIn ? 'Conta Stremio' : 'Entrar no Stremio';
  const query = $('movies-search').value.trim();
  if (query) return searchMovies(query);

  const body = $('movies-body');
  body.replaceChildren(discoverCard());
  if (!stremio.loggedIn) body.append(loginCard());
  if (stremio.loggedIn) {
    try {
      const { items } = await api('/api/stremio/library');
      // Same as Stremio: "continue watching" includes titles never added to the library
      const watching = items.filter((i) => i.watching);
      const library = items.filter((i) => i.inLibrary);
      if (watching.length) body.append(shelf('Continuar assistindo', watching.map((i) => posterCard(i, i.duration > 0 ? { progress: i.position / i.duration } : {}))));
      if (library.length) body.append(shelf('Minha biblioteca', library.map((i) => posterCard(i))));
    } catch (e) { toast(e.message, true); }
  }
  const [movies, series] = await Promise.all([
    api('/api/stremio/catalog?type=movie').catch(() => ({ metas: [] })),
    api('/api/stremio/catalog?type=series').catch(() => ({ metas: [] })),
  ]);
  body.append(shelf('Filmes populares', movies.metas.slice(0, 20).map((m) => posterCard(m))));
  body.append(shelf('Séries populares', series.metas.slice(0, 20).map((m) => posterCard(m))));
  if (kind === 'projetor') loadGenreShelves(body);
}

async function searchMovies(query) {
  const body = $('movies-body');
  body.innerHTML = '<p class="hint">Buscando…</p>';
  const [movies, series] = await Promise.all([
    api(`/api/stremio/catalog?type=movie&q=${encodeURIComponent(query)}`).catch(() => ({ metas: [] })),
    api(`/api/stremio/catalog?type=series&q=${encodeURIComponent(query)}`).catch(() => ({ metas: [] })),
  ]);
  if ($('movies-search').value.trim() !== query) return;
  const all = [...movies.metas, ...series.metas];
  if (!all.length) { body.innerHTML = '<p class="hint">Nada encontrado.</p>'; return; }
  const grid = document.createElement('div');
  grid.className = 'poster-grid';
  grid.append(...all.map((m) => posterCard(m)));
  body.replaceChildren(grid);
}

$('movies-search').addEventListener('input', () => {
  clearTimeout(moviesQueryTimer);
  moviesQueryTimer = setTimeout(loadMovies, 450);
});

// ---------- YouTube: the channels picked here become rows on the TV; videos open in SmartTube ----------

const ytVideos = new Map(); // channel id → { at, videos }

async function loadYouTube() {
  const body = $('yt-body');
  const query = $('yt-search').value.trim();
  if (query) return searchYouTube(query);
  let yt;
  try { yt = await api('/api/youtube'); } catch (e) { body.replaceChildren(hintText(e.message)); return; }
  body.replaceChildren();
  if (!yt.smartTube) {
    body.append(noteCard('tv', 'Instale o SmartTube na TV', 'É ele que toca o YouTube sem anúncios. Sem ele, os vídeos abrem no app comum.'));
  }
  if (!yt.channels.length) {
    const card = noteCard('youtube', 'Escolha seus canais', 'Cada canal vira uma fileira com os vídeos mais novos, aqui e na tela inicial da TV.');
    const add = document.createElement('button');
    add.className = 'pill glow';
    add.innerHTML = `${icon('plus')}Adicionar canal`;
    add.onclick = channelsSheet;
    card.querySelector('div').append(add);
    body.append(card);
    return;
  }
  for (const channel of yt.channels) {
    const section = shelf(channel.name, []);
    body.append(section);
    channelVideos(channel.id)
      .then((videos) => section.querySelector('.shelf-row').replaceChildren(...videos.slice(0, 15).map((v) => videoCard(v))))
      .catch(() => section.querySelector('.shelf-row').replaceChildren(hintText('Não deu para carregar este canal agora')));
  }
}

async function channelVideos(id) {
  const cached = ytVideos.get(id);
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached.videos;
  const { videos } = await api(`/api/youtube/videos?channel=${encodeURIComponent(id)}`);
  ytVideos.set(id, { at: Date.now(), videos });
  return videos;
}

async function searchYouTube(query) {
  const body = $('yt-body');
  body.replaceChildren(hintText('Buscando…'));
  try {
    const { videos } = await api(`/api/youtube/search?q=${encodeURIComponent(query)}`);
    const grid = document.createElement('div');
    grid.className = 'video-grid';
    grid.append(...videos.map((v) => videoCard(v)));
    body.replaceChildren(videos.length ? grid : hintText('Nada encontrado'));
  } catch (e) { body.replaceChildren(hintText(e.message)); }
}

function videoCard(v) {
  const btn = document.createElement('button');
  btn.className = 'video';
  btn.innerHTML = '<span class="art"><img alt="" loading="lazy"></span><strong></strong><small></small>';
  btn.querySelector('img').src = v.thumbSmall;
  if (v.length) btn.querySelector('.art').insertAdjacentHTML('beforeend', `<span class="len"></span>`);
  if (v.length) btn.querySelector('.len').textContent = v.length;
  btn.querySelector('strong').textContent = v.name;
  btn.querySelector('small').textContent = [v.channel, v.when].filter(Boolean).join(' · ');
  btn.onclick = () => videoSheet(v);
  return btn;
}

function videoSheet(v) {
  const sheet = openSheet(`
    <div class="video-sheet">
      <img class="thumb" alt="">
      <h3></h3>
      <p class="meta"></p>
      <p class="about"></p>
      <div class="sheet-actions">
        <button class="sheet-btn primary" id="v-play">${icon('play')}Tocar na TV</button>
        <button class="sheet-btn" id="v-follow" hidden>${icon('plus')}<span></span></button>
      </div>
    </div>`);
  sheet.querySelector('.thumb').src = v.thumb;
  sheet.querySelector('.thumb').onerror = (e) => { e.target.onerror = null; e.target.src = v.thumbSmall; };
  sheet.querySelector('h3').textContent = v.name;
  sheet.querySelector('.meta').textContent = [v.channel, v.when, v.views, v.length].filter(Boolean).join(' · ');
  sheet.querySelector('.about').textContent = (v.description || '').split(/\n\s*\n/)[0].slice(0, 500);
  sheetButton(sheet, 'v-play', async () => {
    try {
      await api('/api/youtube/open', { id: v.id });
      toast('Abrindo na TV');
      closeSheet();
    } catch (e) { toast(e.message, true); }
  });
  // From a search: offer to follow the channel
  api('/api/youtube').then(({ channels }) => {
    if (!v.channelId || channels.some((c) => c.id === v.channelId)) return;
    const follow = sheet.querySelector('#v-follow');
    follow.querySelector('span').textContent = `Adicionar ${v.channel}`;
    follow.hidden = false;
    follow.onclick = async () => {
      try {
        await api('/api/youtube/channel', { id: v.channelId, name: v.channel });
        toast(`${v.channel} adicionado`);
        follow.hidden = true;
      } catch (e) { toast(e.message, true); }
    };
  }).catch(() => {});
}

/** The picked channels (remove) and a search to add more. */
async function channelsSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Seus canais</h3><p>Cada um vira uma fileira aqui e na TV</p></div></div>
    <div id="ch-list"></div>
    <h4>Adicionar canal</h4>
    <label class="search">${icon('search')}<input id="ch-query" type="search" placeholder="Nome do canal" autocomplete="off" enterkeyhint="search"></label>
    <div id="ch-results"></div>
    <div class="sheet-actions"><button class="sheet-btn" id="ch-done">${icon('check')}Pronto</button></div>`);
  const list = sheet.querySelector('#ch-list');
  const results = sheet.querySelector('#ch-results');
  const row = (c, action, actionIcon, label) => {
    const el = document.createElement('div');
    el.className = 'channel-row';
    el.innerHTML = `<img alt=""><div><strong></strong><small></small></div><button class="round" aria-label="${label}">${icon(actionIcon)}</button>`;
    if (c.thumb) el.querySelector('img').src = c.thumb;
    el.querySelector('strong').textContent = c.name;
    el.querySelector('small').textContent = c.subscribers || '';
    el.querySelector('button').onclick = () => action(c);
    return el;
  };
  let saved = [];
  const showSaved = () => {
    list.replaceChildren(...(saved.length ? saved.map((c) => row(c, remove, 'trash', 'Remover')) : [hintText('Nenhum canal ainda')]));
  };
  const remove = async (c) => {
    try { saved = (await api('/api/youtube/channel/remove', { id: c.id })).channels; showSaved(); } catch (e) { toast(e.message, true); }
  };
  const add = async (c) => {
    try {
      saved = (await api('/api/youtube/channel', { id: c.id, name: c.name, thumb: c.thumb })).channels;
      showSaved();
      toast(`${c.name} adicionado`);
    } catch (e) { toast(e.message, true); }
  };
  try { saved = (await api('/api/youtube')).channels; } catch (e) { toast(e.message, true); }
  showSaved();
  const input = sheet.querySelector('#ch-query');
  let timer;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    const q = input.value.trim();
    if (q.length < 2) { results.replaceChildren(); return; }
    timer = setTimeout(async () => {
      try {
        const { channels } = await api(`/api/youtube/channels?q=${encodeURIComponent(q)}`);
        if (input.value.trim() !== q) return;
        results.replaceChildren(...channels.map((c) => row(c, add, 'plus', 'Adicionar')));
      } catch (e) { results.replaceChildren(hintText(e.message)); }
    }, 450);
  });
  sheetButton(sheet, 'ch-done', () => { closeSheet(); if (state.tab === 'youtube') loadYouTube(); });
}

function noteCard(iconName, title, text) {
  const card = document.createElement('article');
  card.className = 'note-card';
  card.innerHTML = `${icon(iconName)}<div><strong></strong><p></p></div>`;
  card.querySelector('strong').textContent = title;
  card.querySelector('p').textContent = text;
  return card;
}

function hintText(text) {
  const p = document.createElement('p');
  p.className = 'hint';
  p.textContent = text;
  return p;
}

$('yt-channels').addEventListener('click', channelsSheet);
$('yt-search').addEventListener('search', () => loadYouTube());
$('yt-search').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('yt-search').blur(); loadYouTube(); } });

const PROJECTOR_GENRES = [['Ação', 'Action'], ['Aventura', 'Adventure'], ['Comédia', 'Comedy'], ['Drama', 'Drama'],
  ['Ficção científica', 'Sci-Fi'], ['Suspense', 'Thriller'], ['Terror', 'Horror'], ['Romance', 'Romance'],
  ['Animação', 'Animation'], ['Família', 'Family'], ['Fantasia', 'Fantasy'], ['Crime', 'Crime'],
  ['Mistério', 'Mystery'], ['Documentário', 'Documentary'], ['Guerra', 'War'], ['Faroeste', 'Western']];

/** One row per genre, films and series mixed, added one after another as they arrive. */
async function loadGenreShelves(body) {
  for (const [title, genre] of PROJECTOR_GENRES) {
    if (state.tab !== 'movies' || $('movies-search').value.trim()) return;
    const get = (type) => api(`/api/stremio/genre?type=${type}&genre=${encodeURIComponent(genre)}`).then((r) => r.metas).catch(() => []);
    const [movies, series] = await Promise.all([get('movie'), get('series')]);
    const mixed = [];
    for (let i = 0; i < Math.max(movies.length, series.length) && mixed.length < 20; i++) {
      if (movies[i]) mixed.push(movies[i]);
      if (series[i]) mixed.push(series[i]);
    }
    if (mixed.length) body.append(shelf(title, mixed.map((m) => posterCard(m))));
  }
}

// ---------- "Não sei o que ver": hidden gems, not the obvious picks ----------

const DISCOVER_GROUPS = [
  ['type', 'Tipo', [['movie', 'Filme'], ['series', 'Série']]],
  ['mood', 'Clima', [['qualquer', 'Tanto faz'], ['rir', 'Rir'], ['leve', 'Leve'], ['tensao', 'Tensão'], ['emocionar', 'Emocionar'],
    ['susto', 'Susto'], ['pensar', 'Pensar'], ['aventura', 'Aventura'], ['romance', 'Romance'], ['misterio', 'Mistério'],
    ['crime', 'Crime'], ['animacao', 'Animação'], ['documentario', 'Documentário']]],
  ['time', 'Tempo', [['curto', 'Até 1h40'], ['medio', 'Até 2h10'], ['qualquer', 'Tanto faz']]],
  ['with', 'Com quem', [['sozinho', 'Sozinho'], ['casal', 'Casal'], ['familia', 'Família']]],
  ['era', 'Época', [['qualquer', 'Tanto faz'], ['classico', 'Clássicos'], ['8090', 'Anos 80 e 90'], ['0015', '2000 a 2015'], ['recente', 'Recentes']]],
  ['region', 'De onde', [['qualquer', 'Qualquer lugar'], ['fora', 'Fora dos EUA'], ['asia', 'Ásia'], ['europa', 'Europa'], ['latam', 'América Latina'], ['brasil', 'Brasil']]],
  ['rating', 'Nota mínima no IMDb', [['7', '7+'], ['7.5', '7,5+'], ['8', '8+']]],
  ['depth', 'Quão escondido', [['pouco', 'Pouco conhecido'], ['escondido', 'Bem escondido']]],
];
const DISCOVER_DEFAULT = { type: 'movie', mood: 'qualquer', time: 'qualquer', with: 'sozinho', era: 'qualquer', region: 'qualquer', rating: '7', depth: 'pouco' };

function discoverCard() {
  const card = document.createElement('button');
  card.className = 'discover-card';
  card.innerHTML = `${icon('star')}<span><strong>Não sei o que ver</strong><small>Joias bem avaliadas que pouca gente viu, do seu jeito</small></span>${icon('right')}`;
  card.onclick = discoverSheet;
  return card;
}

function discoverSheet() {
  let choice = { ...DISCOVER_DEFAULT };
  try { choice = { ...choice, ...JSON.parse(store.get('discover') || '{}') }; } catch {}
  // Older saved choices had "far" instead of a region
  if (choice.far === '1' && choice.region === 'qualquer') choice.region = 'fora';
  delete choice.far;
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Não sei o que ver</h3><p>Nada de óbvio: nota alta, pouca gente viu, e nada que você já tem</p></div></div>
    <div id="dc-groups"></div>
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="dc-go">${icon('zap')}Sortear</button>
      <button class="sheet-btn" id="dc-surprise">${icon('sparkles')}Surpreenda-me</button>
      <button class="sheet-btn" id="dc-reset">${icon('refresh')}Limpar escolhas</button>
    </div>
    <div id="dc-results"></div>`);
  const groups = sheet.querySelector('#dc-groups');
  const paint = () => {
    groups.replaceChildren(...DISCOVER_GROUPS.filter(([key]) => !(key === 'time' && choice.type === 'series')).map(([key, label, options]) => {
      const wrap = document.createElement('div');
      wrap.innerHTML = `<h4>${label}</h4><div class="chips"></div>`;
      wrap.querySelector('.chips').append(...options.map(([value, text]) => {
        const b = document.createElement('button');
        b.className = 'chip' + (choice[key] === value ? ' on' : '');
        b.textContent = text;
        b.onclick = () => { choice[key] = value; paint(); };
        return b;
      }));
      return wrap;
    }));
  };
  paint();
  const results = sheet.querySelector('#dc-results');
  let list = [];
  let at = 0;
  const showThree = () => {
    if (at >= list.length) at = 0;
    const three = list.slice(at, at + 3);
    at += 3;
    results.replaceChildren(...three.map(gemCard));
    if (list.length > 3) {
      const more = document.createElement('button');
      more.className = 'sheet-btn';
      more.innerHTML = `${icon('refresh')}Mais 3`;
      more.onclick = showThree;
      results.append(more);
    }
  };
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  sheetButton(sheet, 'dc-reset', () => { choice = { ...DISCOVER_DEFAULT }; paint(); });
  // Surprise: a random mood, era and place (keeps film/series and company), then draws
  sheetButton(sheet, 'dc-surprise', () => {
    choice.mood = pick(DISCOVER_GROUPS[1][2].map(([v]) => v).filter((v) => v !== 'qualquer'));
    choice.era = pick(['qualquer', 'classico', '8090', '0015', 'recente']);
    choice.region = pick(['qualquer', 'fora', 'asia', 'europa', 'latam']);
    choice.depth = pick(['pouco', 'escondido']);
    paint();
    sheet.querySelector('#dc-go').click();
  });
  sheetButton(sheet, 'dc-go', async () => {
    store.set('discover', JSON.stringify(choice));
    results.replaceChildren(hintText('Garimpando joias… a primeira vez leva alguns segundos'));
    try {
      const q = new URLSearchParams(choice).toString();
      ({ titles: list } = await api(`/api/discover?${q}`));
      at = 0;
      if (!list.length) results.replaceChildren(hintText('Nada com essas escolhas. Afrouxe o tempo ou o lugar e sorteie de novo.'));
      else showThree();
    } catch (e) { results.replaceChildren(hintText(e.message)); }
  });
}

function gemCard(t) {
  const card = document.createElement('article');
  card.className = 'gem';
  card.innerHTML = `<img alt=""><div><strong></strong><small class="why"></small><p></p>
    <div class="gem-actions"><button class="pill glow">${icon('play')}Ver na TV</button><button class="pill">Detalhes</button></div></div>`;
  card.querySelector('img').src = t.poster || `https://images.metahub.space/poster/medium/${t.id}/img`;
  card.querySelector('strong').textContent = t.name;
  card.querySelector('.why').textContent = t.why || '';
  card.querySelector('p').textContent = t.description || '';
  const [play, info] = card.querySelectorAll('.gem-actions button');
  play.onclick = async () => {
    try { await api('/api/stremio/open', { type: t.type, id: t.id, name: t.name }); toast(`Abrindo ${t.name} no Stremio`); closeSheet(); }
    catch (e) { toast(e.message, true); }
  };
  info.onclick = () => titleSheet(t);
  return card;
}

// ---------- Second screen: cast with photos, the story, and each actor's other films ----------

async function filmSheet(item) {
  const type = item.type === 'series' ? 'series' : 'movie';
  const sheet = openSheet(`
    <div class="film-head"><img alt=""><div><h3></h3><p class="film-info"></p></div></div>
    <h4>Sobre</h4>
    <p class="meta-desc" id="f-about">Carregando…</p>
    <h4>Elenco</h4>
    <div class="cast-row" id="f-cast"><p class="hint">Carregando…</p></div>
    <div class="sheet-actions"><button class="sheet-btn" id="f-close">${icon('x')}Fechar</button></div>`);
  sheet.querySelector('.film-head img').src = `https://images.metahub.space/background/medium/${item.id}/img`;
  sheet.querySelector('.film-head img').onerror = (e) => { e.target.hidden = true; };
  sheet.querySelector('h3').textContent = item.name || '';
  sheetButton(sheet, 'f-close', closeSheet);
  try {
    const r = await api(`/api/film?type=${type}&id=${encodeURIComponent(item.id)}`);
    const meta = r.meta || {};
    if (!item.name && meta.name) sheet.querySelector('h3').textContent = meta.name;
    sheet.querySelector('.film-info').textContent = [meta.releaseInfo || meta.year, meta.imdbRating && `IMDb ${meta.imdbRating}`,
      (meta.director || []).length ? `Direção: ${meta.director.join(', ')}` : ''].filter(Boolean).join(' · ');
    sheet.querySelector('#f-about').textContent = r.about || meta.description || 'Sem resumo.';
    const cast = r.cast || [];
    sheet.querySelector('#f-cast').replaceChildren(...(cast.length ? cast.map(castItem) : [hintText('Elenco não encontrado')]));
  } catch (e) {
    sheet.querySelector('#f-about').textContent = e.message;
    sheet.querySelector('#f-cast').replaceChildren();
  }
}

function castItem(c) {
  const b = document.createElement('button');
  b.className = 'cast-item';
  b.innerHTML = '<span class="cast-photo"></span><small></small>';
  const photo = b.querySelector('.cast-photo');
  if (c.photo) photo.innerHTML = `<img alt="" loading="lazy" src="${c.photo}">`;
  else photo.textContent = (c.name || '?').split(' ').map((w) => w[0]).slice(0, 2).join('');
  b.querySelector('small').textContent = c.name;
  b.onclick = () => personSheet(c);
  return b;
}

async function personSheet(c) {
  const sheet = openSheet(`
    <div class="person-head"><span class="cast-photo big"></span><div><h3></h3></div></div>
    <p class="meta-desc" id="p-bio">Carregando…</p>
    <h4>Filmes e séries</h4>
    <div class="poster-grid" id="p-films"></div>
    <div class="sheet-actions"><button class="sheet-btn" id="p-close">${icon('x')}Fechar</button></div>`);
  sheet.querySelector('h3').textContent = c.name;
  if (c.photo) sheet.querySelector('.cast-photo').innerHTML = `<img alt="" src="${c.photo}">`;
  sheetButton(sheet, 'p-close', closeSheet);
  try {
    const p = await api(`/api/person?nm=${encodeURIComponent(c.nm || '')}&name=${encodeURIComponent(c.name || '')}`);
    sheet.querySelector('#p-bio').textContent = p.bio || 'Sem biografia em português.';
    if (p.photo && !c.photo) sheet.querySelector('.cast-photo').innerHTML = `<img alt="" src="${p.photo}">`;
    const films = p.films || [];
    sheet.querySelector('#p-films').replaceChildren(...(films.length ? films.map((f) => posterCard({ ...f, releaseInfo: f.year }))
      : [hintText('A lista de filmes não respondeu agora. Tente de novo em alguns minutos.')]));
  } catch (e) { sheet.querySelector('#p-bio').textContent = e.message; }
}

// ---------- Semana: weekly picks from a free AI (Gemini), based on the user's own words ----------

let weekPoll = null;

async function loadWeek() {
  clearTimeout(weekPoll);
  const body = $('week-body');
  let ai;
  try { ai = await api('/api/ai'); } catch (e) { body.replaceChildren(hintText(e.message)); return; }
  if (!ai.hasKey || !ai.taste) {
    const card = noteCard('sparkles', 'Sugestões da IA toda semana',
      'Conte do que você gosta e cole uma chave gratuita do Gemini (Google). Toda segunda chegam 10 filmes e séries fora do óbvio, com o porquê.');
    const go = document.createElement('button');
    go.className = 'pill glow';
    go.innerHTML = `${icon('sparkles')}Começar`;
    go.onclick = () => tasteSheet(ai);
    card.querySelector('div').append(go);
    body.replaceChildren(card);
    return;
  }
  const when = ai.generatedAt ? new Date(ai.generatedAt).toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }) : null;
  $('week-sub').textContent = when ? `Escolhidos pela IA em ${when}` : 'Sugestões da IA para o seu gosto';
  const top = document.createElement('div');
  top.className = 'sheet-actions';
  const again = document.createElement('button');
  again.className = 'sheet-btn';
  again.innerHTML = `${icon('refresh')}${ai.generating ? 'Gerando… (até 1 minuto)' : 'Gerar de novo'}`;
  again.disabled = ai.generating;
  again.onclick = async () => {
    try { await api('/api/ai/generate', {}); toast('Pedindo novas sugestões à IA…'); loadWeek(); } catch (e) { toast(e.message, true); }
  };
  top.append(again);
  const parts = [top];
  if (ai.error && !ai.generating) parts.push(noteCard('info', 'A última tentativa falhou', ai.error));
  if (!ai.items.length && !ai.generating && !ai.error) parts.push(hintText('Ainda sem sugestões: toque em Gerar de novo.'));
  parts.push(...ai.items.map(weekCard));
  body.replaceChildren(...parts);
  // Follow the generation until it's done
  if (ai.generating && state.tab === 'week') weekPoll = setTimeout(loadWeek, 4000);
}

function weekCard(t) {
  const card = gemCard(t);
  card.querySelector('.why').textContent = [t.type === 'series' ? 'Série' : 'Filme', t.releaseInfo, t.imdbRating && `IMDb ${t.imdbRating}`].filter(Boolean).join(' · ');
  card.querySelector('p').textContent = t.why || '';
  return card;
}

/** The user's taste in their own words, and the free Gemini key. */
function tasteSheet(ai) {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Meu gosto</h3><p>A IA usa isto toda semana para escolher</p></div></div>
    <h4>Do que você gosta (e do que não gosta)</h4>
    <textarea id="ai-taste" rows="6" placeholder="Ex.: Amo suspense psicológico e filmes coreanos, ficção científica que faz pensar, séries curtas. Gosto de Parasita, Dark e Ex Machina. Odeio comédia romântica boba e filmes de super-herói."></textarea>
    <h4>Chave gratuita do Gemini</h4>
    <p class="body">1. Abra <b>aistudio.google.com/apikey</b> e entre com sua conta Google.<br>2. Toque em <b>Criar chave de API</b> e copie.<br>3. Cole aqui. Ela fica guardada só na TV.</p>
    <input class="name-input" id="ai-key" autocomplete="off" placeholder="${ai.hasKey ? 'Chave já salva (cole outra para trocar)' : 'Cole a chave aqui'}">
    ${native?.openUri ? `<button class="sheet-btn" id="ai-open">${icon('download')}Abrir o site da chave</button>` : ''}
    <p class="hint small">${icon('info')}Na faixa grátis, o Google pode usar o seu gosto e a lista do que você viu para melhorar a IA dele.</p>
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="ai-save">${icon('sparkles')}Salvar e gerar sugestões</button>
      <button class="sheet-btn" id="ai-cancel">${icon('x')}Cancelar</button>
    </div>`);
  sheet.querySelector('#ai-taste').value = ai.taste || '';
  sheetButton(sheet, 'ai-cancel', closeSheet);
  sheetButton(sheet, 'ai-open', () => native.openUri('https://aistudio.google.com/apikey'));
  sheetButton(sheet, 'ai-save', async () => {
    const taste = sheet.querySelector('#ai-taste').value.trim();
    const key = sheet.querySelector('#ai-key').value.trim();
    if (!taste) { toast('Escreva do que você gosta', true); return; }
    if (!key && !ai.hasKey) { toast('Cole a chave do Gemini', true); return; }
    try {
      await api('/api/ai/settings', { taste, key });
      await api('/api/ai/generate', {});
      toast('Pedindo sugestões à IA…');
      closeSheet();
      loadWeek();
    } catch (e) { toast(e.message, true); }
  });
}

$('week-edit').addEventListener('click', async () => {
  try { tasteSheet(await api('/api/ai')); } catch (e) { toast(e.message, true); }
});

function shelf(title, cards) {
  const wrap = document.createElement('section');
  wrap.className = 'shelf';
  const h = document.createElement('h3');
  h.textContent = title;
  const row = document.createElement('div');
  row.className = 'shelf-row';
  row.append(...cards);
  wrap.append(h, row);
  return wrap;
}

function posterCard(item, { progress = null } = {}) {
  const btn = document.createElement('button');
  btn.className = 'poster';
  btn.innerHTML = '<span class="art"><img alt="" loading="lazy"></span><strong></strong><small></small>';
  if (item.poster) btn.querySelector('img').src = item.poster;
  if (progress != null) btn.querySelector('.art').insertAdjacentHTML('beforeend', `<span class="bar"><span style="width:${Math.round(progress * 100)}%"></span></span>`);
  btn.querySelector('strong').textContent = item.name;
  btn.querySelector('small').textContent = [item.type === 'series' ? 'Série' : 'Filme', item.releaseInfo].filter(Boolean).join(' · ');
  btn.onclick = () => titleSheet(item);
  return btn;
}

async function titleSheet(item) {
  const type = item.type === 'series' ? 'series' : 'movie';
  const sheet = openSheet(`
    <div class="meta-head"><img alt=""><div><h3></h3><p id="t-info"></p></div></div>
    <p class="meta-desc" id="t-desc">Carregando…</p>
    <div class="sheet-actions">
      ${item.videoId ? `<button class="sheet-btn primary" id="t-resume">${icon('play')}Continuar (${item.videoId.split(':').slice(1).join('x') || 'de onde parou'}) na TV</button>` : ''}
      <button class="sheet-btn${item.videoId ? '' : ' primary'}" id="t-tv">${icon('tv')}Abrir no Stremio da TV</button>
      ${native?.openUri ? `<button class="sheet-btn" id="t-phone">${icon('phone')}Abrir no Stremio do celular</button>` : ''}
      <button class="sheet-btn" id="t-cast">${icon('info')}Elenco e curiosidades</button>
    </div>`);
  if (item.poster) sheet.querySelector('img').src = item.poster;
  sheet.querySelector('h3').textContent = item.name;
  const openTv = async (videoId) => {
    try {
      await api('/api/stremio/open', { type, id: item.id, videoId: videoId || '', name: item.name });
      toast(`Abrindo ${item.name} na TV`);
      closeSheet();
    } catch (e) { toast(e.message, true); }
  };
  sheetButton(sheet, 't-resume', () => openTv(item.videoId));
  sheetButton(sheet, 't-cast', () => filmSheet({ id: item.id, type, name: item.name }));
  sheetButton(sheet, 't-tv', () => openTv(null));
  sheetButton(sheet, 't-phone', () => {
    const ok = native.openUri(`stremio:///detail/${type}/${encodeURIComponent(item.id)}`);
    if (!ok) toast('Instale o Stremio no celular', true);
  });
  try {
    const { meta } = await api(`/api/stremio/meta?type=${type}&id=${encodeURIComponent(item.id)}`);
    sheet.querySelector('#t-info').textContent = [meta.releaseInfo || meta.year, meta.imdbRating && `IMDb ${meta.imdbRating}`, (meta.genres || meta.genre || []).slice(0, 3).join(', ')].filter(Boolean).join(' · ');
    sheet.querySelector('#t-desc').textContent = meta.description || 'Sem sinopse.';
  } catch { sheet.querySelector('#t-desc').textContent = ''; }
}

function loginCard() {
  const card = document.createElement('div');
  card.className = 'login-card';
  card.innerHTML = `<strong>Entre com sua conta do Stremio</strong>
    <p>Para ver a sua biblioteca e o "continuar assistindo". A senha não fica salva: só a sessão, na TV.</p>
    <form id="stremio-login">
      <input class="name-input" id="stremio-email" type="email" placeholder="E-mail" autocomplete="username">
      <input class="name-input" id="stremio-pass" type="password" placeholder="Senha" autocomplete="current-password">
      <button type="submit" class="btn-primary">Entrar</button>
    </form>`;
  card.querySelector('form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await api('/api/stremio/login', { email: card.querySelector('#stremio-email').value, password: card.querySelector('#stremio-pass').value });
      toast('Conta do Stremio conectada');
      loadMovies();
    } catch (err) { toast(err.message, true); }
  });
  return card;
}

$('stremio-account').addEventListener('click', () => {
  if (!stremio?.loggedIn) { $('stremio-email')?.focus(); return; }
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Conta do Stremio</h3><p></p></div></div>
    <div class="sheet-actions"><button class="sheet-btn danger" id="s-out">${icon('logout')}Sair da conta</button></div>`);
  sheet.querySelector('.sheet-head p').textContent = stremio.email || '';
  sheetButton(sheet, 's-out', async () => {
    try { await api('/api/stremio/logout', {}); toast('Saiu da conta do Stremio'); closeSheet(); loadMovies(); }
    catch (e) { toast(e.message, true); }
  });
});

// ---------- TV home layout editor (drag and drop) ----------
// Sections: the 3 featured slots, each folder row, and the hidden apps. Drag an app by its
// handle to reorder, move between folders, feature it or hide it. Saves to the TV by itself.

let layoutModel = null;
let layoutSaveTimer = null;

$('organize-tv').addEventListener('click', async () => {
  try {
    layoutModel = await api('/api/home/layout');
    renderLayoutEditor();
  } catch (e) { toast(e.message, true); }
});

function saveLayout() {
  clearTimeout(layoutSaveTimer);
  layoutSaveTimer = setTimeout(async () => {
    try {
      await api('/api/home/layout', {
        featured: layoutModel.featured.map((a) => a.pkg),
        rows: layoutModel.rows.map((r) => ({ id: r.id, apps: r.apps.map((a) => a.pkg) })),
        hidden: layoutModel.hidden.map((a) => a.pkg),
      });
      toast('Salvo na TV');
    } catch (e) { toast(e.message, true); }
  }, 500);
}

function renderLayoutEditor() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Tela da TV</h3><p>Arraste pela alça ${icon('grip')} para organizar</p></div></div>
    <div id="le-body"></div>`);
  const body = sheet.querySelector('#le-body');
  const section = (id, title, items, { featured = false, rowIndex = -1 } = {}) => {
    const wrap = document.createElement('div');
    wrap.className = 'le-section' + (featured ? ' le-featured' : '');
    const head = document.createElement('div');
    head.className = 'le-head';
    head.innerHTML = `<h4></h4>`;
    head.querySelector('h4').textContent = title;
    if (rowIndex >= 0) {
      const up = document.createElement('button');
      up.innerHTML = icon('up');
      up.disabled = rowIndex === 0;
      up.onclick = () => moveRow(rowIndex, -1);
      const down = document.createElement('button');
      down.innerHTML = icon('down');
      down.disabled = rowIndex === layoutModel.rows.length - 1;
      down.onclick = () => moveRow(rowIndex, 1);
      head.append(up, down);
    }
    const list = document.createElement('div');
    list.className = 'le-list' + (items.length ? '' : ' empty');
    list.dataset.section = id;
    items.forEach((app, i) => list.append(layoutItem(app, id, i)));
    if (!items.length) list.innerHTML = `<p class="le-empty-hint">${id === 'hidden' ? 'Arraste aqui para esconder' : 'Arraste apps para cá'}</p>`;
    wrap.append(head, list);
    return wrap;
  };
  // The big row at the top of the TV now shows Stremio's "continue watching", not chosen apps
  layoutModel.rows.forEach((r, i) => body.append(section(r.id, r.title, r.apps, { rowIndex: i })));
  body.append(section('hidden', 'Escondidos', layoutModel.hidden));
}

function layoutItem(app, sectionId, index) {
  const el = document.createElement('div');
  el.className = 'le-item';
  el.innerHTML = `<button class="le-grip" aria-label="Arrastar">${icon('grip')}</button><img alt=""><span></span>
    <button class="le-toggle" aria-label="${sectionId === 'hidden' ? 'Mostrar' : 'Esconder'}">${icon(sectionId === 'hidden' ? 'eye' : sectionId === 'featured' ? 'x' : 'eyeOff')}</button>`;
  el.querySelector('img').src = iconUrl(app.pkg);
  el.querySelector('span').textContent = app.label;
  el.querySelector('.le-toggle').onclick = () => {
    buzz(15);
    if (sectionId === 'hidden') moveApp('hidden', index, layoutModel.rows[layoutModel.rows.length - 1].id, Infinity);
    else if (sectionId === 'featured') { layoutModel.featured.splice(index, 1); renderLayoutEditor(); saveLayout(); }
    else moveApp(sectionId, index, 'hidden', Infinity);
  };
  startDrag(el.querySelector('.le-grip'), el, sectionId, index);
  return el;
}

function moveRow(index, dir) {
  const rows = layoutModel.rows;
  [rows[index], rows[index + dir]] = [rows[index + dir], rows[index]];
  renderLayoutEditor();
  saveLayout();
}

/**
 * Applies a drop. Folders and "hidden" hold the apps themselves; the featured slots only point
 * at apps that also live in a folder.
 */
function moveApp(from, fromIndex, to, toIndex) {
  applyLayoutMove(layoutModel, from, fromIndex, to, toIndex);
  renderLayoutEditor();
  saveLayout();
}

function applyLayoutMove(model, from, fromIndex, to, toIndex) {
  const listOf = (id) => (id === 'featured' ? model.featured : id === 'hidden' ? model.hidden : model.rows.find((r) => r.id === id)?.apps);
  const source = listOf(from);
  const app = source?.[fromIndex];
  if (!app || !listOf(to)) return;
  const lastRow = model.rows[model.rows.length - 1];

  if (to === 'featured') {
    const featured = model.featured;
    if (from === 'featured') {
      featured.splice(fromIndex, 1);
      if (fromIndex < toIndex) toIndex--;
    } else {
      const existing = featured.findIndex((a) => a.pkg === app.pkg);
      if (existing >= 0) { featured.splice(existing, 1); if (existing < toIndex) toIndex--; }
      // Featuring a hidden app shows it again, in the last folder
      if (from === 'hidden') { source.splice(fromIndex, 1); lastRow.apps.push(app); }
    }
    featured.splice(Math.min(toIndex, featured.length), 0, app);
    if (featured.length > 3) featured.length = 3;
    return;
  }

  if (from === 'featured') {
    // Out of the featured slots: it stays in its folder, unless it was dropped on "hidden"
    model.featured.splice(fromIndex, 1);
    if (to === 'hidden') {
      model.rows.forEach((r) => { r.apps = r.apps.filter((a) => a.pkg !== app.pkg); });
      model.hidden.splice(Math.min(toIndex, model.hidden.length), 0, app);
    }
    return;
  }

  source.splice(fromIndex, 1);
  if (from === to && fromIndex < toIndex) toIndex--;
  const target = listOf(to);
  target.splice(Math.min(toIndex, target.length), 0, app);
  if (to === 'hidden') model.featured = model.featured.filter((a) => a.pkg !== app.pkg);
}

/** Drag by the handle: a floating copy follows the finger and a cyan line shows where it lands. */
function startDrag(handle, item, sectionId, index) {
  handle.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
    buzz(20);
    const box = item.getBoundingClientRect();
    const ghost = item.cloneNode(true);
    ghost.classList.add('le-ghost');
    Object.assign(ghost.style, { left: `${box.left}px`, top: `${box.top}px`, width: `${box.width}px` });
    document.body.append(ghost);
    item.classList.add('dragging');
    const offsetY = e.clientY - box.top;
    const marker = document.createElement('div');
    marker.className = 'le-marker';
    const sheet = $('sheet');
    let drop = null;
    let scrollTimer = null;

    const locate = (x, y) => {
      document.querySelectorAll('.le-list.target').forEach((l) => l.classList.remove('target'));
      const under = document.elementFromPoint(x, y);
      const list = under?.closest('.le-list');
      if (!list) { marker.remove(); drop = null; return; }
      list.classList.add('target');
      const items = [...list.querySelectorAll('.le-item')];
      let pos = items.length;
      for (let i = 0; i < items.length; i++) {
        const r = items[i].getBoundingClientRect();
        if (y < r.top + r.height / 2) { pos = i; break; }
      }
      if (items[pos]) list.insertBefore(marker, items[pos]); else list.append(marker);
      drop = { to: list.dataset.section, index: pos };
    };

    const onMove = (ev) => {
      ghost.style.top = `${ev.clientY - offsetY}px`;
      locate(ev.clientX, ev.clientY);
      // Scroll the sheet when dragging near its edges
      clearInterval(scrollTimer);
      const r = sheet.getBoundingClientRect();
      const edge = ev.clientY < r.top + 70 ? -1 : ev.clientY > r.bottom - 70 ? 1 : 0;
      if (edge) scrollTimer = setInterval(() => { sheet.scrollTop += edge * 12; locate(ev.clientX, ev.clientY); }, 16);
    };
    const onUp = () => {
      clearInterval(scrollTimer);
      handle.removeEventListener('pointermove', onMove);
      handle.removeEventListener('pointerup', onUp);
      handle.removeEventListener('pointercancel', onUp);
      ghost.remove();
      marker.remove();
      item.classList.remove('dragging');
      document.querySelectorAll('.le-list.target').forEach((l) => l.classList.remove('target'));
      if (drop && !(drop.to === sectionId && (drop.index === index || drop.index === index + 1))) {
        buzz(15);
        moveApp(sectionId, index, drop.to, drop.index);
      }
    };
    handle.addEventListener('pointermove', onMove);
    handle.addEventListener('pointerup', onUp);
    handle.addEventListener('pointercancel', onUp);
  });
}

// ---------- Install on TV (phone app only) ----------

window.onNativeInstall = (status, message) => {
  toast(message, status === 'error');
  if (status === 'working') { clearTimeout(toast.timer); }
  if (status === 'done') { buzz(40); loadApps(); }
};

$('install-tv').addEventListener('click', async () => {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Instalar app na TV</h3><p id="s-abi">Processador da TV: …</p></div></div>
    <div class="sheet-actions">
      <button class="sheet-btn primary" id="s-file">${icon('download')}Escolher arquivo APK</button>
      <button class="sheet-btn" id="s-copy">${icon('phone')}Copiar um app deste celular</button>
    </div>
    <p class="hint small" style="margin-top:14px">${icon('info')}Apps de celular funcionam na TV, mas foram feitos para toque. Use o Mouse para navegar neles.</p>`);
  sheetButton(sheet, 's-file', () => { closeSheet(); native.pickApk(); });
  sheetButton(sheet, 's-copy', phoneAppsSheet);
  try {
    const { abis } = await api('/api/abi');
    sheet.querySelector('#s-abi').textContent = `Processador da TV: ${abis}`;
  } catch { sheet.querySelector('#s-abi').textContent = 'Precisa do controle completo ativo'; }
});

function phoneAppsSheet() {
  const apps = JSON.parse(native.phoneApps());
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Copiar app para a TV</h3><p>${apps.length} apps neste celular</p></div></div>
    <label class="search">${icon('search')}<input id="s-filter" type="search" placeholder="Buscar" autocomplete="off"></label>
    <div class="list" id="s-phone-apps"></div>`);
  const render = () => {
    const q = sheet.querySelector('#s-filter').value.trim().toLowerCase();
    sheet.querySelector('#s-phone-apps').replaceChildren(...apps
      .filter((a) => a.label.toLowerCase().includes(q))
      .map((a) => {
        const btn = document.createElement('button');
        btn.className = 'phone-app';
        btn.innerHTML = `<img alt=""><span><strong></strong><small></small></span>${icon('download')}`;
        btn.querySelector('img').src = a.icon;
        btn.querySelector('strong').textContent = a.label;
        btn.querySelector('small').textContent = a.split ? `${a.pkg} · em partes` : a.pkg;
        btn.onclick = () => { closeSheet(); native.sendPhoneApp(a.pkg); };
        return btn;
      }));
  };
  sheet.querySelector('#s-filter').addEventListener('input', render);
  render();
}

// ---------- Keyboard ----------
// The textarea mirrors the TV field: each change is sent as "delete N, insert text".

const kb = $('kb-input');
let kbPrev = '';
const textQueue = [];
let textBusy = false;

function setKeyboard(active) {
  api('/api/keyboard', { active }).catch((e) => active && toast(e.message, true));
}

function queueText(backspaces, text, enter) {
  const last = textQueue[textQueue.length - 1];
  if (last && !last.enter && !enter) {
    // Merge with the pending edit: deletions first eat that edit's own unsent text
    const eaten = Math.min(backspaces, last.text.length);
    last.text = last.text.slice(0, last.text.length - eaten) + text;
    last.backspaces += backspaces - eaten;
  } else {
    textQueue.push({ backspaces, text, enter });
  }
  pumpText();
}

async function pumpText() {
  if (textBusy) return;
  textBusy = true;
  while (textQueue.length) {
    const op = textQueue.shift();
    if (!op.backspaces && !op.text && !op.enter) continue;
    try { await api('/api/text', op); } catch (e) { toast(e.message, true); textQueue.length = 0; }
  }
  textBusy = false;
}

kb.addEventListener('input', () => {
  const cur = kb.value;
  let i = 0;
  while (i < kbPrev.length && i < cur.length && kbPrev[i] === cur[i]) i++;
  queueText(kbPrev.length - i, cur.slice(i), false);
  kbPrev = cur;
});

function sendEnter() {
  buzz(15);
  queueText(0, '', true);
}

kb.addEventListener('beforeinput', (e) => {
  if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') {
    e.preventDefault();
    sendEnter();
  }
});
kb.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); sendEnter(); }
});

$('kb-enter').addEventListener('click', sendEnter);
$('kb-clear').addEventListener('click', () => {
  buzz(15);
  queueText(kbPrev.length, '', false);
  kb.value = '';
  kbPrev = '';
  kb.focus();
});

document.addEventListener('visibilitychange', () => {
  if (state.tab !== 'keyboard') return;
  setKeyboard(document.visibilityState === 'visible');
});

// ---------- Start ----------

let statusTimer = null;

function start() {
  native?.setToken(token);
  $('pair').hidden = true;
  $('app').hidden = false;
  greet();
  refreshStatus();
  refreshMusic();
  loadApps();
  clearInterval(statusTimer);
  statusTimer = setInterval(() => {
    if (document.visibilityState === 'visible') refreshStatus();
  }, 3000);
  const linked = location.hash.match(/tab=(\w+)/)?.[1];
  if (linked && $(`view-${linked}`)) showTab(linked);
}

paintIcons();
$('install-tv').hidden = !native;
$('cast-tip').hidden = !native || store.get('castTipClosed') === '1';
$('cast-tip-close').addEventListener('click', () => { store.set('castTipClosed', '1'); $('cast-tip').hidden = true; });
if (native && !native.version) {
  setTimeout(() => toast('Há uma versão nova do app do celular: abra tinyurl.com/2bhywg3r no Chrome e instale', true), 1500);
}
const hasAir = !!native?.hasGyroscope?.();
$('mode-air').hidden = !hasAir;
const savedMode = store.get('padMode');
setPadMode(savedMode === 'dpad' || (savedMode === 'air' && hasAir) ? savedMode : 'mouse');

const pinFromQr = new URLSearchParams(location.search).get('pin');
if (pinFromQr) pair(pinFromQr);
else if (token) start();
else connect();
