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
let pendingScroll = 0;
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
  if (!scrollBusy && Math.abs(pendingScroll) > 0.03) {
    const y = pendingScroll;
    pendingScroll = 0;
    scrollBusy = true;
    try { await api('/api/mouse/scroll', { y }); } catch (e) { toast(e.message, true); }
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
    // Two fingers: scroll by the average vertical movement (content follows the fingers)
    pendingScroll += dy / f.box.height / fingers.size * 1.5;
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
}

// ---------- Air mouse (switches instead of holding) ----------

let airMode = 'off';
const AIR_STATUS = {
  off: 'Sensor desligado: toque em Mover ou Rolar',
  move: 'Movendo: aponte o celular para a TV',
  scroll: 'Rolando: incline para a frente ou para trás (reto para parar)',
};

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
  document.querySelectorAll('.air-toggle').forEach((b) => b.classList.toggle('on', b.dataset.air === mode));
  $('air-status').textContent = AIR_STATUS[mode];
}

document.querySelectorAll('.air-toggle').forEach((btn) => {
  // Tapping the lit switch turns the sensor off; tapping the other one switches to it
  btn.addEventListener('click', () => { buzz(15); setAirMode(airMode === btn.dataset.air ? 'off' : btn.dataset.air); });
});
$('air-select').addEventListener('click', () => mouseClick(false));
$('air-long').addEventListener('click', () => mouseClick(true));
// Never leave the sensor running in the background
document.addEventListener('visibilitychange', () => { if (document.visibilityState !== 'visible') setAirMode('off'); });

// ---------- Tabs ----------

document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-tab]');
  if (btn) showTab(btn.dataset.tab);
});

function showTab(tab) {
  if (state.tab === 'keyboard' && tab !== 'keyboard') setKeyboard(false);
  if (tab !== 'remote') setAirMode('off');
  state.tab = tab;
  for (const name of ['home', 'remote', 'apps', 'keyboard']) {
    $(`view-${name}`).hidden = name !== tab;
  }
  document.querySelectorAll('.tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  window.scrollTo(0, 0);
  if (tab === 'apps') loadApps();
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
    renderNow(s.foreground);
  } catch {
    $('status-dot').className = 'dot off';
    $('status-text').textContent = 'TV fora do ar';
  }
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
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Ajustes</h3><p>Controle TV</p></div></div>
    <div class="kv"><span>Controle</span><span><span class="dot ${dot}"></span> ${text}${s.via ? ` (via ${s.via})` : ''}</span></div>
    ${ADB_HELP[s.adb] ? `<p class="body" style="margin-top:12px">Para liberar o controle completo:</p>${ADB_HELP[s.adb]}` : ''}
    ${s.adbError ? `<div class="kv"><span>Detalhe</span><span class="mono" id="s-adb-error"></span></div>` : ''}
    <div class="kv"><span>Aparelho</span><span id="s-device"></span></div>
    ${native ? `<div class="kv"><span>App do celular</span><span>versão ${native.version?.() ?? '?'}</span></div>` : ''}
    ${native ? `<div class="kv"><span>Mouse aéreo</span><span class="mono" id="s-air"></span></div>` : ''}
    ${s.lastPlay ? `<div class="kv"><span>Último vídeo</span><span class="mono" id="s-last-play"></span></div>` : ''}
    <div class="kv"><span>Versão do app</span><span>${s.version ?? '–'}</span></div>
    <div class="kv"><span>Versão das telas</span><span>${s.webVersion ?? '–'}</span></div>
    <div class="kv"><span>Atualização</span><span id="s-update-text"></span></div>
    <div class="sheet-actions" style="margin-top:16px">
      ${s.adb !== 'ok' && !native ? `<a class="sheet-btn primary" href="${PHONE_APP_URL}">${icon('download')}Baixar app do celular</a>` : ''}
      ${native ? `<button class="sheet-btn" id="s-forget">${icon('tv')}Trocar de TV</button>` : ''}
      ${s.adb !== 'ok' ? `<button class="sheet-btn primary" id="s-retry">${icon('refresh')}Tentar conectar de novo</button>
      <button class="sheet-btn" id="s-probe">${icon('search')}Diagnóstico detalhado</button>` : ''}
      ${s.updatePending ? `<button class="sheet-btn primary" id="s-install">${icon('download')}Instalar atualização baixada</button>` : ''}
      <button class="sheet-btn${s.homeLauncher ? ' primary' : ''}" id="s-home">${icon('house')}${s.homeLauncher ? 'Controle TV é a tela inicial (desligar)' : 'Usar o Controle TV como tela inicial'}</button>
      <button class="sheet-btn" id="s-player">${icon('play')}Player da TV</button>
      <button class="sheet-btn" id="s-pin">${icon('lock')}${s.requirePin ? 'Parar de pedir PIN' : 'Pedir PIN para novos celulares'}</button>
      ${native?.updateTv ? `<button class="sheet-btn primary" id="s-update-tv">${icon('download')}Atualizar a TV pelo celular</button>` : ''}
      <button class="sheet-btn primary" id="s-update">${icon('download')}Procurar atualização</button>
      <button class="sheet-btn danger" id="s-logout">${icon('logout')}Desconectar este celular</button>
    </div>
    <p class="hint small" style="margin-top:16px">${icon('info')}Legenda automática: modelo Kroko ASR (Banafo, licença CC-BY-SA) rodando com sherpa-onnx (Apache-2.0).</p>`);
  sheet.querySelector('#s-update-text').textContent = s.update || '–';
  sheet.querySelector('#s-device').textContent = s.device || '–';
  const air = sheet.querySelector('#s-air');
  if (air) {
    air.textContent = `celular enviou ${native.airMouseSent?.() ?? 0} · TV recebeu ${s.airReceived ?? '?'}` +
      (s.airRejected ? ` · recusados ${s.airRejected}` : '') + (s.pointerProblem ? ` · ${s.pointerProblem}` : '');
  }
  const lastPlay = sheet.querySelector('#s-last-play');
  if (lastPlay) lastPlay.textContent = s.lastPlay;
  const adbError = sheet.querySelector('#s-adb-error');
  if (adbError) adbError.textContent = s.adbError;
  sheetButton(sheet, 's-retry', async () => {
    toast('Tentando conectar…');
    try {
      await api('/api/adb/retry', {});
      setTimeout(async () => { await refreshStatus(); settingsSheet(); }, 4000);
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-probe', probeSheet);
  sheetButton(sheet, 's-player', playerSheet);
  sheetButton(sheet, 's-update-tv', () => { closeSheet(); native.updateTv(); });
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
  sheetButton(sheet, 's-forget', () => native.forgetTv());
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
      sheet.querySelector('#s-update-text').textContent = result;
      if (/Telas atualizadas/.test(result)) setTimeout(() => location.reload(), 1200);
    } catch (e) { toast(e.message, true); }
  });
  sheetButton(sheet, 's-logout', () => { closeSheet(); logout(); });
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
      btn.className = 'sheet-btn' + (p.pkg === preferred ? ' primary' : '');
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
    const data = JSON.parse(await nativeCall('list', { path }));
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
      btn.className = 'sheet-btn' + (t.selected ? ' primary' : '');
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
