// Plume pour Windows : la fenêtre de l'appli et le carnet central qui synchronise le téléphone.
'use strict';
const { app, BrowserWindow, Tray, Menu, nativeImage, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { createHub, HTTP_PORT } = require('./hub');

const startHidden = process.argv.includes('--hidden');
let win = null;
let tray = null;
let quitting = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.whenReady().then(start);
}

function iconPath() { return path.join(__dirname, 'web', 'icon-512.png'); }

function showWindow() {
  if (!win) createWindow();
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 420, minHeight: 500,
    title: 'Plume', icon: iconPath(), backgroundColor: '#F6F4EF', autoHideMenuBar: true, show: false,
    webPreferences: { contextIsolation: true, sandbox: true, spellcheck: true },
  });
  win.setMenuBarVisibility(false);
  win.loadURL(`http://127.0.0.1:${HTTP_PORT}/`);
  win.once('ready-to-show', () => { if (!startHidden || app.openedOnce) win.show(); app.openedOnce = true; });
  // Les liens externes s'ouvrent dans le navigateur.
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.session.setSpellCheckerLanguages(['fr']);
  // Fermer la fenêtre garde Plume en arrière-plan pour que le téléphone puisse se synchroniser.
  win.on('close', (e) => {
    if (quitting) return;
    e.preventDefault();
    win.hide();
    if (!app.shownTrayHint && tray) {
      app.shownTrayHint = true;
      tray.displayBalloon?.({ title: 'Plume reste disponible', content: 'Plume continue en arrière-plan pour synchroniser votre téléphone. Clic droit sur l’icône pour quitter.' });
    }
  });
  win.on('closed', () => { win = null; });
}

function buildTray() {
  const img = nativeImage.createFromPath(iconPath()).resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.setToolTip('Plume');
  const refresh = () => {
    const auto = app.getLoginItemSettings().openAtLogin;
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Ouvrir Plume', click: showWindow },
      { type: 'separator' },
      { label: 'Démarrer avec Windows', type: 'checkbox', checked: auto, click: () => {
        app.setLoginItemSettings({ openAtLogin: !auto, args: ['--hidden'] }); refresh();
      } },
      { type: 'separator' },
      { label: 'Quitter Plume', click: () => { quitting = true; app.quit(); } },
    ]));
  };
  refresh();
  tray.on('click', showWindow);
}

async function start() {
  const hub = createHub({
    dataDir: path.join(app.getPath('userData'), 'notes-pc'),
    webDir: path.join(__dirname, 'web'),
    log: (m) => console.log('[plume]', m),
  });
  try {
    await hub.start();
  } catch (e) {
    dialog.showErrorBox('Plume', `Plume n'a pas pu démarrer (port ${HTTP_PORT} déjà utilisé ?).\n\n${e.message}`);
    app.exit(1);
    return;
  }
  // Première ouverture : démarrage automatique avec Windows (désactivable depuis l'icône).
  const marker = path.join(app.getPath('userData'), 'demarrage-auto.txt');
  if (app.isPackaged && !fs.existsSync(marker)) {
    app.setLoginItemSettings({ openAtLogin: true, args: ['--hidden'] });
    fs.writeFileSync(marker, 'ok');
  }
  buildTray();
  createWindow();
}

app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', (e) => { /* reste dans la zone de notification */ });
