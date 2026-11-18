// Plume pour PC : bibliothèque, éditeur et synchronisation avec l'appli Android.
import { Repo } from './repo.js';
import { Sync } from './sync.js';
import { nextId, uuid } from './store.js';
import * as R from './render.js';

const $ = (s) => document.querySelector(s);
const el = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'style') e.style.cssText = v;
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(c));
  return e;
};

const repo = new Repo();
let sync;

// ============ Fenêtres, menus, messages ============

function toast(msg, ms = 3000) {
  const t = $('#toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, ms);
}

function closePopover() { $('#popover').hidden = true; $('#popover').replaceChildren(); }

function popover(anchor, content) {
  const p = $('#popover');
  p.replaceChildren(...[content].flat());
  p.hidden = false;
  const r = anchor.getBoundingClientRect();
  const w = p.offsetWidth, h = p.offsetHeight;
  let x = Math.min(r.left, innerWidth - w - 8), y = r.bottom + 6;
  if (y + h > innerHeight - 8) y = Math.max(8, r.top - h - 6);
  p.style.left = Math.max(8, x) + 'px'; p.style.top = y + 'px';
}
document.addEventListener('pointerdown', (e) => {
  if (!$('#popover').hidden && !$('#popover').contains(e.target)) closePopover();
}, true);

function menu(anchor, items) {
  popover(anchor, items.filter(Boolean).map(([label, fn, cls]) =>
    el('button', { class: 'item ' + (cls || ''), onclick: () => { closePopover(); fn(); } }, label)));
}

function modal(build) {
  const wrap = $('#modal-wrap'), box = $('#modal');
  return new Promise((resolve) => {
    const close = (v) => { wrap.hidden = true; box.replaceChildren(); resolve(v); };
    box.replaceChildren(...build(close));
    wrap.hidden = false;
    wrap.onclick = (e) => { if (e.target === wrap) close(null); };
    box.querySelector('input')?.focus();
  });
}

function askText(title, initial = '', ok = 'OK') {
  return modal((close) => {
    const input = el('input', { type: 'text', value: initial });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') close(input.value); if (e.key === 'Escape') close(null); });
    setTimeout(() => input.select());
    return [el('h2', {}, title), input, el('div', { class: 'row' },
      el('button', { onclick: () => close(null) }, 'Annuler'),
      el('button', { class: 'primary', onclick: () => close(input.value) }, ok))];
  });
}

function confirmBox(title, text, ok = 'Supprimer') {
  return modal((close) => [el('h2', {}, title), el('p', {}, text), el('div', { class: 'row' },
    el('button', { onclick: () => close(false) }, 'Annuler'),
    el('button', { class: 'primary', onclick: () => close(true) }, ok))]);
}

// ============ Synchronisation : bouton et compte ============

function renderSyncButtons() {
  const s = sync.status;
  for (const b of document.querySelectorAll('.sync-btn')) {
    let ic = '☁', label = 'Se connecter', cls = '';
    if (!sync.configured) { ic = '☁'; label = 'Hors ligne'; }
    else if (s.email) {
      if (s.running) { ic = '⟳'; label = 'Synchronisation…'; }
      else if (s.error) { ic = '⚠'; label = 'Non synchronisé'; cls = 'err'; }
      else { ic = '✓'; label = 'Synchronisé'; cls = 'ok'; }
    }
    b.className = 'sync-btn ' + cls;
    b.replaceChildren(el('span', { class: 'ic' + (s.running ? ' spin' : '') }, ic), el('span', { class: 'label' }, label));
  }
}

function accountDialog() {
  modal((close) => {
    const s = sync.status;
    if (!sync.configured) {
      return [el('h2', {}, 'Synchronisation'),
        el('p', {}, "La synchronisation n'est pas encore activée. Vos notes restent enregistrées dans ce navigateur."),
        el('div', { class: 'row' }, el('button', { class: 'primary', onclick: () => close() }, 'Fermer'))];
    }
    if (!s.email) {
      const email = el('input', { type: 'email', placeholder: 'E-mail', autocomplete: 'username' });
      const pass = el('input', { type: 'password', placeholder: 'Mot de passe', autocomplete: 'current-password' });
      const err = el('p', { class: 'err' });
      const go = async (create) => {
        if (!email.value.trim() || !pass.value) { err.textContent = 'Indiquez votre e-mail et votre mot de passe.'; return; }
        err.textContent = create ? 'Création du compte…' : 'Connexion…';
        const msg = await sync.signIn(email.value, pass.value, create);
        if (msg) err.textContent = msg; else { close(); toast('Connecté : vos notes se synchronisent.'); }
      };
      pass.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(false); });
      return [el('h2', {}, 'Compte Plume'),
        el('p', { class: 'small' }, 'Utilisez le même compte que sur votre téléphone pour retrouver toutes vos notes.'),
        email, pass, err,
        el('button', { class: 'primary', onclick: () => go(false) }, 'Se connecter'),
        el('button', { onclick: () => go(true) }, 'Créer un compte'),
        el('div', { class: 'row' }, el('button', { class: 'ghost', onclick: () => close() }, 'Fermer'))];
    }
    const when = s.running ? 'Synchronisation en cours…' : s.error ? s.error
      : s.lastSync ? 'Dernière synchronisation : ' + new Date(s.lastSync).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
        : 'Pas encore synchronisé.';
    return [el('h2', {}, 'Synchronisation'), el('p', {}, 'Connecté : ', el('b', {}, s.email)), el('p', { class: 'small' }, when),
      el('button', { class: 'primary', onclick: () => { sync.request(0); close(); } }, 'Synchroniser maintenant'),
      el('button', { onclick: async () => {
        close();
        const wipe = await confirmBox('Se déconnecter', 'Effacer aussi les notes de ce navigateur ? Elles restent sur votre compte et sur votre téléphone.', 'Effacer et se déconnecter');
        if (wipe === null) return;
        await sync.signOut(!!wipe); renderLibrary();
      } }, 'Se déconnecter'),
      el('div', { class: 'row' }, el('button', { class: 'ghost', onclick: () => close() }, 'Fermer'))];
  });
}

