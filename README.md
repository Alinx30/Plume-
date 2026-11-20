# Plume

Application Android de prise de notes manuscrites, pensée pour les tablettes avec stylet.

## Fonctions (version 0.3)

**Bibliothèque**
- Barre latérale : toutes les notes, récentes, non classées, dossiers et sous-dossiers
- Recherche, tri (modification, création, titre), affichage en grille avec miniatures ou en liste
- Renommer, déplacer, dupliquer, supprimer

**Écriture**
- Pages en défilement continu ; une page s'ajoute quand on écrit sur la dernière
- Barre d'outils flottante : stylo, surligneur, gomme, lasso, texte, photo. Toucher l'outil actif ouvre ses réglages
- Encre sensible à la pression, lissée ; surligneur sous l'encre
- Gomme partielle ou trait entier ; bouton gomme du stylet reconnu
- Formes : garder le stylet immobile en fin de trait pour obtenir une ligne, un cercle, un triangle ou un rectangle
- Lasso : déplacer, redimensionner, recolorer, dupliquer, supprimer
- Zones de texte tapé
- Anti-paume : au premier usage du stylet, le doigt sert à défiler (bouton pour changer)
- Zoom et défilement à deux doigts, avec inertie

**PDF et photos**
- Importer un PDF (bouton ou « Ouvrir avec / Partager vers Plume ») et écrire dessus
- Ajouter des photos, déplaçables et redimensionnables
- Export de la note en PDF

**Audio**
- Enregistrer pendant la prise de notes
- Relecture synchronisée : l'encre écrite plus tard est estompée et réapparaît au fil de l'audio
- Toucher un trait pendant la relecture pour réécouter ce moment

## Installer

Chaque modification de `main` compile l'APK automatiquement. Sur votre appareil Android :

1. Ouvrez la page [Releases](../../releases/tag/latest) et téléchargez `Plume.apk`.
2. Ouvrez le fichier. Android demande d'autoriser l'installation d'applications depuis votre navigateur : acceptez.

Les nouvelles versions s'installent par-dessus l'ancienne sans perdre vos notes.

## Compiler soi-même

Avec Android Studio (ou le SDK Android et JDK 17) : `./gradlew assembleRelease`.
Le fichier `keystore/plume-dev.jks` est une clé de développement volontairement publique.

## Prochaines étapes

- Recherche dans l'écriture manuscrite et conversion en texte
- Deux notes côte à côte
- Modèles de pages et couvertures

## Version PC et synchronisation (sans serveur)

Plume existe aussi pour PC Windows : `Plume-PC-Setup.exe` dans la release « latest ». Le PC garde une copie de toutes les notes ; quand le téléphone et le PC sont sur le même Wi-Fi, ils se synchronisent directement, sans Internet ni service en ligne.

- Association une seule fois : sur le PC, « Associer mon téléphone » affiche un code à 6 chiffres ; sur le téléphone, nuage en haut de la bibliothèque, « Rechercher mon PC », puis le code.
- Pour chaque note, la version modifiée le plus récemment l'emporte. Les suppressions sont transmises.
- Code : `web/` (interface PC), `desktop/` (programme Windows et carnet central), `app/.../sync/` (côté Android).

## Version iPad et iPhone

Plume existe aussi pour iPad et iPhone (iPadOS / iOS 16 ou plus récent), écrite en Swift avec PencilKit, le moteur d'écriture d'Apple : Apple Pencil sensible à la pression, anti-paume, gomme, lasso et palette d'outils d'Apple. Les notes ont le même format que sur Android et se synchronisent avec le PC de la même façon (même Wi-Fi, code à 6 chiffres, sans serveur).

**Ce que fait la version 0.1**
- Bibliothèque : dossiers et sous-dossiers, toutes les notes, récentes, non classées, recherche, tri, miniatures, renommer, déplacer, dupliquer, supprimer
- Écriture : pages en défilement continu (une page s'ajoute quand on écrit sur la dernière), stylo, surligneur, gomme, lasso, règle, annuler / rétablir, zoom à deux doigts
- Les 10 papiers et 6 couleurs de papier d'Android
- Importer un PDF (bouton, ou « Partager vers Plume » depuis Fichiers ou Mail) et écrire dessus ; ajouter des photos ; zones de texte
- Export de la note en PDF
- Synchronisation avec Plume PC : nuage en haut de la bibliothèque, « Rechercher mon PC », puis le code affiché sur le PC
- Les enregistrements audio faits sur Android sont conservés (la lecture n'est pas encore disponible sur iPad)

**Installer sans Mac**

Chaque modification de `main` compile `Plume-iOS.ipa` (non signé) dans la release « latest ». Apple n'autorise l'installation que d'applis signées :

1. Sur le PC Windows, installez iTunes (version du site d'Apple, pas celle du Microsoft Store) et [Sideloadly](https://sideloadly.io).
2. Branchez l'iPad en USB, faites-lui confiance, puis glissez `Plume-iOS.ipa` dans Sideloadly et entrez votre identifiant Apple.
3. Sur l'iPad : Réglages > Général > VPN et gestion de l'appareil, faites confiance à votre identifiant. Sur iPadOS 16 ou plus, activez aussi Réglages > Confidentialité et sécurité > Mode développeur.

Avec un identifiant Apple gratuit, l'appli doit être réinstallée tous les 7 jours (les notes sont conservées). Un compte Apple Developer (99 €/an) permet une signature d'un an ou une installation par TestFlight.

**Compiler soi-même** (Mac avec Xcode 15 ou plus) : `brew install xcodegen`, puis dans `ios/` : `xcodegen generate` et ouvrir `Plume.xcodeproj`.
