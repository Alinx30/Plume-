# Plume pour PC (Windows)

Programme Electron qui affiche l'interface de `web/` et sert de carnet central pour le téléphone :
- `hub.js` : serveur local (port 8765) et recherche sur le Wi-Fi (UDP 8766). Le téléphone s'associe avec un code à 6 chiffres affiché sur le PC.
- `main.js` : fenêtre, icône dans la zone de notification, démarrage avec Windows.

Les notes du PC sont dans `%APPDATA%\Plume\notes-pc` (copie centrale) et dans le stockage de la fenêtre.

Essai local : `cp -r ../web web && npm install && npm start`.
