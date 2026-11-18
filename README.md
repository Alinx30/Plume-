# Plume

Application Android de prise de notes manuscrites, pensée pour les tablettes avec stylet.

## Fonctions (version 0.1)

- Écriture au stylet ou au doigt, sensible à la pression, avec lissage du trait
- Stylo (6 couleurs, 3 épaisseurs), surligneur (passe sous l'encre), gomme par trait
- Gomme du stylet (bouton ou bout gomme) reconnue automatiquement
- Mode « stylet uniquement » : la paume est ignorée et le doigt fait défiler (activé au premier usage du stylet)
- Zoom et déplacement à deux doigts
- Annuler / rétablir
- Papier : lignes, quadrillage, points ou blanc
- Notes de plusieurs pages, rangées dans des carnets, avec miniatures
- Export PDF (format A4)
- Sauvegarde automatique, tout reste sur l'appareil

## Installer

Chaque modification de `main` compile l'APK automatiquement. Sur votre appareil Android :

1. Ouvrez la page [Releases](../../releases/tag/latest) et téléchargez `Plume.apk`.
2. Ouvrez le fichier. Android demande d'autoriser l'installation d'applications depuis votre navigateur : acceptez.

Les nouvelles versions s'installent par-dessus l'ancienne sans perdre vos notes.

## Compiler soi-même

Avec Android Studio (ou le SDK Android et JDK 17) : `./gradlew assembleRelease`.
Le fichier `keystore/plume-dev.jks` est une clé de développement volontairement publique.

## Prochaines étapes

- Texte tapé au clavier
- Lasso pour sélectionner, déplacer et redimensionner
- Import et annotation de PDF
- Enregistrement audio synchronisé avec l'écriture
- Recherche dans les notes