// ============ Images et PDF ============

const imageCache = new Map(); // asset -> ImageBitmap | 'loading' | 'missing'
const listeners = new Set();
const notifyAssets = () => listeners.forEach((f) => f());

function getImage(asset) {
  const v = imageCache.get(asset);
  if (v && v !== 'loading' && v !== 'missing') return v;
  if (!v) {
    imageCache.set(asset, 'loading');
    repo.getAsset(asset).then(async (blob) => {
      if (!blob) { imageCache.set(asset, 'missing'); setTimeout(() => imageCache.delete(asset), 5000); return; }
      imageCache.set(asset, await createImageBitmap(blob));
      notifyAssets();
    }).catch(() => imageCache.set(asset, 'missing'));
  }
  return null;
}

let pdfjsP = null;
function pdfjs() {
  if (!pdfjsP) pdfjsP = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = 'vendor/pdf.min.js';
    s.onload = () => {
      window.pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('vendor/pdf.worker.min.js', location.href).href;
      resolve(window.pdfjsLib);
    };
    s.onerror = () => { pdfjsP = null; reject(new Error('pdf.js')); };
    document.head.append(s);
  });
  return pdfjsP;
}
const pdfDocs = new Map();
async function pdfDoc(asset) {
  if (!pdfDocs.has(asset)) {
    pdfDocs.set(asset, (async () => {
      const blob = await repo.getAsset(asset);
      if (!blob) throw new Error('missing');
      const lib = await pdfjs();
      return lib.getDocument({ data: new Uint8Array(await blob.arrayBuffer()) }).promise;
    })().catch((e) => { pdfDocs.delete(asset); throw e; }));
  }
  return pdfDocs.get(asset);
}
const pdfCache = new Map(); // clé -> canvas | 'loading'
const PDF_BUCKETS = [300, 800, 1400, 2000];
function getPdfPage(ref, widthPx) {
  const bucket = PDF_BUCKETS.find((b) => b >= widthPx) || 2000;
  const key = `${ref.asset}#${ref.index}@${bucket}`;
  const v = pdfCache.get(key);
  if (v && v !== 'loading') return v;
  if (!v) {
    pdfCache.set(key, 'loading');
    (async () => {
      const doc = await pdfDoc(ref.asset);
      const page = await doc.getPage(ref.index + 1);
      const vp0 = page.getViewport({ scale: 1 });
      const vp = page.getViewport({ scale: bucket / vp0.width });
      const c = document.createElement('canvas'); c.width = Math.ceil(vp.width); c.height = Math.ceil(vp.height);
      const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
      await page.render({ canvasContext: ctx, viewport: vp }).promise;
      if (pdfCache.size > 40) pdfCache.delete(pdfCache.keys().next().value);
      pdfCache.set(key, c); notifyAssets();
    })().catch(() => { setTimeout(() => pdfCache.delete(key), 5000); });
  }
  for (const b of [...PDF_BUCKETS].reverse()) { const o = pdfCache.get(`${ref.asset}#${ref.index}@${b}`); if (o && o !== 'loading') return o; }
  return null;
}

async function importPdf(file, folderId) {
  try {
    toast('Import du PDF…', 10000);
    const asset = uuid() + '.pdf';
    await repo.putAsset(asset, new Blob([await file.arrayBuffer()], { type: 'application/pdf' }));
    const doc = await pdfDoc(asset);
    const n = doc.numPages;
    const note = repo.createNote(folderId, { style: 'BLANK', color: 0xFFFFFFFF }, file.name.replace(/\.pdf$/i, '') || 'PDF importé');
    const content = { pages: Array.from({ length: n }, (_, i) => ({ strokes: [], texts: [], images: [], pdf: { asset, index: i } })), recordings: [] };
    await repo.saveContent(note.id, content);
    toast('PDF importé.');
    return note;
  } catch (e) {
    console.error(e); toast("Impossible d'importer ce PDF.");
    return null;
  }
}

async function importImage(file) {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 2048 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.9));
  const asset = uuid() + '.jpg';
  await repo.putAsset(asset, blob);
  return { asset, width: c.width, height: c.height };
}

// ============ Bibliothèque ============

const lib = { filter: 'all', query: '', sort: 'updated' };

function folderChildren(parent) { return repo.index.folders.filter((f) => (f.parentId ?? null) === parent).sort((a, b) => a.name.localeCompare(b.name, 'fr')); }

