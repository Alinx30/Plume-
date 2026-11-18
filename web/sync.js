// Synchronisation avec le compte Plume en ligne (Supabase) : mêmes règles que l'appli Android.
import * as db from './store.js';

const MIME = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', m4a: 'audio/mp4', webm: 'audio/webm' };

class HttpError extends Error {
  constructor(code, body) { super(`HTTP ${code}`); this.code = code; this.body = body || ''; }
}

export class Sync extends EventTarget {
  constructor(repo, config) {
    super();
    this.repo = repo;
    this.url = (config.url || '').replace(/\/+$/, '');
    this.key = config.anonKey || '';
    this.configured = this.url.startsWith('https://') || this.url.startsWith('http://localhost') || this.url.startsWith('http://127.');
    this.configured &&= !!this.key;
    this.status = { email: null, running: false, lastSync: 0, error: null };
    this.session = null;
    this.uploaded = new Set();
    this.again = false;
    this.timer = null;
  }

  async init() {
    this.session = await db.get('kv', 'session');
    this.uploaded = new Set((await db.get('kv', 'uploaded')) || []);
    this.status.email = this.session?.email || null;
    this.status.lastSync = (await db.get('kv', 'lastSync')) || 0;
    this.repo.addEventListener('local', () => this.request(3000));
    setInterval(() => { if (!document.hidden) this.request(0); }, 30000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) this.request(0); });
    window.addEventListener('online', () => this.request(0));
    this.emit();
    this.request(0);
  }

  emit() { this.dispatchEvent(new Event('status')); }
  set(p) { Object.assign(this.status, p); this.emit(); }

  async http(method, path, { token, body, headers = {}, raw = false } = {}) {
    const h = { apikey: this.key, ...headers };
    if (token) h.Authorization = `Bearer ${token}`;
    let payload = body;
    if (body !== undefined && !(body instanceof Blob)) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    const res = await fetch(this.url + path, { method, headers: h, body: payload });
    if (!res.ok) throw new HttpError(res.status, await res.text().catch(() => ''));
    if (raw) return res;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  // ---- Compte ----
  async signIn(email, password, create) {
    if (!this.configured) return "La synchronisation n'est pas encore configurée.";
    try {
      const path = create ? '/auth/v1/signup' : '/auth/v1/token?grant_type=password';
      const res = await this.http('POST', path, { body: { email: email.trim(), password } });
      if (!res.access_token) return 'Compte créé. Ouvrez le lien reçu par e-mail, puis connectez-vous.';
      await this.saveSession(res, email.trim());
      this.uploaded = new Set(); await db.put('kv', 'uploaded', []);
      this.set({ email: email.trim(), error: null });
      this.request(0);
      return null;
    } catch (e) {
      if (e instanceof HttpError) return authMessage(e);
      return 'Pas de connexion internet.';
    }
  }

  async signOut(wipe) {
    this.session = null;
    await db.del('kv', 'session'); await db.del('kv', 'lastSync'); await db.put('kv', 'uploaded', []);
    if (wipe) await this.repo.wipe();
    this.set({ email: null, error: null, lastSync: 0 });
  }

  async saveSession(res, email) {
    this.session = {
      access: res.access_token, refresh: res.refresh_token,
      expires: Date.now() + ((res.expires_in || 3600) - 60) * 1000,
      uid: res.user?.id || this.session?.uid, email: email || this.session?.email,
    };
    await db.put('kv', 'session', this.session);
  }

  async token() {
    const s = this.session;
    if (!s) throw new Error('non connecté');
    if (Date.now() < s.expires) return s.access;
    try {
      const res = await this.http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: s.refresh } });
      await this.saveSession(res);
    } catch (e) {
      if (e instanceof HttpError && e.code >= 400 && e.code < 500) {
        await this.signOut(false);
        this.set({ error: 'Session expirée : reconnectez-vous.' });
      }
      throw e;
    }
    return this.session.access;
  }

  // ---- Déclenchement ----
  request(delay = 0) {
    if (!this.configured || !this.session) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.run(), delay);
  }

  async run() {
    if (!this.configured || !this.session) return;
    if (this.status.running) { this.again = true; return; }
    let error;
    do {
      this.again = false;
      this.set({ running: true });
      error = null;
      try { await this.once(); } catch (e) {
        console.warn('Synchronisation', e);
        if (e instanceof HttpError) error = e.code === 404 ? "Le serveur n'est pas prêt (tables manquantes)." : `Erreur du serveur (${e.code}).`;
        else if (e instanceof TypeError) error = 'Pas de connexion internet.';
        else error = e.message || 'Erreur de synchronisation.';
      }
      const t = Date.now();
      if (!error) await db.put('kv', 'lastSync', t);
      this.set({ running: false, error, lastSync: error ? this.status.lastSync : t });
    } while (this.again && !error);
  }

  // ---- Synchronisation ----
  async rows(path) { return this.http('GET', path, { token: await this.token() }); }
  async upsert(table, rows) {
    if (!rows.length) return;
    await this.http('POST', `/rest/v1/${table}?on_conflict=user_id,id`, {
      token: await this.token(), body: rows, headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
    });
  }
  get uid() { return this.session.uid; }
  tombRow(id, at, key) { return { user_id: this.uid, id, updated_at: at, deleted: true, [key]: {} }; }

  async once() {
    await this.token();
    let changed = false;
    changed = (await this.syncFolders()) || changed;
    changed = (await this.syncNotes()) || changed;
    if (changed) await this.repo.commit(false);
  }

  async syncFolders() {
    const repo = this.repo;
    const remote = await this.rows('/rest/v1/folders?select=id,data,updated_at,deleted');
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
    for (const id of Object.keys(tombs)) if (!ids.has(id)) delete tombs[id];
    await this.upsert('folders', push);
    await db.put('kv', 'tombs', repo.tombs);
    return changed;
  }

  folderRow(f) { return { user_id: this.uid, id: f.id, updated_at: f.updatedAt || 0, deleted: false, data: f }; }

  async syncNotes() {
    const repo = this.repo;
    const remote = await this.rows('/rest/v1/notes?select=id,meta,updated_at,deleted');
    const local = new Map(repo.index.notes.map((n) => [n.id, n]));
    const tombs = repo.tombs.notes;
    const busy = (id) => id === repo.editingNoteId;
    let changed = false;
    for (const r of remote) {
      const l = local.get(r.id); const tomb = tombs[r.id];
      if (tomb != null) {
        if (r.deleted) delete tombs[r.id];
        else if (r.updated_at > tomb) { delete tombs[r.id]; changed = (await this.pull(r.id, r.updated_at)) || changed; }
        else { await this.upsert('notes', [this.tombRow(r.id, tomb, 'meta')]); delete tombs[r.id]; }
      } else if (r.deleted) {
        if (l && l.updatedAt <= r.updated_at && !busy(r.id)) { repo.removeNoteLocally(r.id); changed = true; }
      } else if (!l) changed = (await this.pull(r.id, r.updated_at)) || changed;
      else if (r.updated_at > l.updatedAt) { if (!busy(r.id)) changed = (await this.pull(r.id, r.updated_at)) || changed; }
      else if (l.updatedAt > r.updated_at) await this.push(l);
    }
    const ids = new Set(remote.map((r) => r.id));
    for (const l of local.values()) if (!ids.has(l.id)) await this.push(l);
    for (const id of Object.keys(tombs)) if (!ids.has(id)) delete tombs[id];
    await db.put('kv', 'tombs', repo.tombs);
    return changed;
  }

  assetsOf(c) {
    const out = new Set();
    for (const p of c.pages || []) { if (p.pdf) out.add(p.pdf.asset); for (const im of p.images || []) out.add(im.asset); }
    for (const r of c.recordings || []) out.add(r.asset);
    return out;
  }
  assetPath(name) { return `/storage/v1/object/assets/${encodeURIComponent(this.uid)}/${encodeURIComponent(name)}`; }

  async push(meta) {
    const content = await this.repo.loadContent(meta.id);
    for (const a of this.assetsOf(content)) {
      if (this.uploaded.has(a)) continue;
      const blob = await this.repo.getAsset(a);
      if (!blob) continue;
      const ext = a.split('.').pop().toLowerCase();
      await this.http('POST', this.assetPath(a), {
        token: await this.token(), body: blob, headers: { 'x-upsert': 'true', 'Content-Type': MIME[ext] || 'application/octet-stream' },
      });
      this.uploaded.add(a); await db.put('kv', 'uploaded', [...this.uploaded]);
    }
    await this.upsert('notes', [{ user_id: this.uid, id: meta.id, updated_at: meta.updatedAt, deleted: false, meta, content }]);
  }

  async pull(id, updatedAt) {
    const rows = await this.rows(`/rest/v1/notes?id=eq.${encodeURIComponent(id)}&select=meta,content`);
    const o = rows[0]; if (!o) return false;
    const content = o.content || { pages: [{}], recordings: [] };
    for (const a of this.assetsOf(content)) {
      if (await this.repo.hasAsset(a)) { this.uploaded.add(a); continue; }
      try {
        const res = await this.http('GET', this.assetPath(a), { token: await this.token(), raw: true });
        await this.repo.putAsset(a, await res.blob());
        this.uploaded.add(a);
      } catch (e) { if (!(e instanceof HttpError) || (e.code !== 400 && e.code !== 404)) throw e; }
    }
    await db.put('kv', 'uploaded', [...this.uploaded]);
    if (id === this.repo.editingNoteId) return false;
    await this.repo.applyRemoteNote({ ...o.meta, id, updatedAt, pageCount: (content.pages || []).length || 1 }, content);
    return true;
  }
}

function authMessage(e) {
  const b = e.body.toLowerCase();
  if (b.includes('invalid login') || b.includes('invalid_credentials')) return 'E-mail ou mot de passe incorrect.';
  if (b.includes('already registered') || b.includes('user_already_exists')) return 'Ce compte existe déjà : utilisez « Se connecter ».';
  if (b.includes('password') && (b.includes('6') || b.includes('weak'))) return 'Mot de passe trop court (6 caractères au moins).';
  if (b.includes('email not confirmed')) return "Ouvrez d'abord le lien reçu par e-mail.";
  if (b.includes('invalid') && b.includes('email')) return 'Adresse e-mail invalide.';
  return `Erreur du serveur (${e.code}).`;
}
