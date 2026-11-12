![minobot.png](assets/minobot.png)

# Minobot

Un logiciel de confort pour **Dofus Retro**, pour les joueurs multi-comptes. Il ne joue pas à votre
place : il reste en fond (icône à côté de l'horloge) et vous évite les manipulations répétitives.

![overlay.png](assets/overlay.png)

## Fonctions

| Touche | Ce qu'elle fait |
| --- | --- |
| `X1` | **Tous vos personnages cliquent au même endroit** que vous, sans que la fenêtre principale perde le focus. |
| `Shift+X1` | Éteint le clignotement orange des fenêtres dans la barre des tâches. |
| `F8` | **Groupe toute la team** : chacun invite le suivant, qui accepte. |
| `X2` / `Shift+X2` | Passe au personnage suivant / précédent, dans votre ordre, sur l'écran courant. |
| `F9` | Remet les fenêtres dans l'ordre dans la barre des tâches (elles disparaissent un instant, c'est normal). |
| `Shift+Espace` | Ouvre / ferme l'overlay. |
| `Shift+clic molette` | Active / coupe **Auto-pass turns**. |

Et automatiquement :

- **Le bon personnage revient à l'écran** quand c'est son tour en combat, ou quand il est attaqué ou
  invité.
- **Auto-accept trades** (activé par défaut) : un échange proposé par un de vos propres personnages est
  accepté tout seul. Celui d'un autre joueur ne l'est jamais.
- **Auto-pass turns** (désactivé par défaut) : passe le tour de **tous** vos personnages. Une pastille
  orange reste affichée tant que c'est actif ; son bouton « Turn off » coupe la fonction.
- **Les messages privés** s'affichent dans une petite carte au lieu de vous faire changer de fenêtre ;
  cliquez dessus pour aller sur le personnage concerné.

## Installation

1. Téléchargez `Minobot-x.y.z.zip` depuis la
   [page des versions](https://github.com/AdrienLeblanc/minobot/releases/latest) et décompressez-le où
   vous voulez.
2. Lancez **`Minobot.exe`**. Rien d'autre à installer.
3. **Activez les notifications Windows** (elles peuvent être silencieuses, en cochant
   `Masquer le contenu...`).

![windows_settings1.png](assets/windows_settings1.png)
![windows_settings2.png](assets/windows_settings2.png)

> Si Windows affiche « Windows a protégé votre ordinateur », cliquez sur **Informations
> complémentaires** puis **Exécuter quand même**.

Pour quitter : clic droit sur l'icône à côté de l'horloge → **Quitter**.

## L'overlay (`Shift+Espace`, sur une fenêtre de jeu)

- **Vos personnages** : glissez une ligne pour changer l'ordre (utilisé par `X2` et `F9`). Cliquez sur
  « Pick a class » (ou double-cliquez la ligne) pour choisir classe et sexe. Un personnage configuré reste
  dans la liste même déconnecté ; la petite croix le retire.
- **Les interrupteurs** Auto-pass turns et Auto-accept trades.
- **ACTIVITY** et **WHISPERS** : ce que Minobot vient de faire, et les messages privés reçus.
- **Keybinds ›** : cliquez sur une touche puis appuyez sur la nouvelle. Les lettres et chiffres sont
  refusés (ce sont les touches du chat).
- Un curseur en bas règle la taille du panneau.

L'ordre, les classes et les touches sont retenus ; la taille et les interrupteurs repartent de zéro au
redémarrage.

## Réglages avancés (`config.json`)

Créé à côté de `Minobot.exe` au premier lancement. Relancez Minobot après modification.

| Réglage | Rôle |
| --- | --- |
| `multiclick_exclude` | Personnages exclus du clic multiple, ex. `["Marchand"]`. |
| `auto_accept_trade` / `auto_pass_turn` | État des interrupteurs au démarrage. |
| `log_level` | `"DEBUG"` pour un journal détaillé. |

## En cas de problème

Le journal est dans **`logs/minobot.log`**, à côté de `Minobot.exe` : joignez-le si vous signalez un
souci. Une touche qui ne répond plus se réattribue dans le tiroir Keybinds.