function renderNav() {
  const nav = $('#nav');
  const notes = repo.index.notes;
  const item = (key, label, count, extra = {}) => el('button', {
    class: 'nav-item' + (lib.filter === key ? ' active' : ''), style: extra.indent ? `padding-left:${8 + extra.indent * 16}px` : '',
    onclick: () => { lib.filter = key; $('#sidebar').classList.remove('open'); renderLibrary(); },
  }, extra.dot, el('span', { class: 'nav-label' }, label), el('span', { class: 'count' }, count), extra.more);
  const rows = [item('all', '📚  Toutes les notes', notes.length), item('none', '📄  Non classées', notes.filter((n) => !n.notebookId).length)];
  rows.push(el('div', { class: 'nav-title' }, 'Dossiers'));
  const walk = (parent, depth) => {
    for (const f of folderChildren(parent)) {
      const tree = repo.folderTree(f.id);
      const more = el('span', { class: 'more', title: 'Options', onclick: (e) => { e.stopPropagation(); folderMenu(e.currentTarget, f); } }, '⋯');
      rows.push(item(f.id, f.name, notes.filter((n) => tree.has(n.notebookId)).length,
        { indent: depth, dot: el('span', { class: 'dot', style: `background:${R.css(f.color, 1)}` }), more }));
      walk(f.id, depth + 1);
    }
  };
  walk(null, 0);
  if (!repo.index.folders.length) rows.push(el('p', { class: 'small', style: 'color:var(--muted);padding:0 8px;font-size:13px' }, 'Aucun dossier.'));
  nav.replaceChildren(...rows);
}

function folderMenu(anchor, f) {
  menu(anchor, [
    ['Nouveau sous-dossier', async () => { const n = await askText('Nouveau sous-dossier', '', 'Créer'); if (n) { const c = repo.createFolder(n, f.id); lib.filter = c.id; renderLibrary(); } }],
    ['Renommer', async () => { const n = await askText('Renommer le dossier', f.name); if (n) repo.renameFolder(f.id, n); }],
    ['Couleur', () => setTimeout(() => popover(anchor, el('div', { class: 'colors' }, R.FOLDER_COLORS.map((c) =>
      el('button', { class: 'swatch' + (c === f.color ? ' active' : ''), style: `background:${R.css(c, 1)}`, onclick: () => { closePopover(); repo.setFolderColor(f.id, c); } }))))) ],
    ['Supprimer', async () => {
      if (await confirmBox('Supprimer le dossier', `« ${f.name} », ses sous-dossiers et toutes leurs notes seront supprimés, aussi sur vos autres appareils.`)) {
        repo.deleteFolder(f.id); if (repo.folderTree(f.id).has(lib.filter) || lib.filter === f.id) lib.filter = 'all'; renderLibrary();
      }
    }, 'danger'],
  ]);
}

function visibleNotes() {
  let notes = repo.index.notes;
  if (lib.filter === 'none') notes = notes.filter((n) => !n.notebookId);
  else if (lib.filter !== 'all') { const tree = repo.folderTree(lib.filter); notes = notes.filter((n) => tree.has(n.notebookId)); }
  const q = lib.query.trim().toLowerCase();
  if (q) notes = repo.index.notes.filter((n) => n.title.toLowerCase().includes(q) || (repo.folder(n.notebookId)?.name || '').toLowerCase().includes(q));
  const by = { updated: (a, b) => b.updatedAt - a.updatedAt, created: (a, b) => b.createdAt - a.createdAt, title: (a, b) => a.title.localeCompare(b.title, 'fr') };
  return [...notes].sort(by[lib.sort]);
}

const thumbObserver = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { thumbObserver.unobserve(e.target); drawThumb(e.target); }
}, { rootMargin: '300px' });

async function drawThumb(canvas) {
  const note = repo.note(canvas.dataset.id); if (!note) return;
  const content = await repo.loadContent(note.id);
  const page = content.pages[0] || {};
  const w = canvas.width = 240 * Math.min(2, devicePixelRatio || 1); canvas.height = Math.round(w * R.PAGE_H / R.PAGE_W);
  const draw = () => {
    if (!canvas.isConnected) { listeners.delete(draw); return; }
    const ctx = canvas.getContext('2d'); ctx.setTransform(w / R.PAGE_W, 0, 0, w / R.PAGE_W, 0, 0);
    R.drawPage(ctx, page, note.paper, note.paperColor, { getImage, pdfImage: page.pdf ? getPdfPage(page.pdf, w) : null });
  };
  listeners.add(draw); draw();
}

function noteMenu(anchor, n) {
  menu(anchor, [
    ['Ouvrir', () => openEditor(n.id)],
    ['Renommer', async () => { const t = await askText('Renommer la note', n.title); if (t) repo.renameNote(n.id, t); }],
    ['Déplacer…', () => setTimeout(() => moveMenu(anchor, n), 0)],
    ['Dupliquer', () => repo.duplicateNote(n.id)],
    ['Supprimer', async () => { if (await confirmBox('Supprimer la note', `« ${n.title} » sera supprimée, aussi sur vos autres appareils.`)) repo.deleteNote(n.id); }, 'danger'],
  ]);
}

function moveMenu(anchor, n) {
  const rows = [el('h4', {}, 'Déplacer vers'), el('button', { class: 'item', onclick: () => { closePopover(); repo.moveNote(n.id, null); } }, '📄 Non classées')];
  const walk = (parent, depth) => { for (const f of folderChildren(parent)) {
    rows.push(el('button', { class: 'item', style: `padding-left:${12 + depth * 16}px`, onclick: () => { closePopover(); repo.moveNote(n.id, f.id); } },
      el('span', { class: 'folder-tag', style: `background:${R.css(f.color, 1)}` }), f.name));
    walk(f.id, depth + 1);
  } };
  walk(null, 0);
  popover(anchor, rows);
}

