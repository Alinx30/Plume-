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
