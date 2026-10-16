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

// ---------- Touchpad ----------

const pad = $('touchpad');
const STEP = 42;
let touch = null;

pad.addEventListener('pointerdown', (e) => {
  pad.setPointerCapture(e.pointerId);
  const box = pad.getBoundingClientRect();
  const dot = document.createElement('span');
  dot.className = 'touch-dot';
  pad.append(dot);
  touch = { x0: e.clientX, y0: e.clientY, ox: e.clientX, oy: e.clientY, t: Date.now(), moved: false, dot, box };
  moveDot(e);
});

pad.addEventListener('pointermove', (e) => {
  if (!touch) return;
  moveDot(e);
  const dx = e.clientX - touch.ox;
  const dy = e.clientY - touch.oy;
  if (Math.abs(dx) < STEP && Math.abs(dy) < STEP) return;
  const horizontal = Math.abs(dx) > Math.abs(dy);
  const delta = horizontal ? dx : dy;
  const steps = Math.floor(Math.abs(delta) / STEP);
  const sign = Math.sign(delta);
  sendKey(horizontal ? (sign > 0 ? 'right' : 'left') : (sign > 0 ? 'down' : 'up'), steps);
  // Advance the origin along the movement axis and re-anchor the other one to avoid drift
  if (horizontal) { touch.ox += sign * steps * STEP; touch.oy = e.clientY; }
  else { touch.oy += sign * steps * STEP; touch.ox = e.clientX; }
  touch.moved = true;
});

function moveDot(e) {
  touch.dot.style.left = `${e.clientX - touch.box.left}px`;
  touch.dot.style.top = `${e.clientY - touch.box.top}px`;
}

function endTouch(e) {
  if (!touch) return;
  const distance = Math.hypot(e.clientX - touch.x0, e.clientY - touch.y0);
  if (!touch.moved && distance < 12 && Date.now() - touch.t < 350) {
    sendKey('ok');
    pad.classList.add('flash');
    setTimeout(() => pad.classList.remove('flash'), 150);
  }
  touch.dot.remove();
  touch = null;
}
pad.addEventListener('pointerup', endTouch);
pad.addEventListener('pointercancel', endTouch);

$('pad-mode').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  setPadMode(btn.dataset.mode);
  store.set('padMode', btn.dataset.mode);
});

function setPadMode(mode) {
  $('pad-mode').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  $('touchpad').hidden = mode !== 'touch';
  $('dpad').hidden = mode !== 'dpad';
}

// ---------- Tabs ----------

document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-tab]');
  if (btn) showTab(btn.dataset.tab);
});

function showTab(tab) {
  if (state.tab === 'keyboard' && tab !== 'keyboard') setKeyboard(false);
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
      <button class="sheet-btn" id="s-player">${icon('play')}Player da TV</button>
      <button class="sheet-btn" id="s-pin">${icon('lock')}${s.requirePin ? 'Parar de pedir PIN' : 'Pedir PIN para novos celulares'}</button>
      <button class="sheet-btn primary" id="s-update">${icon('download')}Procurar atualização</button>
      <button class="sheet-btn danger" id="s-logout">${icon('logout')}Desconectar este celular</button>
    </div>`);
  sheet.querySelector('#s-update-text').textContent = s.update || '–';
  sheet.querySelector('#s-device').textContent = s.device || '–';
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

// ---------- Subtitles ----------

const LANGS = [['en', 'Inglês'], ['pt', 'Português'], ['es', 'Espanhol'], ['off', 'Desligada']];

$('subs-btn').addEventListener('click', subtitlesSheet);

async function subtitlesSheet() {
  const sheet = openSheet(`
    <div class="sheet-head"><div><h3>Legendas</h3><p id="s-sub-title">Carregando…</p></div></div>
    <div class="sheet-actions" id="s-tracks"></div>
    <div class="sheet-actions" style="margin-top:8px">
      <button class="sheet-btn primary" id="s-sub-file">${icon('download')}Carregar legenda do celular (.srt)</button>
    </div>
    <h4>Idioma padrão</h4>
    <div class="lang-row" id="s-langs"></div>
    <p class="hint small">${icon('info')}A legenda nesse idioma liga sozinha quando o vídeo tiver uma.</p>`);
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
    const options = [...player.subtitles, { id: -1, label: 'Sem legenda', selected: !anyOn }];
    if (!player.subtitles.length) {
      const empty = document.createElement('p');
      empty.className = 'hint';
      empty.textContent = 'Este vídeo chegou sem legendas. Carregue um arquivo .srt do celular.';
      tracksBox.append(empty);
      return;
    }
    tracksBox.replaceChildren(...options.map((t) => {
      const btn = document.createElement('button');
      btn.className = 'sheet-btn' + (t.selected ? ' primary' : '');
      btn.innerHTML = t.selected ? icon('check') : icon(t.id < 0 ? 'x' : 'captions');
      btn.append(t.label);
      btn.onclick = async () => {
        try {
          await api('/api/player/subtitle', { id: t.id });
          toast(t.id < 0 ? 'Legenda desligada' : `Legenda: ${t.label}`);
          subtitlesSheet();
        } catch (e) { toast(e.message, true); }
      };
      return btn;
    }));
  } catch (e) {
    sheet.querySelector('#s-sub-title').textContent = e.message;
  }
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
    <p class="hint small" style="margin-top:14px">${icon('info')}Apps de celular funcionam na TV, mas foram feitos para toque. Use o Touchpad para navegar neles.</p>`);
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
setPadMode(store.get('padMode') || 'touch');

const pinFromQr = new URLSearchParams(location.search).get('pin');
if (pinFromQr) pair(pinFromQr);
else if (token) start();
else connect();