function renderLibrary() {
  if (lib.filter !== 'all' && lib.filter !== 'none' && !repo.folder(lib.filter)) lib.filter = 'all';
  renderNav();
  $('#lib-title').textContent = lib.query ? 'Recherche' : lib.filter === 'all' ? 'Toutes les notes' : lib.filter === 'none' ? 'Non classées' : repo.folder(lib.filter).name;
  const notes = visibleNotes();
  $('#empty').hidden = notes.length > 0;
  $('#grid').replaceChildren(...notes.map((n) => {
    const thumb = el('canvas', { class: 'thumb', 'data-id': n.id });
    thumbObserver.observe(thumb);
    const f = repo.folder(n.notebookId);
    const more = el('button', { class: 'more', title: 'Options', onclick: (e) => { e.stopPropagation(); noteMenu(e.currentTarget, n); } }, '⋮');
    return el('div', { class: 'card', onclick: () => openEditor(n.id), oncontextmenu: (e) => { e.preventDefault(); noteMenu(more, n); } },
      thumb, el('div', { class: 'meta' }, el('div', { style: 'min-width:0' },
        el('div', { class: 'name', title: n.title }, n.title),
        el('div', { class: 'sub' }, f ? el('span', { class: 'folder-tag', style: `background:${R.css(f.color, 1)}` }) : null,
          new Date(n.updatedAt).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }),
          ' · ', n.pageCount > 1 ? `${n.pageCount} pages` : '1 page')), more));
  }));
}

function currentFolder() { return lib.filter === 'all' || lib.filter === 'none' ? null : lib.filter; }

// ============ Éditeur ============

const ed = {
  id: null, meta: null, content: null, tool: 'pen',
  penColor: R.INK_COLORS[0], penWidth: 4.5, hlColor: R.HIGHLIGHTER_COLORS[0], hlWidth: 30,
  eraserMode: 'partial', eraserSize: 16, zoom: 1,
  undo: [], redo: [], pages: [], saveTimer: null, penSeen: false, replay: null, dirty: false,
};
const PEN_WIDTHS = [2, 3.2, 4.5, 7, 11];
const HL_WIDTHS = [16, 24, 30, 44];
const ERASER_SIZES = [8, 16, 32, 60];

try { Object.assign(ed, JSON.parse(localStorage.getItem('plume-tools') || '{}')); } catch { /* ignoré */ }
ed.tool = ed.tool || 'pen';
function saveTools() {
  try { localStorage.setItem('plume-tools', JSON.stringify({ tool: ed.tool, penColor: ed.penColor, penWidth: ed.penWidth, hlColor: ed.hlColor, hlWidth: ed.hlWidth, eraserMode: ed.eraserMode, eraserSize: ed.eraserSize, zoom: ed.zoom })); } catch { /* ignoré */ }
}

async function openEditor(id) {
  closePopover();
  const meta = repo.note(id); if (!meta) return;
  ed.id = id; ed.meta = meta; repo.editingNoteId = id;
  ed.content = await repo.loadContent(id);
  ed.undo = []; ed.redo = []; ed.replay = null; ed.dirty = false;
  $('#library').hidden = true; $('#editor').hidden = false;
  $('#ed-title').textContent = meta.title;
  history.pushState({ note: id }, '', '#note=' + id);
  renderToolbar(); renderAudioButton(); $('#audio-bar').hidden = true; $('#audio-bar').replaceChildren();
  buildPages();
  $('#pages').scrollTop = 0;
}

function flushSave() {
  clearTimeout(ed.saveTimer);
  if (ed.dirty && ed.id) { ed.dirty = false; return repo.saveContent(ed.id, ed.content); }
}

async function closeEditor(fromHistory = false) {
  commitText();
  await flushSave();
  repo.editingNoteId = null;
  ed.id = null; ed.pages = [];
  $('#pages').replaceChildren();
  $('#editor').hidden = true; $('#library').hidden = false;
  if (!fromHistory && location.hash) history.back();
  renderLibrary();
  sync.request(1000);
}

window.addEventListener('popstate', () => { if (ed.id && !location.hash.startsWith('#note=')) closeEditor(true); });

function changed() {
  ed.dirty = true;
  clearTimeout(ed.saveTimer);
  ed.saveTimer = setTimeout(flushSave, 800);
}

function snapshot() {
  ed.undo.push(JSON.stringify(ed.content.pages));
  if (ed.undo.length > 60) ed.undo.shift();
  ed.redo = [];
}
function undo() {
  commitText();
  if (!ed.undo.length) return;
  ed.redo.push(JSON.stringify(ed.content.pages));
  ed.content.pages = JSON.parse(ed.undo.pop());
  changed(); buildPages(true);
}
function redo() {
  if (!ed.redo.length) return;
  ed.undo.push(JSON.stringify(ed.content.pages));
  ed.content.pages = JSON.parse(ed.redo.pop());
  changed(); buildPages(true);
}

