# DogmatixPlus

[English](README.md) · [Nederlands](README.nl.md) · **Français** · [Deutsch](README.de.md) · [Español](README.es.md)

**Trouvez, téléchargez et organisez des jeux rétro sur votre téléphone Android ou votre console portable.**

DogmatixPlus rassemble les jeux des sources que *vous* ajoutez dans une seule grande liste, où l’on peut faire des recherches. Il les télécharge dans les bons dossiers et vous aide à garder votre collection bien rangée. Il fonctionne au toucher *et* avec une manette de jeu. Il est donc à l’aise sur les consoles portables comme les Retroid, Anbernic ou Kinhank.

> **L’application est livrée sans aucun jeu ni lien de téléchargement.** Vous ajoutez vos propres sources, et vous êtes responsable de ne télécharger que ce que vous avez le droit d’avoir.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/library.png" width="210" alt="La bibliothèque"><br><sub>Vos jeux dans une seule liste — un ✓ vert veut dire que vous l’avez déjà</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/duplicates.png" width="210" alt="Jeux en double"><br><sub>Trouvez les jeux que vous avez en double</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/overview.png" width="210" alt="Aperçu de la bibliothèque"><br><sub>Voyez votre collection console par console</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/scan-progress.png" width="210" alt="Progression du scan"><br><sub>Voyez où en est un scan</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/delete-dialog.png" width="210" alt="Confirmation de suppression"><br><sub>Vous voyez toujours quels fichiers vont disparaître</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/settings.png" width="210" alt="Paramètres"><br><sub>Les nouveaux outils sont dans les Paramètres</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/credits.png" width="210" alt="Crédits"><br><sub>Les crédits de toutes les personnes impliquées</sub></td>
    <td></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/library-landscape.png" width="720" alt="La bibliothèque en mode paysage sur une console portable"></p>

*Les captures d’écran utilisent des titres de jeux inventés et des fichiers vides de substitution, et montrent l’application en anglais.*

---

## Que peut faire l’application ?

### Trouver des jeux
- **Une seule liste** avec les jeux de toutes vos sources.
- **Une recherche** qui pardonne les erreurs : les accents, les tirets et les lettres doublées n’ont pas d’importance, donc « yugioh » trouve *Yu-Gi-Oh!*.
- **Filtrez** par console, région, langue et type, et **triez** par nom ou par taille.
- Un **✓** vert marque les jeux que vous avez déjà.
- **★ Favoris** : ajoutez une étoile aux jeux que vous aimez et n’affichez que ceux-là.

### Télécharger des jeux
- Fonctionne avec les **liens directs, les torrents et les liens magnet**.
- **Plusieurs téléchargements à la fois**, avec pause, reprise, possibilité de réessayer et limite de vitesse.
- **Les fichiers ZIP et 7z sont décompressés** pour vous.
- Chaque console a **son propre dossier**. Les dossiers que vous avez déjà (comme `gba` ou `psx`) sont réutilisés, et vous pouvez fusionner deux dossiers qui désignent la même console.
- Votre **liste de téléchargements est conservée** quand vous fermez l’application. Sélectionnez plusieurs téléchargements pour les arrêter, les réessayer ou les supprimer ensemble.
- En option : **TorBox** et **Real-Debrid** — des services payants qui récupèrent les torrents pour vous, de sorte que le téléchargement est un fichier normal et rapide.

### Garder sa collection bien rangée *(nouveau dans DogmatixPlus)*
- **Jeux en double** : trouve les jeux présents plusieurs fois sur votre appareil, montre combien de place vous gagnez et vous laisse supprimer la copie en trop. Rien n’est supprimé avant que vous ayez vu exactement quels fichiers vont disparaître.
- **Aperçu de la bibliothèque** : pour chaque console, combien de jeux sont indexés, combien vous en possédez, combien sont sur votre appareil et quelle place ils prennent, et la date du dernier scan.
- **Sauvegarder** et **Restaurer une sauvegarde** : enregistrez vos paramètres, vos sources, vos favoris et vos téléchargements dans un seul fichier et restaurez-les plus tard — pratique pour un nouvel appareil.
- **Progression du scan** : un pourcentage et le temps restant pendant que vos sources sont lues.

### Pensé pour les consoles portables
- **Tout contrôler avec une manette** : D-pad, A/B/X/Y et les boutons d’épaule. Les indications à l’écran, en bas, correspondent à votre manette (Xbox, Nintendo ou PlayStation), et vous pouvez inverser les boutons si votre manette les signale à l’envers.
- Affichage **paysage et portrait**, avec un panneau de filtres à côté de la liste sur les grands écrans.
- Thème **clair, sombre ou noir pur** (agréable sur les écrans OLED) et cinq couleurs d’accent.

### Compatible avec votre lanceur de jeux
- **ES-DE** et **iiSU** reçoivent une entrée « Search for more games » dans chaque console, configurée avec un seul bouton. **Daijishō** vous montre les quelques valeurs à saisir.

### Compatible avec RomM
- Envoyez les téléchargements terminés vers votre serveur **RomM**, ou utilisez RomM comme source de jeux.

