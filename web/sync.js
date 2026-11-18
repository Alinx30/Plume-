// Synchronisation entre l'interface PC et le carnet central de ce PC (qui reçoit aussi le téléphone).
// Mêmes règles que l'appli Android : pour chaque note, la version modifiée le plus récemment gagne.
import * as db from './store.js';

class HttpError extends Error {
  constructor(code, body) { super(`HTTP ${code}`); this.code = code; this.body = body || ''; }
}

export class Sync extends EventTarget {
  constructor(repo) {
    super();
    this.repo = repo;
    this.available = false; // vrai dans le programme Plume pour PC
    this.token = null;
    this.status = { running: false, lastSync: 0, error: null };
    this.hub = null; // infos du carnet central (nom du PC, appareils associés, code d'association)
    this.uploaded = new Set();
    this.again = false;
    this.timer = null;
  }

  async init() {
    try {
      const r = await fetch('hub/local-token', { cache: 'no-store' });
      if (r.ok) { this.token = (await r.json()).token; this.available = true; }
    } catch { /* simple navigateur : pas de synchronisation */ }
    this.uploaded = new Set((await db.get('kv', 'uploaded')) || []);
    this.status.lastSync = (await db.get('kv', 'lastSync')) || 0;
    if (!this.available) { this.emit(); return; }
    await this.refreshInfo();
    this.repo.addEventListener('local', () => this.request(1500));
    // Le téléphone dépose ses changements sur le PC : on les récupère régulièrement.
    setInterval(() => this.request(0), 5000);
    setInterval(() => this.refreshInfo(), 4000);
    this.request(0);
  }

  emit() { this.dispatchEvent(new Event('status')); }
  set(p) { Object.assign(this.status, p); this.emit(); }

  async http(method, path, { body, raw = false } = {}) {
    const headers = { Authorization: `Bearer ${this.token}` };
    let payload = body;
    if (body !== undefined && !(body instanceof Blob)) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    const res = await fetch(path, { method, headers, body: payload, cache: 'no-store' });
    if (!res.ok) throw new HttpError(res.status, await res.text().catch(() => ''));
    if (raw) return res;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  async refreshInfo() {
    try {
      const before = JSON.stringify(this.hub);
      this.hub = await (await fetch('hub/info', { cache: 'no-store' })).json();
      if (JSON.stringify(this.hub) !== before) this.dispatchEvent(new Event('hub'));
    } catch { /* ignoré */ }
  }
  async startPairing() { this.hub = await this.http('POST', 'hub/pairing'); this.dispatchEvent(new Event('hub')); }
  async stopPairing() { this.hub = await this.http('DELETE', 'hub/pairing'); this.dispatchEvent(new Event('hub')); }
  async removeDevice(id) { this.hub = await this.http('DELETE', `hub/devices/${id}`); this.dispatchEvent(new Event('hub')); }

  request(delay = 0) {
    if (!this.available) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.run(), delay);
  }

  async run() {
    if (!this.available) return;
    if (this.status.running) { this.again = true; return; }
    let error;
    do {
      this.again = false;
      this.status.running = true;
      error = null;
      try { await this.once(); } catch (e) {
        console.warn('Synchronisation', e);
        error = e instanceof HttpError ? `Erreur (${e.code}).` : 'Le carnet central ne répond pas.';
      }
      const t = Date.now();
      if (!error) await db.put('kv', 'lastSync', t);
      this.set({ running: false, error, lastSync: error ? this.status.lastSync : t });
    } while (this.again && !error);
  }

  async once() {
    let changed = false;
    changed = (await this.syncFolders()) || changed;
    changed = (await this.syncNotes()) || changed;
    if (changed) await this.repo.commit(false);
  }

  tombRow(id, at, key) { return { id, updated_at: at, deleted: true, [key]: {} }; }

