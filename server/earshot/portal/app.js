// Earshot portal. Plain JS, no build step.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, opts = {}) {
  const init = { credentials: 'same-origin', ...opts, headers: { ...(opts.headers || {}) } };
  if (opts.json !== undefined) {
    init.body = JSON.stringify(opts.json);
    init.headers['Content-Type'] = 'application/json';
  }
  const r = await fetch(path, init);
  if (r.status === 401) { showLogin(); throw new Error('signed out'); }
  if (!r.ok) {
    let msg = r.statusText;
    try { msg = (await r.json()).detail || msg; } catch (_) {}
    throw new Error(msg);
  }
  const ct = r.headers.get('content-type') || '';
  return ct.includes('json') ? r.json() : r.text();
}

// ---- sign in ----
function showLogin() { $('app').classList.add('hidden'); $('login').classList.remove('hidden'); $('tokenIn').focus(); }
$('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  $('loginErr').textContent = '';
  const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token: $('tokenIn').value }) });
  if (!r.ok) { $('loginErr').textContent = (await r.json()).detail || 'Could not sign in'; return; }
  $('tokenIn').value = '';
  start();
};

// ---- routing ----
function route() {
  const h = location.hash.replace(/^#/, '') || '/';
  document.querySelectorAll('.view').forEach((v) => v.classList.add('hidden'));
  let nav = 'list';
  if (h.startsWith('/r/')) { showRec(decodeURIComponent(h.slice(3))); $('v-rec').classList.remove('hidden'); }
  else if (h === '/connect') { nav = 'connect'; showConnect(); $('v-connect').classList.remove('hidden'); }
  else if (h === '/server') { nav = 'server'; showServer(); $('v-server').classList.remove('hidden'); }
  else { loadList(); $('v-list').classList.remove('hidden'); }
  document.querySelectorAll('nav a').forEach((a) => a.classList.toggle('on', a.dataset.nav === nav));
}
window.addEventListener('hashchange', route);

// ---- status line ----
let status = null;
async function poll() {
  try {
    status = await api('/api/status');
    const busy = status.queue > 0;
    $('dot').className = 'dot ' + (status.state === 'error' ? 'bad' : busy ? 'busy' : 'ok');
    $('stateLine').textContent = status.state === 'error' ? 'problem: see Server'
      : busy ? `transcribing · ${status.queue} waiting` : status.state === 'idle' ? 'ready' : status.state;
  } catch (_) {}
}

// ---- list ----
let qTimer;
$('q').oninput = () => { clearTimeout(qTimer); qTimer = setTimeout(loadList, 250); };
async function loadList() {
  const q = $('q').value.trim();
  const d = await api('/api/recordings?limit=100' + (q ? '&q=' + encodeURIComponent(q) : ''));
  $('recs').innerHTML = d.recordings.map((r) => `
    <a class="item" href="#/r/${encodeURIComponent(r.id)}">
      <div class="t">${esc(r.title)}${r.status === 'transcribing' ? '<span class="tag">transcribing</span>'
        : r.status === 'failed' ? '<span class="tag bad">failed</span>' : r.status === 'recording' ? '<span class="tag">recording</span>' : ''}</div>
      <div class="m">${esc(r.started)} · ${Math.max(1, Math.round(r.audio_s / 60))} min · ${esc(r.source)}</div>
      <div class="p">${esc(r.preview) || '&nbsp;'}</div>
    </a>`).join('');
  $('recsEmpty').textContent = d.recordings.length ? '' : q ? 'Nothing found.'
    : 'No transcripts yet. Connect the phone (Connect, above) or upload an audio file.';
}

// upload
const drop = $('drop');
drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
drop.ondragleave = () => drop.classList.remove('over');
drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove('over'); upload(e.dataTransfer.files); };
$('file').onchange = () => upload($('file').files);
async function upload(files) {
  for (const f of files) {
    drop.textContent = `Uploading ${f.name}…`;
    const fd = new FormData();
    fd.append('file', f);
    fd.append('started_ms', String(f.lastModified || Date.now()));
    try { await api('/api/upload', { method: 'POST', body: fd }); }
    catch (e) { alertLine(`Upload failed: ${e.message}`); }
  }
  drop.textContent = 'Uploaded. The transcript shows here when it is ready.';
  loadList(); poll();
}
function alertLine(msg) { $('recsEmpty').textContent = msg; }