// ---- Barre d'outils ----
function renderToolbar() {
  document.querySelectorAll('#toolbar [data-tool]').forEach((b) => b.classList.toggle('active', b.dataset.tool === ed.tool));
  document.body.classList.toggle('pen-cursor', ed.tool !== 'text');
  document.body.classList.toggle('text-cursor', ed.tool === 'text');
  const colors = $('#colors'), widths = $('#widths');
  if (ed.tool === 'pen' || ed.tool === 'text') {
    colors.replaceChildren(...R.INK_COLORS.map((c) => el('button', { class: 'swatch' + (c === ed.penColor ? ' active' : ''), style: `background:${R.css(c, 1)}`, title: 'Couleur', onclick: () => { ed.penColor = c; saveTools(); renderToolbar(); } })));
  } else if (ed.tool === 'highlighter') {
    colors.replaceChildren(...R.HIGHLIGHTER_COLORS.map((c) => el('button', { class: 'swatch' + (c === ed.hlColor ? ' active' : ''), style: `background:${R.css(c, 1)}`, onclick: () => { ed.hlColor = c; saveTools(); renderToolbar(); } })));
  } else {
    colors.replaceChildren(
      el('button', { class: 'chip' + (ed.eraserMode === 'partial' ? ' active' : ''), onclick: () => { ed.eraserMode = 'partial'; saveTools(); renderToolbar(); } }, 'Partielle'),
      el('button', { class: 'chip' + (ed.eraserMode === 'stroke' ? ' active' : ''), onclick: () => { ed.eraserMode = 'stroke'; saveTools(); renderToolbar(); } }, 'Trait entier'));
  }
  const list = ed.tool === 'highlighter' ? HL_WIDTHS : ed.tool === 'eraser' ? ERASER_SIZES : ed.tool === 'pen' ? PEN_WIDTHS : [];
  const cur = ed.tool === 'highlighter' ? ed.hlWidth : ed.tool === 'eraser' ? ed.eraserSize : ed.penWidth;
  widths.replaceChildren(...list.map((w, i) => {
    const d = 4 + i * 4;
    return el('button', { class: 'wbtn' + (w === cur ? ' active' : ''), title: 'Épaisseur', onclick: () => {
      if (ed.tool === 'highlighter') ed.hlWidth = w; else if (ed.tool === 'eraser') ed.eraserSize = w; else ed.penWidth = w;
      saveTools(); renderToolbar();
    } }, el('i', { style: `width:${d}px;height:${d}px` }));
  }));
  $('#widths').previousElementSibling.hidden = !list.length;
  $('#undo').disabled = !ed.undo.length; $('#redo').disabled = !ed.redo.length;
}

function setTool(t) { commitText(); ed.tool = t; saveTools(); renderToolbar(); }
document.querySelectorAll('#toolbar [data-tool]').forEach((b) => b.addEventListener('click', () => setTool(b.dataset.tool)));

// ---- Pages ----
function pageWidth() {
  const avail = $('#pages').clientWidth - 32;
  return Math.max(260, Math.min(avail, 880) * ed.zoom);
}

const pageObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    const p = ed.pages[+e.target.dataset.index]; if (!p) continue;
    p.visible = e.isIntersecting;
    if (p.visible && p.stale) renderPage(p.index);
  }
}, { root: document.querySelector('#pages'), rootMargin: '800px 0px' });

function buildPages(keepScroll = false) {
  const box = $('#pages');
  const scroll = box.scrollTop;
  pageObserver.disconnect();
  const w = pageWidth(), h = w * R.PAGE_H / R.PAGE_W;
  const dpr = Math.min(2.5, devicePixelRatio || 1);
  ed.pages = ed.content.pages.map((_, i) => {
    const canvas = el('canvas');
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    const foot = el('div', { class: 'pfoot' }, `Page ${i + 1} / ${ed.content.pages.length}`,
      ed.content.pages.length > 1 ? el('button', { title: 'Supprimer cette page', onclick: () => deletePage(i) }, '🗑 Supprimer') : null);
    const div = el('div', { class: 'page', 'data-index': i, style: `width:${w}px;height:${h}px` }, canvas, foot);
    return { index: i, div, canvas, w, h, scale: canvas.width / R.PAGE_W, visible: false, stale: true };
  });
  box.replaceChildren(...ed.pages.map((p) => p.div), el('button', { class: 'add-page', onclick: addPage }, '＋ Ajouter une page'));
  ed.pages.forEach((p) => pageObserver.observe(p.div));
  if (keepScroll) box.scrollTop = scroll;
  renderToolbar();
}

function renderPage(i) {
  const p = ed.pages[i]; if (!p) return;
  if (!p.visible) { p.stale = true; return; }
  p.stale = false;
  const ctx = p.canvas.getContext('2d');
  ctx.setTransform(p.scale, 0, 0, p.scale, 0, 0);
  const page = ed.content.pages[i];
  R.drawPage(ctx, page, ed.meta.paper, ed.meta.paperColor, {
    getImage, pdfImage: page.pdf ? getPdfPage(page.pdf, p.canvas.width) : null, replay: ed.replay,
    exclude: ed.editingText && ed.editingText.page === i ? new Set([ed.editingText.id]) : null,
  });
}
function renderAll() { ed.pages.forEach((p) => { p.stale = true; if (p.visible) renderPage(p.index); }); }
listeners.add(() => { if (ed.id) renderAll(); });

function addPage() {
  snapshot();
  ed.content.pages.push({ strokes: [], texts: [], images: [], pdf: null });
  changed(); buildPages(true);
  ed.pages.at(-1).div.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
async function deletePage(i) {
  if (!(await confirmBox('Supprimer la page', `La page ${i + 1} et son contenu seront supprimés.`))) return;
  snapshot(); ed.content.pages.splice(i, 1); changed(); buildPages(true);
}

function setZoom(z) { ed.zoom = Math.min(3, Math.max(0.5, z)); saveTools(); buildPages(true); }
$('#ed-zoom-in').onclick = () => setZoom(ed.zoom * 1.2);
$('#ed-zoom-out').onclick = () => setZoom(ed.zoom / 1.2);
let resizeTimer;
window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { if (ed.id) buildPages(true); }, 200); });

