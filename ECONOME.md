# Économe : une croix sur chaque sort déjà lancé

Le challenge **Économe** interdit de lancer deux fois le même sort ou la même arme dans un combat, pour
chaque personnage. Minobot lit le journal de combat dans le chat, retient ce que chaque personnage a lancé
et pose une croix sur la case correspondante de sa barre de sorts.

## Ce qui a été mesuré (captures `captures/20261112-*`, fenêtre 2560x1369)

### Le chat

- **Fond** : `0xD5CFAA`, le même beige que la bannière de tour.
- **Lignes** : ancrées en bas, à pas fixe de 30 px (12,18 en coordonnées de scène). La dernière occupe
  y 1270..1300, et six lignes sont visibles.
- **Zone lue** : x 410..1160 (scène 17,7..322). À droite, les cases à cocher des filtres empiètent sur la
  ligne du haut, d'où cette limite.
- **OCR Windows, ligne par ligne** : 3 à 12 ms par ligne (27 ms pour la toute première lecture), texte
  exact. Exemples lus :
  - « Foxtrot attaque avec Sceptre du Minotot. »
  - « Hotel lance Vague à Lame. »
  - « (Coup critique) »
  - « (Echec critique) »
- **Ordre des lignes** : la ligne de lancer arrive avant les dégâts, que l'animation retarde de 0,5 à 1 s.
  Un sort de zone peut ensuite pousser plus de six lignes d'un coup.

### La barre de sorts (coordonnées de scène, cases de 25,6)

| Case | Gauche | Haut | Pas |
|---|---|---|---|
| Arme (0) | 486,7 | 485,7 | — |
| Rangée 1 (1–7) | 518,0 | 486,1 | 28,02 en x |
| Rangée 2 (8–14) | 518,0 | 515,4 | 28,02 en x |
| Colonne gauche (15–22) | 3,0 | 192,9 | 28,02 en y |

- **Repères de la barre** :
  - fond entre les cases : `0xB8B18F` ;
  - onglet « Sorts » actif : `0xB4AC8D` (scène 720,3 ; 483,3) ;
  - fiche d'un combattant survolé : elle remplace la barre par `0xD5CFAA`.
- Les bords des cases sont anticrénelés : il ne faut jamais tester la couleur d'un bord.

## Conception

1. **Lire le chat plus vite.** Pendant un combat, `ScreenWatcher` regarde toutes les 50 ms au lieu de
   100. Le chat est lu **à chaque regard**, toujours dans la même fenêtre : celle du premier personnage
   dans l'ordre du joueur. On ne lit pas les fenêtres à tour de rôle, parce que les messages privés
   diffèrent d'une fenêtre à l'autre. L'OCR passe ligne par ligne, et une ligne déjà lue (mêmes pixels)
   n'est jamais relue, si bien qu'un regard sans nouvelle ligne ne coûte qu'une comparaison de pixels.
2. **Repérer les nouvelles lignes.** `FightLog` compare les lignes visibles à celles du regard précédent
   et cherche le plus long chevauchement. Après une pause de plus de 2 s, la lecture sert de base de
   comparaison : ce qui était déjà affiché n'est pas neuf. Il en extrait :
   - `X lance Y.` : un sort ;
   - `X attaque avec Y.` : l'arme.
   Un échec critique ne compte **pas** : la ligne `(Échec critique)` écrite sous le lancer le retire
   (la croix apparaît puis disparaît), et le sort peut être relancé. Une relance n'est signalée qu'une fois
   la ligne suivante lue, pour ne pas compter une relance ratée.
3. **Retenir les lancers.** `SpellUsage` garde, par combat, ce que chaque combattant a lancé. Tout est
   effacé au début d'un combat (`FightTurns.onFightStart`). Un même sort relancé est noté dans le journal
   d'activité : le challenge est raté.
4. **Associer sorts et cases.** `SpellLayouts` stocke des dispositions nommées, au format
   `case → nom du sort`, dans `spell-layouts.json`.
   - Par défaut, un personnage utilise la disposition qui porte le nom de sa classe, ou son propre nom
     s'il n'a pas de classe. Un personnage peut être rattaché à une autre disposition.
   - **Apprentissage** : le joueur clique une case de la barre, puis le journal annonce que ce
     personnage lance un sort dans les 10 s. Minobot retient alors que cette case est ce sort.
   - L'arme n'a pas besoin d'être apprise, c'est toujours la case 0.
5. **Mode réglage**, activé par un interrupteur dans l'overlay :
   - un cadre sur chaque case du personnage au premier plan, vert si la case est connue, braise sinon ;
   - au survol d'une case, le nom du sort qu'elle contient ;
   - une consigne en haut : « clique un sort, puis lance-le ».
6. **Croix**, avec l'interrupteur « Économe » : pendant un combat, une croix sur chaque case déjà
   utilisée par le personnage au premier plan. Les positions viennent de `GameStage`, donc elles sont
   exactes à toute taille de fenêtre. Les croix sont masquées quand :
   - la barre n'est pas visible (fiche d'un combattant, onglet « Obj. ») ;
   - le jeu n'est pas au premier plan ;
   - aucun combat n'est en cours.

## À faire

- [x] Mesurer le chat et les cases ; vérifier l'OCR ligne par ligne
- [x] Plan (ce fichier)
- [x] `vision.ChatLog` : lire les lignes du bas du chat, OCR ligne par ligne avec cache des lignes
- [x] `core.FightLog` : lignes nouvelles, lancers (`domain.Cast`)
- [x] `core.SpellUsage` + `FightTurns.onFightStart`
- [x] `ScreenWatcher` : rythme de combat à 50 ms, lecteurs épinglés à une fenêtre (chat, barre)
- [x] `vision.SpellBar` : géométrie des cases, case sous le curseur, barre visible ou non
- [x] `core.SpellBars` : dernier état vu de la barre, par fenêtre
- [x] `core.SpellLayouts` : dispositions, apprentissage, `spell-layouts.json`
- [x] `ui.MarksView` + `ui.marks.SwingMarks` : croix, cadres, étiquette, consigne
- [x] `feature.econome.SpellMarks` : suivi du premier plan, clics, apprentissage, contenu de la vue
- [x] Interrupteurs « Économe » et « Spell setup » dans l'overlay (`Config`, `Switches`)
- [x] Câblage dans `MinobotApp` ; `CaptureProbe` décrit aussi le chat et la barre
- [x] Tests, `./mvnw verify` (379 tests, tous verts)
- [x] `CaptureProbe --read captures/20261112-201714` : les lancers et les fiches de combattant sont
  bien reconnus
- [ ] Essai en jeu :
  - les croix tombent juste ;
  - les clics traversent la fenêtre des marques ;
  - le coût des regards à 50 ms (logs `LookTimes` en DEBUG).

## Plus tard

- **Empreinte des icônes** : retrouver un sort dans la barre d'un autre personnage de la même classe, même
  s'il est rangé ailleurs, et signaler une barre réorganisée.
- **Choisir la disposition d'un personnage dans l'overlay.** Pour l'instant, cela se fait à la main dans
  `spell-layouts.json`.
- **Apprendre par raccourci clavier** : aujourd'hui, seul un clic sur la case permet l'apprentissage.
- **Chat remonté par le joueur** : d'anciennes lignes peuvent être prises pour des nouvelles. C'est sans
  effet dans le même combat, où une croix posée deux fois reste une croix.