// ---- one recording ----
let cur = null, recTimer = null;
async function showRec(id) {
  clearTimeout(recTimer);
  const r = await api('/api/recordings/' + encodeURIComponent(id));
  cur = r;
  $('recTitle').textContent = r.title;
  $('recMeta').textContent = `${r.started}${r.ended ? ' – ' + r.ended.slice(11) : ''} · ${Math.max(1, Math.round(r.audio_s / 60))} min of speech · ${r.source}`
    + (r.status !== 'done' ? ' · ' + r.status : '');
  $('recDl').href = `/api/recordings/${encodeURIComponent(id)}/text?download=true`;
  $('recRetry').classList.toggle('hidden', !r.failed);
  let html = '', job = null;
  for (const l of r.lines) {
    if (job !== null && l.job_id !== job) html += '<div class="gap"></div>';
    job = l.job_id;
    const t = new Date(l.t_ms).toTimeString().slice(0, 8);
    const who = l.speaker ? `<span class="who">Speaker ${l.speaker}</span>` : '';
    html += `<div class="ln s${l.speaker}"><div class="time">${t}</div><div>${who}${esc(l.text)}</div></div>`;
  }
  if (!r.lines.length) html = `<p class="muted">${r.pending ? 'Transcribing… this page updates by itself.' : 'No speech found.'}</p>`;
  $('recLines').innerHTML = html;
  if (r.pending && location.hash === '#/r/' + encodeURIComponent(id)) recTimer = setTimeout(() => showRec(id), 5000);
}
$('recTitle').onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); e.target.blur(); } };
$('recTitle').onblur = async () => {
  const t = $('recTitle').textContent.trim();
  if (cur && t && t !== cur.title) { await api('/api/recordings/' + encodeURIComponent(cur.id), { method: 'PATCH', json: { title: t } }); cur.title = t; }
};
$('recCopy').onclick = async () => {
  const text = await api(`/api/recordings/${encodeURIComponent(cur.id)}/text`);
  await navigator.clipboard.writeText(text);
  flash($('recCopy'), 'Copied');
};
$('recDel').onclick = async () => {
  if (!confirm(`Delete "${cur.title}"? This cannot be undone.`)) return;
  await api('/api/recordings/' + encodeURIComponent(cur.id), { method: 'DELETE' });
  location.hash = '#/';
};
$('recRetry').onclick = async () => { await api(`/api/recordings/${encodeURIComponent(cur.id)}/retry`, { method: 'POST' }); showRec(cur.id); };

function flash(btn, text) { const o = btn.textContent; btn.textContent = text; setTimeout(() => (btn.textContent = o), 1500); }

// ---- connect ----
async function showConnect() {
  const p = await api('/api/pair');
  $('qr').innerHTML = p.qr_svg;
  $('pUrl').textContent = p.url;
  $('pTok').textContent = p.token;
  $('mUrl').textContent = p.mcp_url;
  $('mBase').textContent = p.url + '/mcp';
  $('ccCmd').textContent = `claude mcp add --transport http earshot ${p.mcp_url}`;
  $('localNote').classList.toggle('hidden', !p.local);
}
document.addEventListener('click', async (e) => {
  const id = e.target.dataset && e.target.dataset.copy;
  if (id) { await navigator.clipboard.writeText($(id).textContent); flash(e.target, 'Copied'); }
  const tab = e.target.dataset && e.target.dataset.tab;
  if (tab) {
    document.querySelectorAll('#aiTabs button').forEach((b) => b.classList.toggle('on', b === e.target));
    document.querySelectorAll('[data-pane]').forEach((p) => p.classList.toggle('hidden', p.dataset.pane !== tab));
  }
});

// ---- server ----
async function showServer() {
  await poll();
  const s = status;
  const rows = [
    ['State', s.error ? `<span style="color:var(--bad)">${esc(s.error)}</span>` : esc(s.state)],
    ['Speech model', esc(s.model)],
    ['Runs on', `${s.device === 'cuda' ? 'NVIDIA GPU' : 'CPU'} <span class="muted">(${esc(s.device_note)})</span>`],
    ['Speaker models', s.speaker_models ? 'ready' : 'not downloaded (run: sudo earshot update)'],
    ['Transcripts', `${s.recordings} recordings, ${s.audio_hours} h of speech`],
    ['Speed', s.speed ? `${s.speed}× real time` : '—'],
    ['Waiting', s.queue + (s.failed ? ` · <span style="color:var(--bad)">${s.failed} failed</span>` : '')],
    ['Free disk', `${s.disk_free_gb} GB`],
    ['Address', esc(s.url)],
    ['Version', esc(s.version)],
  ];
  $('kv').innerHTML = rows.map(([k, v]) => `<div>${k}</div><div>${v}</div>`).join('');
  const d = await api('/api/settings');
  $('sModel').innerHTML = `<option value="auto">Automatic (large-v3 with a GPU, large-v3-turbo without)</option>` +
    Object.keys(d.models).map((m) => `<option value="${m}">${m}</option>`).join('');
  const st = d.settings;
  $('sModel').value = st.model; $('sDevice').value = st.device; $('sLang').value = st.language;
  $('sDiarize').checked = st.diarize; $('sSkip').checked = st.skip_background; $('sKeep').checked = st.keep_audio;
  const note = () => { $('sModelNote').textContent = d.models[$('sModel').value] || 'Picks the best model for this computer.'; };
  $('sModel').onchange = note; note();
}
$('sSave').onclick = async () => {
  await api('/api/settings', { method: 'PUT', json: {
    model: $('sModel').value, device: $('sDevice').value, language: $('sLang').value,
    diarize: $('sDiarize').checked, skip_background: $('sSkip').checked, keep_audio: $('sKeep').checked } });
  $('sSaved').textContent = 'Saved. A new model downloads before the next transcript.';
  setTimeout(() => ($('sSaved').textContent = ''), 5000);
};
$('rotate').onclick = async () => {
  if (!confirm('Make a new token? The phone and AI tools must be connected again.')) return;
  await api('/api/token', { method: 'POST' });
  location.hash = '#/connect';
};
$('logout').onclick = async () => { await api('/api/logout', { method: 'POST' }); showLogin(); };

// ---- start ----
async function start() {
  try { await poll(); if (!status) return; } catch (_) { return; }
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  route();
}
setInterval(() => { if (!$('app').classList.contains('hidden')) poll(); }, 5000);
start();