// ---- Saisie au stylo, à la souris ou au doigt ----
const gesture = { active: false };

function pagePoint(p, e) {
  const r = p.canvas.getBoundingClientRect();
  return [(e.clientX - r.left) / r.width * R.PAGE_W, (e.clientY - r.top) / r.height * R.PAGE_H];
}

$('#pages').addEventListener('pointerdown', (e) => {
  const div = e.target.closest?.('.page'); if (!div || e.target.tagName !== 'CANVAS') return;
  if (e.pointerType === 'pen') ed.penSeen = true;
  if (e.button !== 0 && e.pointerType === 'mouse') return;
  const p = ed.pages[+div.dataset.index];
  // Avec un stylo, le doigt sert à faire défiler (la paume n'écrit pas).
  if (e.pointerType === 'touch' && ed.penSeen) {
    Object.assign(gesture, { active: true, scroll: true, id: e.pointerId, y: e.clientY, x: e.clientX });
    return;
  }
  e.preventDefault();
  e.target.setPointerCapture(e.pointerId);
  const [x, y] = pagePoint(p, e);
  if (ed.tool === 'text') { commitText(); startText(p.index, x, y); return; }
  commitText();
  snapshot();
  Object.assign(gesture, { active: true, scroll: false, id: e.pointerId, page: p, points: [], pressure: 0, n: 0, changed: false });
  if (ed.tool === 'eraser') { eraseAt(p.index, x, y); return; }
  const base = document.createElement('canvas'); base.width = p.canvas.width; base.height = p.canvas.height;
  if (p.stale) { p.visible = true; renderPage(p.index); }
  base.getContext('2d').drawImage(p.canvas, 0, 0);
  gesture.base = base;
  addPoint(e);
});

function addPoint(e) {
  const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
  for (const ev of (evs.length ? evs : [e])) {
    const [x, y] = pagePoint(gesture.page, ev);
    const pr = ev.pointerType === 'pen' ? (ev.pressure || 0.5) : 0.5;
    const pts = gesture.points;
    if (pts.length >= 3) { const dx = x - pts[pts.length - 3], dy = y - pts[pts.length - 2]; if (dx * dx + dy * dy < 0.8) continue; }
    pts.push(+x.toFixed(2), +y.toFixed(2), +pr.toFixed(3));
    gesture.pressure += pr; gesture.n++;
  }
  if (!gesture.raf) gesture.raf = requestAnimationFrame(drawLive);
}

function liveStroke() {
  const hl = ed.tool === 'highlighter';
  return { tool: hl ? 'HIGHLIGHTER' : 'PEN', color: hl ? ed.hlColor : ed.penColor,
    width: hl ? ed.hlWidth : R.pressureWidth(ed.penWidth, gesture.pressure / Math.max(1, gesture.n)), points: gesture.points };
}

function drawLive() {
  gesture.raf = 0;
  if (!gesture.active || !gesture.base) return;
  const p = gesture.page, ctx = p.canvas.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(gesture.base, 0, 0);
  ctx.setTransform(p.scale, 0, 0, p.scale, 0, 0);
  R.drawStroke(ctx, liveStroke());
}

$('#pages').addEventListener('pointermove', (e) => {
  if (!gesture.active || e.pointerId !== gesture.id) return;
  if (gesture.scroll) {
    const box = $('#pages'); box.scrollTop -= e.clientY - gesture.y; box.scrollLeft -= e.clientX - gesture.x;
    gesture.y = e.clientY; gesture.x = e.clientX; return;
  }
  e.preventDefault();
  if (ed.tool === 'eraser') {
    const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of (evs.length ? evs : [e])) { const [x, y] = pagePoint(gesture.page, ev); eraseAt(gesture.page.index, x, y); }
    return;
  }
  addPoint(e);
});

function endGesture(e) {
  if (!gesture.active || e.pointerId !== gesture.id) return;
  gesture.active = false;
  if (gesture.scroll) return;
  if (ed.tool === 'eraser') {
    if (!gesture.changed) ed.undo.pop(); else changed();
    renderToolbar(); return;
  }
  cancelAnimationFrame(gesture.raf); gesture.raf = 0;
  if (gesture.points.length < 3) { ed.undo.pop(); renderPage(gesture.page.index); return; }
  const s = liveStroke();
  const stroke = { id: nextId(), tool: s.tool, color: s.color, width: +s.width.toFixed(3), points: gesture.points, rec: null, t: -1 };
  ed.content.pages[gesture.page.index].strokes.push(stroke);
  gesture.base = null;
  renderPage(gesture.page.index);
  changed(); renderToolbar();
}
$('#pages').addEventListener('pointerup', endGesture);
$('#pages').addEventListener('pointercancel', endGesture);

