// Bibliothèque locale : mêmes données et mêmes règles que l'appli Android (NoteRepository).
import * as db from './store.js';
import { FOLDER_COLORS } from './render.js';

const now = () => Date.now();
const emptyContent = () => ({ pages: [{ strokes: [], texts: [], images: [], pdf: null }], recordings: [] });

export class Repo extends EventTarget {
  index = { folders: [], notes: [] };
  tombs = { notes: {}, folders: {} };
  editingNoteId = null;
  paper = { style: 'LINED', color: 0xFFFFFFFF };

  async load() {
    this.index = (await db.get('kv', 'index')) || { folders: [], notes: [] };
    this.index.folders ||= []; this.index.notes ||= [];
    this.tombs = (await db.get('kv', 'tombs')) || { notes: {}, folders: {} };
    this.paper = (await db.get('kv', 'paper')) || this.paper;
  }

  /** Enregistre l'index ; `local` = changement fait ici (à envoyer au serveur). */
  async commit(local = true) {
    await db.put('kv', 'index', this.index);
    await db.put('kv', 'tombs', this.tombs);
    this.dispatchEvent(new Event('change'));
    if (local) this.dispatchEvent(new Event('local'));
  }

  // ---- Dossiers ----
  folder(id) { return this.index.folders.find((f) => f.id === id); }
  note(id) { return this.index.notes.find((n) => n.id === id); }

  createFolder(name, parentId) {
    const t = now();
    const f = { id: db.uuid(), name: name.trim() || 'Dossier', parentId: parentId ?? null,
      color: FOLDER_COLORS[this.index.folders.length % FOLDER_COLORS.length], createdAt: t, updatedAt: t };
    this.index.folders.push(f);
    this.commit();
    return f;
  }
  renameFolder(id, name) { const f = this.folder(id); if (f && name.trim()) { f.name = name.trim(); f.updatedAt = now(); this.commit(); } }
  setFolderColor(id, color) { const f = this.folder(id); if (f) { f.color = color; f.updatedAt = now(); this.commit(); } }
  folderTree(id) {
    const out = new Set([id]); let grew = true;
    while (grew) { grew = false; for (const f of this.index.folders) if (out.has(f.parentId) && !out.has(f.id)) { out.add(f.id); grew = true; } }
    return out;
  }
  deleteFolder(id) {
    const tree = this.folderTree(id); const t = now();
    const removed = this.index.notes.filter((n) => tree.has(n.notebookId));
    tree.forEach((f) => { this.tombs.folders[f] = t; });
    removed.forEach((n) => { this.tombs.notes[n.id] = t; db.del('contents', n.id); });
    this.index.folders = this.index.folders.filter((f) => !tree.has(f.id));
    this.index.notes = this.index.notes.filter((n) => !tree.has(n.notebookId));
    this.commit();
  }

  // ---- Notes ----
  createNote(folderId, paper = this.paper, title) {
    const t = now();
    const date = new Date().toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
    const n = { id: db.uuid(), notebookId: folderId ?? null, title: title || `Note du ${date}`, createdAt: t, updatedAt: t,
      paper: paper.style, pageCount: 1, paperColor: paper.color };
    this.index.notes.push(n);
    this.commit();
    return n;
  }
  touch(id, fn) { const n = this.note(id); if (n) { fn(n); n.updatedAt = now(); this.commit(); } }
  renameNote(id, title) { if (title.trim()) this.touch(id, (n) => { n.title = title.trim(); }); }
  moveNote(id, folderId) { this.touch(id, (n) => { n.notebookId = folderId ?? null; }); }
  setPaper(id, style, color) {
    this.touch(id, (n) => { n.paper = style; n.paperColor = color; });
    this.paper = { style, color }; db.put('kv', 'paper', this.paper);
  }
  async duplicateNote(id) {
    const n = this.note(id); if (!n) return null;
    const content = await this.loadContent(id); const t = now();
    const copy = { ...n, id: db.uuid(), title: n.title + ' (copie)', createdAt: t, updatedAt: t };
    await db.put('contents', copy.id, content);
    this.index.notes.push(copy); this.commit();
    return copy;
  }
  deleteNote(id) {
    this.tombs.notes[id] = now();
    this.index.notes = this.index.notes.filter((n) => n.id !== id);
    db.del('contents', id);
    this.commit();
  }
  async loadContent(id) {
    const c = (await db.get('contents', id)) || emptyContent();
    c.pages ||= [{}]; c.recordings ||= [];
    for (const p of c.pages) { p.strokes ||= []; p.texts ||= []; p.images ||= []; p.pdf ??= null; }
    return c;
  }
  async saveContent(id, content) {
    await db.put('contents', id, content);
    this.touch(id, (n) => { n.pageCount = content.pages.length; });
  }

  // ---- Fichiers joints ----
  putAsset(name, blob) { return db.put('assets', name, blob); }
  getAsset(name) { return db.get('assets', name); }
  async hasAsset(name) { return (await db.get('assets', name)) != null; }

  // ---- Changements venant du serveur ----
  applyRemoteFolder(f) {
    const i = this.index.folders.findIndex((x) => x.id === f.id);
    if (i >= 0) this.index.folders[i] = f; else this.index.folders.push(f);
  }
  removeFolderLocally(id) { this.index.folders = this.index.folders.filter((f) => f.id !== id); }
  async applyRemoteNote(meta, content) {
    await db.put('contents', meta.id, content);
    const i = this.index.notes.findIndex((x) => x.id === meta.id);
    if (i >= 0) this.index.notes[i] = meta; else this.index.notes.push(meta);
  }
  removeNoteLocally(id) { this.index.notes = this.index.notes.filter((n) => n.id !== id); db.del('contents', id); }

  /** Efface tout (déconnexion avec nettoyage). */
  async wipe() {
    await db.clear('contents'); await db.clear('assets');
    this.index = { folders: [], notes: [] }; this.tombs = { notes: {}, folders: {} };
    await this.commit(false);
  }
}