### Vos sources, à votre façon
- Ajoutez des sources à la main, ou **importez et exportez**-les sous forme de fichier pour les partager entre appareils.
- Un court **guide de premier démarrage** vous aide à choisir votre dossier de ROM et à importer vos sources.

### Langues
- **Anglais, espagnol, néerlandais, français et allemand** (*Paramètres → Langue*, ou selon la langue de votre téléphone).

---

## Installation

1. Ouvrez la **[page Releases](https://github.com/Tufein/DogmatixPlus/releases)** sur votre téléphone ou votre console portable, ou téléchargez-y le fichier et copiez-le sur l’appareil.
2. Téléchargez l’un des deux fichiers :
   - **`DogmatixPlus-debug.apk`** — *le choix facile.* Il s’installe **à côté** du Dogmatix officiel, donc rien de ce qui vous appartient n’est touché.
   - **`DogmatixPlus-release.apk`** — une version plus légère. Elle ne peut pas être installée par-dessus le Dogmatix officiel ; vous devrez d’abord le désinstaller (faites une sauvegarde avant).
3. Ouvrez le fichier et autorisez **« Installer des applications inconnues »** si Android vous le demande.

Il vous faut **Android 10 ou plus récent**. L’application n’est pas sur Google Play.

## Premier démarrage

1. Le guide de bienvenue explique les bases.
2. **Choisissez votre dossier de ROM** — le dossier où vos jeux doivent aller.
3. **Importez vos sources** (un fichier avec vos listes de jeux) — ou passez cette étape et ajoutez des sources plus tard dans l’onglet **Sources**.
4. Ouvrez la **Bibliothèque**, trouvez un jeu et touchez-le (ou appuyez sur **A**) pour le télécharger.

## Utiliser une manette

| Bouton | Ce qu’il fait |
|---|---|
| D-pad | Se déplacer |
| **A** | Choisir / télécharger |
| **B** | Revenir en arrière d’une étape |
| **X** | Infos du jeu |
| **Y** | Rechercher |
| **Select** | Ajouter un jeu aux favoris ou l’en retirer |
| **LB / RB** | Passer des filtres à la liste, et inversement |
| **ZL / ZR** | Section précédente / suivante |
| **R3** | Masquer ou afficher le panneau de filtres |

Tout fonctionne aussi au toucher. Les indications ne s’affichent que lorsqu’une manette est connectée.

## Bon à savoir

- **Supprimer les doublons est définitif.** L’application affiche d’abord chaque fichier, mais il n’y a pas de corbeille.
- **Un fichier de sauvegarde contient vos clés de compte** (TorBox, Real-Debrid, RomM). Gardez-le privé.
- **La fenêtre d’infos du jeu reste vide dans les fichiers proposés au téléchargement ici**, car elle a besoin d’une clé gratuite, fournie par une base de données de jeux et ajoutée lors de la compilation de l’application.
- DogmatixPlus ne cherche pas de jeux tout seul. Il lit seulement les sources que **vous** ajoutez.

---

## Crédits

DogmatixPlus est une petite couche ajoutée par-dessus deux autres projets. La plus grande partie de ce que vous utilisez tous les jours vient d’eux.

| Projet | Réalisé par | Ce qu’il a apporté |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | L’application d’origine et tout son moteur : lecture des sources, classement des jeux par console / région / langue, recherche, téléchargement et décompression. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) | La version pour consoles portables : contrôle à la manette, affichage paysage, thèmes, favoris, pause et reprise, le guide de premier démarrage, ES-DE / iiSU / Daijishō, TorBox et Real-Debrid, RomM. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | Recherche de doublons, aperçu de la bibliothèque, sauvegarde et restauration, progression du scan, traductions en néerlandais, en français et en allemand, et ces versions. |

DogmatixPlus a été **réalisé avec l’aide de l’IA** : le code, les tests et la documentation ont été écrits avec un assistant IA et vérifiés lors de plusieurs tours de relecture. Les décisions, l’orientation et la publication reviennent à la personne qui s’occupe du projet.

## Avertissement

Cette application est uniquement destinée à un usage éducatif. Il vous incombe de vous assurer que vous avez le droit légal de télécharger tout contenu.

Milou et Dogmatix n’ont pas de licence, donc tous les droits sur leur code restent à leurs auteurs. DogmatixPlus est une modification personnelle non officielle et n’est affilié à aucun des deux projets. Si vous êtes l’un des auteurs d’origine et que vous souhaitez que quelque chose soit modifié ou retiré, merci d’ouvrir une issue.

---

## Pour les développeurs

Les détails techniques — comment l’application est construite, comment la recherche de doublons décide, l’organisation des dossiers, les liens profonds, les technologies utilisées et plus encore — se trouvent dans **[TECHNICAL.md](TECHNICAL.md)**. Voir aussi [FRONTENDS.md](FRONTENDS.md) pour la configuration des lanceurs et [CHANGELOG.md](CHANGELOG.md) pour chaque changement. Le fichier TECHNICAL.md est en anglais.
