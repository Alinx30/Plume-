// Stockage local dans le navigateur (IndexedDB) : bibliothèque, contenu des notes, fichiers joints.
const DB_NAME = 'plume';
let dbp = null;

function db() {
  if (!dbp) dbp = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const d = req.result;
      for (const s of ['kv', 'contents', 'assets']) if (!d.objectStoreNames.contains(s)) d.createObjectStore(s);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}

async function op(store, mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction(store, mode);
    const req = fn(tx.objectStore(store));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const get = (store, key) => op(store, 'readonly', (s) => s.get(key));
export const put = (store, key, value) => op(store, 'readwrite', (s) => s.put(value, key));
export const del = (store, key) => op(store, 'readwrite', (s) => s.delete(key));
export const keys = (store) => op(store, 'readonly', (s) => s.getAllKeys());
export const clear = (store) => op(store, 'readwrite', (s) => s.clear());

export const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  }));

// Identifiants numériques des traits, compatibles avec Android (millisecondes * 1000 + compteur).
let seq = Date.now() * 1000;
export const nextId = () => { const n = Date.now() * 1000; seq = Math.max(seq + 1, n); return seq; };