// ---- Gomme (comme sur Android : partielle ou trait entier) ----
function segDist2(px, py, ax, ay, bx, by) {
  const vx = bx - ax, vy = by - ay, l = vx * vx + vy * vy;
  let t = l > 0 ? ((px - ax) * vx + (py - ay) * vy) / l : 0; t = Math.max(0, Math.min(1, t));
  const dx = ax + t * vx - px, dy = ay + t * vy - py; return dx * dx + dy * dy;
}
function strokeHits(s, x, y, r) {
  const lim = r + s.width / 2, lim2 = lim * lim, p = s.points;
  for (let i = 0; i + 1 < p.length; i += 3) {
    if (i + 4 < p.length) { if (segDist2(x, y, p[i], p[i + 1], p[i + 3], p[i + 4]) <= lim2) return true; }
    else { const dx = p[i] - x, dy = p[i + 1] - y; if (dx * dx + dy * dy <= lim2) return true; }
  }
  return false;
}
function erasePartial(s, x, y, r) {
  const p = s.points, n = p.length / 3, lim = r + s.width / 2, lim2 = lim * lim;
  let hit = false;
  const inside = Array.from({ length: n }, (_, i) => { const dx = p[i * 3] - x, dy = p[i * 3 + 1] - y; const v = dx * dx + dy * dy <= lim2; if (v) hit = true; return v; });
  if (!hit) return null;
  const pieces = []; let start = -1;
  for (let i = 0; i <= n; i++) {
    const out = i < n && !inside[i];
    if (out && start < 0) start = i;
    if (!out && start >= 0) { if (i - start >= 2) pieces.push({ ...s, id: nextId(), points: p.slice(start * 3, i * 3) }); start = -1; }
  }
  return pieces;
}
function eraseAt(pi, x, y) {
  const page = ed.content.pages[pi];
  const r = ed.eraserSize;
  let any = false; const out = [];
  for (const s of page.strokes) {
    if (ed.eraserMode === 'stroke') { if (strokeHits(s, x, y, r)) { any = true; continue; } out.push(s); }
    else { const res = strokeHits(s, x, y, r) ? (erasePartial(s, x, y, r) ?? [s]) : null; if (res) { any = any || res.length !== 1 || res[0] !== s; out.push(...res); } else out.push(s); }
  }
  // Le texte et les photos se gomment en entier.
  const ctx = ed.pages[pi].canvas.getContext('2d');
  const texts = page.texts.filter((t) => !(x >= t.x - r && x <= t.x + t.width + r && y >= t.y - r && y <= t.y + R.textHeight(ctx, t) + r));
  if (texts.length !== page.texts.length) any = true;
  if (any) { page.strokes = out; page.texts = texts; gesture.changed = true; renderPage(pi); }
}

// ---- Texte ----
function startText(pi, x, y) {
  const page = ed.content.pages[pi];
  const ctx = ed.pages[pi].canvas.getContext('2d');
  let t = page.texts.find((b) => x >= b.x && x <= b.x + b.width && y >= b.y && y <= b.y + R.textHeight(ctx, b));
  const isNew = !t;
  if (!t) t = { id: nextId(), x: Math.min(x, R.PAGE_W - 200), y: Math.max(0, y - 18), width: Math.min(560, R.PAGE_W - Math.min(x, R.PAGE_W - 200) - 40), text: '', size: 30, color: ed.penColor };
  const p = ed.pages[pi], k = p.w / R.PAGE_W;
  const ta = el('textarea', { spellcheck: 'true', style: `left:${t.x * k}px;top:${t.y * k}px;width:${t.width * k}px;font-size:${t.size * k}px;color:${R.css(t.color, 1)}` });
  ta.value = t.text;
  const fit = () => { ta.style.height = 'auto'; ta.style.height = Math.max(t.size * k * 1.3, ta.scrollHeight) + 'px'; };
  ta.addEventListener('input', fit);
  ta.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); commitText(); } e.stopPropagation(); });
  ed.editingText = { page: pi, id: t.id, box: t, isNew, ta };
  p.div.append(ta); fit();
  renderPage(pi);
  ta.focus(); setTimeout(() => ta.focus());
}

function commitText() {
  const e = ed.editingText; if (!e) return;
  ed.editingText = null;
  const text = e.ta.value.replace(/\s+$/, '');
  e.ta.remove();
  const page = ed.content.pages[e.page];
  if (!page) return;
  const old = page.texts.find((t) => t.id === e.id);
  if (text !== (old?.text ?? '')) {
    snapshot();
    if (!text) page.texts = page.texts.filter((t) => t.id !== e.id);
    else if (old) old.text = text;
    else page.texts.push({ ...e.box, text });
    changed();
  }
  renderPage(e.page); renderToolbar();
}

// ---- Photos ----
$('#ed-image').onclick = () => $('#file-image').click();
$('#file-image').onchange = async (e) => {
  const file = e.target.files[0]; e.target.value = '';
  if (!file || !ed.id) return;
  try {
    const img = await importImage(file);
    const pi = currentPageIndex();
    const w = Math.min(600, img.width), h = w * img.height / img.width;
    snapshot();
    ed.content.pages[pi].images.push({ id: nextId(), asset: img.asset, x: (R.PAGE_W - w) / 2, y: Math.max(40, (R.PAGE_H - h) / 3), width: w, height: Math.min(h, R.PAGE_H - 80) });
    changed(); renderPage(pi); renderToolbar();
  } catch { toast("Impossible d'ajouter cette image."); }
};
function currentPageIndex() {
  const box = $('#pages').getBoundingClientRect();
  let best = 0, bestD = Infinity;
  ed.pages.forEach((p) => { const r = p.div.getBoundingClientRect(); const d = Math.abs(r.top + r.height / 2 - (box.top + box.height / 2)); if (d < bestD) { bestD = d; best = p.index; } });
  return best;
}