  async syncFolders() {
    const repo = this.repo;
    const remote = await this.http('GET', 'hub/folders');
    const local = new Map(repo.index.folders.map((f) => [f.id, f]));
    const tombs = repo.tombs.folders;
    const push = []; let changed = false;
    const apply = (r) => { if (r.data && typeof r.data === 'object') { repo.applyRemoteFolder({ ...r.data, id: r.id, updatedAt: r.updated_at }); changed = true; } };
    for (const r of remote) {
      const l = local.get(r.id); const tomb = tombs[r.id];
      if (tomb != null) {
        if (r.deleted) delete tombs[r.id];
        else if (r.updated_at > tomb) { delete tombs[r.id]; apply(r); }
        else { push.push(this.tombRow(r.id, tomb, 'data')); delete tombs[r.id]; }
      } else if (r.deleted) {
        if (l && (l.updatedAt || 0) <= r.updated_at) { repo.removeFolderLocally(r.id); changed = true; }
      } else if (!l || r.updated_at > (l.updatedAt || 0)) apply(r);
      else if ((l.updatedAt || 0) > r.updated_at) push.push(this.folderRow(l));
    }
    const ids = new Set(remote.map((r) => r.id));
    for (const l of local.values()) if (!ids.has(l.id)) push.push(this.folderRow(l));
    for (const [id, at] of Object.entries(tombs)) if (!ids.has(id)) { push.push(this.tombRow(id, at, 'data')); delete tombs[id]; }
    if (push.length) await this.http('POST', 'hub/folders', { body: push });
    await db.put('kv', 'tombs', repo.tombs);
    return changed;
  }

  folderRow(f) { return { id: f.id, updated_at: f.updatedAt || 0, deleted: false, data: f }; }

  async syncNotes() {
    const repo = this.repo;
    const remote = await this.http('GET', 'hub/notes');
    const local = new Map(repo.index.notes.map((n) => [n.id, n]));
    const tombs = repo.tombs.notes;
    const busy = (id) => id === repo.editingNoteId;
    let changed = false;
    for (const r of remote) {
      const l = local.get(r.id); const tomb = tombs[r.id];
      if (tomb != null) {
        if (r.deleted) delete tombs[r.id];
        else if (r.updated_at > tomb) { delete tombs[r.id]; changed = (await this.pull(r.id, r.updated_at)) || changed; }
        else { await this.http('POST', 'hub/notes', { body: [this.tombRow(r.id, tomb, 'meta')] }); delete tombs[r.id]; }
      } else if (r.deleted) {
        if (l && l.updatedAt <= r.updated_at && !busy(r.id)) { repo.removeNoteLocally(r.id); changed = true; }
      } else if (!l) changed = (await this.pull(r.id, r.updated_at)) || changed;
      else if (r.updated_at > l.updatedAt) { if (!busy(r.id)) changed = (await this.pull(r.id, r.updated_at)) || changed; }
      else if (l.updatedAt > r.updated_at) await this.push(l);
    }
    const ids = new Set(remote.map((r) => r.id));
    for (const l of local.values()) if (!ids.has(l.id)) await this.push(l);
    for (const [id, at] of Object.entries(tombs)) if (!ids.has(id)) { await this.http('POST', 'hub/notes', { body: [this.tombRow(id, at, 'meta')] }); delete tombs[id]; }
    await db.put('kv', 'tombs', repo.tombs);
    return changed;
  }

  assetsOf(c) {
    const out = new Set();
    for (const p of c.pages || []) { if (p.pdf) out.add(p.pdf.asset); for (const im of p.images || []) out.add(im.asset); }
    for (const r of c.recordings || []) out.add(r.asset);
    return out;
  }
  assetPath(name) { return `hub/assets/${encodeURIComponent(name)}`; }

  async push(meta) {
    const content = await this.repo.loadContent(meta.id);
    for (const a of this.assetsOf(content)) {
      if (this.uploaded.has(a)) continue;
      const blob = await this.repo.getAsset(a);
      if (!blob) continue;
      await this.http('PUT', this.assetPath(a), { body: blob });
      this.uploaded.add(a); await db.put('kv', 'uploaded', [...this.uploaded]);
    }
    await this.http('POST', 'hub/notes', { body: [{ id: meta.id, updated_at: meta.updatedAt, deleted: false, meta, content }] });
  }

  async pull(id, updatedAt) {
    let o;
    try { o = await this.http('GET', `hub/notes/${encodeURIComponent(id)}`); } catch (e) { if (e.code === 404) return false; throw e; }
    const content = o.content || { pages: [{}], recordings: [] };
    for (const a of this.assetsOf(content)) {
      if (await this.repo.hasAsset(a)) { this.uploaded.add(a); continue; }
      try {
        const res = await this.http('GET', this.assetPath(a), { raw: true });
        await this.repo.putAsset(a, await res.blob());
        this.uploaded.add(a);
      } catch (e) { if (!(e instanceof HttpError) || e.code !== 404) throw e; }
    }
    await db.put('kv', 'uploaded', [...this.uploaded]);
    if (id === this.repo.editingNoteId) return false;
    await this.repo.applyRemoteNote({ ...o.meta, id, updatedAt, pageCount: (content.pages || []).length || 1 }, content);
    return true;
  }
}
