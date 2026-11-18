// Permet d'ouvrir Plume sans connexion : on garde une copie des fichiers de l'appli.
const CACHE = 'plume-v1';
const SHELL = ['./', 'index.html', 'style.css', 'app.js', 'repo.js', 'sync.js', 'store.js', 'render.js', 'icon.svg', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  const sameOrigin = url.origin === location.origin;
  if (!sameOrigin) return; // le serveur de synchronisation n'est jamais mis en cache
  // Réseau d'abord (pour avoir la dernière version), copie locale si hors ligne.
  e.respondWith(fetch(e.request).then((res) => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return res;
  }).catch(() => caches.match(e.request).then((r) => r || caches.match('index.html'))));
});
