// Le « carnet central » de Plume sur le PC : garde une copie de toutes les notes et
// permet au téléphone de se synchroniser sur le Wi-Fi de la maison, sans aucun serveur en ligne.
'use strict';
const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const HTTP_PORT = 8765;
const DISCOVERY_PORT = 8766;

function createHub({ dataDir, webDir, port = HTTP_PORT, discoveryPort = DISCOVERY_PORT, log = () => {} }) {
  const dirs = { notes: path.join(dataDir, 'notes'), assets: path.join(dataDir, 'assets') };
  for (const d of [dataDir, dirs.notes, dirs.assets]) fs.mkdirSync(d, { recursive: true });
  const configFile = path.join(dataDir, 'hub.json');
  const foldersFile = path.join(dataDir, 'folders.json');

  const readJson = (f, def) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return def; } };
  const writeJson = (f, v) => { const tmp = f + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(v)); fs.renameSync(tmp, f); };

  const config = readJson(configFile, {});
  config.id ||= crypto.randomUUID();
  config.devices ||= []; // { token, name, pairedAt, lastSeen }
  config.localToken ||= crypto.randomBytes(24).toString('hex');
  writeJson(configFile, config);
  const saveConfig = () => writeJson(configFile, config);

  let folders = readJson(foldersFile, {}); // id -> row
  const noteIndex = new Map(); // id -> { id, meta, updated_at, deleted }
  for (const f of fs.readdirSync(dirs.notes)) {
    if (!f.endsWith('.json')) continue;
    const row = readJson(path.join(dirs.notes, f), null);
    if (row?.id) noteIndex.set(row.id, { id: row.id, meta: row.meta, updated_at: row.updated_at, deleted: !!row.deleted });
  }

  let pairing = null; // { code, until }
  const failed = { count: 0, until: 0 };
  const listeners = new Set();
  const emit = () => listeners.forEach((f) => f());

  const safeName = (s) => typeof s === 'string' && /^[A-Za-z0-9._-]{1,200}$/.test(s) && !s.startsWith('.');
  const noteFile = (id) => path.join(dirs.notes, crypto.createHash('sha1').update(id).digest('hex') + '.json');

  function ips() {
    const out = [];
    for (const list of Object.values(os.networkInterfaces())) for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal) out.push(a.address);
    }
    return out;
  }

  function info(local) {
    const base = { app: 'plume', id: config.id, name: os.hostname(), port };
    if (!local) return base;
    return { ...base, ips: ips(), devices: config.devices.map((d) => ({ name: d.name, pairedAt: d.pairedAt, lastSeen: d.lastSeen, id: d.token.slice(0, 8) })),
      pairing: pairing && pairing.until > Date.now() ? { code: pairing.code, until: pairing.until } : null };
  }

  const isLocal = (req) => {
    const a = req.socket.remoteAddress || '';
    return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
  };

  function auth(req) {
    const t = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
    if (!t) return null;
    if (t === config.localToken && isLocal(req)) return { name: 'PC' };
    const d = config.devices.find((x) => x.token === t);
    if (d) { const now = Date.now(); if (now - (d.lastSeen || 0) > 30000) { d.lastSeen = now; saveConfig(); emit(); } }
    return d || null;
  }

  const send = (res, code, body, type = 'application/json') => {
    res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(body === undefined ? '' : (typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body)));
  };
  const readBody = (req, limit = 600 * 1024 * 1024) => new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(new Error('too big')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });

  // Une ligne n'est acceptée que si elle est au moins aussi récente que celle qu'on a.
  function upsertFolders(rows) {
    let n = 0;
    for (const r of rows) {
      if (!r || typeof r.id !== 'string' || typeof r.updated_at !== 'number') continue;
      const old = folders[r.id];
      if (old && old.updated_at > r.updated_at) continue;
      folders[r.id] = { id: r.id, data: r.data ?? {}, updated_at: r.updated_at, deleted: !!r.deleted }; n++;
    }
    if (n) { writeJson(foldersFile, folders); emit(); }
  }
  function upsertNotes(rows) {
    let n = 0;
    for (const r of rows) {
      if (!r || typeof r.id !== 'string' || typeof r.updated_at !== 'number') continue;
      const old = noteIndex.get(r.id);
      if (old && old.updated_at > r.updated_at) continue;
      const row = { id: r.id, meta: r.meta ?? {}, content: r.deleted ? null : (r.content ?? null), updated_at: r.updated_at, deleted: !!r.deleted };
      writeJson(noteFile(r.id), row);
      noteIndex.set(r.id, { id: r.id, meta: row.meta, updated_at: row.updated_at, deleted: row.deleted }); n++;
    }
    if (n) emit();
  }

  const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.webmanifest': 'application/manifest+json' };

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://plume');
      const p = url.pathname;
      if (!p.startsWith('/hub/')) {
        // L'interface de Plume, uniquement pour ce PC.
        if (!isLocal(req) || !webDir) return send(res, 404, 'Not found', 'text/plain');
        const rel = p === '/' ? 'index.html' : decodeURIComponent(p).replace(/^\/+/, '');
        const file = path.join(webDir, rel);
        if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(res, 404, 'Not found', 'text/plain');
        return send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream');
      }
      if (p === '/hub/info' && req.method === 'GET') return send(res, 200, info(isLocal(req)));
      if (p === '/hub/local-token' && req.method === 'GET') {
        if (!isLocal(req) || !/^(127\.0\.0\.1|localhost)(:\d+)?$/.test(req.headers.host || '')) return send(res, 403, { error: 'forbidden' });
        return send(res, 200, { token: config.localToken });
      }
      if (p === '/hub/pair' && req.method === 'POST') {
        const now = Date.now();
        if (failed.until > now) return send(res, 429, { error: 'wait' });
        const b = JSON.parse((await readBody(req, 10000)).toString() || '{}');
        if (!pairing || pairing.until < now) return send(res, 409, { error: 'not_pairing' });
        if (String(b.code || '').trim() !== pairing.code) {
          if (++failed.count >= 5) { failed.until = now + 60000; failed.count = 0; }
          return send(res, 401, { error: 'bad_code' });
        }
        const token = crypto.randomBytes(24).toString('hex');
        config.devices.push({ token, name: String(b.device || 'Téléphone').slice(0, 60), pairedAt: now, lastSeen: now });
        pairing = null; saveConfig(); emit();
        log('Appareil associé : ' + b.device);
        return send(res, 200, { token, id: config.id, name: os.hostname() });
      }
      // Gestion des associations : seulement depuis ce PC.
      if (p === '/hub/pairing' && (req.method === 'POST' || req.method === 'DELETE')) {
        if (!isLocal(req) || !auth(req)) return send(res, 403, {});
        pairing = req.method === 'POST' ? { code: String(crypto.randomInt(0, 1000000)).padStart(6, '0'), until: Date.now() + 10 * 60000 } : null;
        emit();
        return send(res, 200, info(true));
      }
      const unpair = p.match(/^\/hub\/devices\/([0-9a-f]{8})$/);
      if (unpair && req.method === 'DELETE') {
        if (!isLocal(req) || !auth(req)) return send(res, 403, {});
        config.devices = config.devices.filter((d) => d.token.slice(0, 8) !== unpair[1]); saveConfig(); emit();
        return send(res, 200, info(true));
      }

      if (!auth(req)) return send(res, 401, { error: 'unauthorized' });

      if (p === '/hub/folders') {
        if (req.method === 'GET') return send(res, 200, Object.values(folders));
        if (req.method === 'POST') { upsertFolders(JSON.parse((await readBody(req)).toString())); return send(res, 204); }
      }
      if (p === '/hub/notes') {
        if (req.method === 'GET') return send(res, 200, [...noteIndex.values()]);
        if (req.method === 'POST') { upsertNotes(JSON.parse((await readBody(req)).toString())); return send(res, 204); }
      }
      const note = p.match(/^\/hub\/notes\/(.+)$/);
      if (note && req.method === 'GET') {
        const id = decodeURIComponent(note[1]);
        if (!noteIndex.has(id)) return send(res, 404, {});
        const row = readJson(noteFile(id), null);
        return row ? send(res, 200, { id, meta: row.meta, content: row.content, updated_at: row.updated_at, deleted: row.deleted }) : send(res, 404, {});
      }
      const asset = p.match(/^\/hub\/assets\/(.+)$/);
      if (asset) {
        const name = decodeURIComponent(asset[1]);
        if (!safeName(name)) return send(res, 400, {});
        const file = path.join(dirs.assets, name);
        if (req.method === 'GET') {
          if (!fs.existsSync(file)) return send(res, 404, {});
          res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': fs.statSync(file).size });
          return fs.createReadStream(file).pipe(res);
        }
        if (req.method === 'HEAD') return send(res, fs.existsSync(file) ? 200 : 404);
        if (req.method === 'PUT' || req.method === 'POST') {
          const tmp = file + '.part';
          await new Promise((resolve, reject) => { const out = fs.createWriteStream(tmp); req.pipe(out); out.on('finish', resolve); out.on('error', reject); req.on('error', reject); });
          fs.renameSync(tmp, file);
          return send(res, 204);
        }
      }
      send(res, 404, {});
    } catch (e) {
      log('Erreur : ' + e.message);
      if (!res.headersSent) send(res, 500, { error: 'server' });
    }
  });

  // Le téléphone envoie « PLUME? » à tout le réseau ; le PC répond avec son nom et son port.
  const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  udp.on('message', (msg, rinfo) => {
    if (msg.toString().trim() !== 'PLUME?') return;
    const reply = Buffer.from(JSON.stringify(info(false)));
    udp.send(reply, rinfo.port, rinfo.address);
  });
  udp.on('error', (e) => log('Recherche réseau indisponible : ' + e.message));

  return {
    config, info: () => info(true), onChange: (f) => listeners.add(f),
    start: () => new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '0.0.0.0', () => {
        try { udp.bind(discoveryPort); } catch (e) { log(e.message); }
        resolve();
      });
    }),
    stop: () => { server.close(); try { udp.close(); } catch { /* déjà fermé */ } },
  };
}

module.exports = { createHub, HTTP_PORT, DISCOVERY_PORT };