// ---- Papier ----
$('#ed-paper').onclick = (e) => {
  const anchor = e.currentTarget;
  const choose = (style, color) => { repo.setPaper(ed.id, style, color); ed.meta = repo.note(ed.id); renderAll(); closePopover(); };
  popover(anchor, [
    el('h4', {}, 'Papier'),
    el('div', { class: 'grid2' }, R.PAPERS.map(([k, label]) => el('button', { class: 'item' + (k === ed.meta.paper ? ' active' : ''), onclick: () => choose(k, ed.meta.paperColor) }, (k === ed.meta.paper ? '✓ ' : '') + label))),
    el('h4', {}, 'Couleur'),
    el('div', { class: 'colors' }, R.PAPER_COLORS.map(([c, label]) => el('button', { class: 'swatch' + (c === ed.meta.paperColor ? ' active' : ''), title: label, style: `background:${R.css(c, 1)}`, onclick: () => choose(ed.meta.paper, c) }))),
  ]);
};

// ---- Enregistrements audio (faits sur le téléphone) ----
function renderAudioButton() { $('#ed-audio').hidden = !(ed.content.recordings || []).length; }
$('#ed-audio').onclick = (e) => {
  const recs = ed.content.recordings || [];
  menu(e.currentTarget, recs.map((r, i) => [`▶ Enregistrement ${i + 1} · ${new Date(r.createdAt).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })}`
    + (r.durationMs ? ` · ${Math.round(r.durationMs / 60000)} min` : ''), () => playRecording(r)]));
};
async function playRecording(r) {
  const blob = await repo.getAsset(r.asset);
  const bar = $('#audio-bar');
  if (!blob) { toast("Cet enregistrement n'est pas encore arrivé. Réessayez dans un instant."); return; }
  const audio = el('audio', { controls: true, autoplay: true });
  audio.src = URL.createObjectURL(blob);
  const stop = () => { ed.replay = null; renderAll(); bar.hidden = true; URL.revokeObjectURL(audio.src); bar.replaceChildren(); };
  audio.addEventListener('timeupdate', () => { ed.replay = { rec: r.id, pos: audio.currentTime * 1000 }; renderAll(); });
  bar.replaceChildren(el('span', {}, '🎧 L’encre s’affiche au fil de l’enregistrement'), audio, el('button', { onclick: stop }, 'Fermer'));
  bar.hidden = false;
}

// ---- Divers ----
$('#ed-back').onclick = () => closeEditor();
$('#ed-title').onclick = async () => { const t = await askText('Renommer la note', ed.meta.title); if (t) { repo.renameNote(ed.id, t); ed.meta = repo.note(ed.id); $('#ed-title').textContent = ed.meta.title; } };
$('#undo').onclick = undo; $('#redo').onclick = redo;
$('#ed-print').onclick = async () => { commitText(); ed.pages.forEach((p) => { p.visible = true; }); renderAll(); await new Promise((r) => setTimeout(r, 600)); window.print(); };

document.addEventListener('keydown', (e) => {
  if (!ed.id || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'INPUT' || !$('#modal-wrap').hidden) return;
  const k = e.key.toLowerCase();
  if ((e.ctrlKey || e.metaKey) && k === 'z' && !e.shiftKey) { e.preventDefault(); undo(); }
  else if ((e.ctrlKey || e.metaKey) && (k === 'y' || (k === 'z' && e.shiftKey))) { e.preventDefault(); redo(); }
  else if (e.ctrlKey || e.metaKey || e.altKey) return;
  else if (k === 'p') setTool('pen'); else if (k === 'h') setTool('highlighter');
  else if (k === 'e') setTool('eraser'); else if (k === 't') setTool('text');
});

// ============ Démarrage ============

async function start() {
  await repo.load();
  let config = {};
  try { const r = await fetch('sync.json', { cache: 'no-store' }); if (r.ok) config = await r.json(); } catch { /* hors ligne */ }
  sync = new Sync(repo, config);
  sync.addEventListener('status', renderSyncButtons);
  document.querySelectorAll('.sync-btn').forEach((b) => b.addEventListener('click', accountDialog));
  repo.addEventListener('change', () => {
    if (!$('#library').hidden) renderLibrary();
    if (ed.id && !repo.note(ed.id)) { toast('Cette note a été supprimée sur un autre appareil.'); closeEditor(); }
  });

  $('#new-note').onclick = () => { const n = repo.createNote(currentFolder()); openEditor(n.id); };
  $('#import-pdf').onclick = () => $('#file-pdf').click();
  $('#file-pdf').onchange = async (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) { const n = await importPdf(f, currentFolder()); if (n) openEditor(n.id); } };
  $('#new-folder').onclick = async () => { const n = await askText('Nouveau dossier', '', 'Créer'); if (n) { const f = repo.createFolder(n, null); lib.filter = f.id; renderLibrary(); } };
  $('#search').oninput = (e) => { lib.query = e.target.value; renderLibrary(); };
  $('#sort').onchange = (e) => { lib.sort = e.target.value; renderLibrary(); };
  $('#menu-btn').onclick = () => $('#sidebar').classList.toggle('open');

  // Glisser-déposer d'un PDF sur la bibliothèque.
  document.addEventListener('dragover', (e) => e.preventDefault());
  document.addEventListener('drop', async (e) => {
    e.preventDefault();
    const f = [...(e.dataTransfer?.files || [])].find((x) => x.type === 'application/pdf');
    if (f && !$('#library').hidden) { const n = await importPdf(f, currentFolder()); if (n) openEditor(n.id); }
  });

  renderLibrary();
  await sync.init();
  if (sync.configured && !sync.status.email) accountDialog();
  const m = location.hash.match(/^#note=(.+)$/);
  if (m && repo.note(m[1])) { history.replaceState(null, '', location.pathname); openEditor(m[1]); }
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
}

window.addEventListener('beforeunload', () => { commitText(); flushSave(); });
start();
