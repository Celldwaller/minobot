# The features

The user-facing features. A feature **speaks of characters** (`GameWindow`, which carries a `name()`)
and keeps whatever Windows demands in one named leaf at the bottom of the file — see the root
`CLAUDE.md` for that principle.

When adding one, **make sure the others still work**: they all compete for the focus. Read
`core/CLAUDE.md` (*The foreground is a single resource*) before you do — a feature that focuses *and*
types must hold `focus.takeOver()` for its whole sequence, or the notification auto-focus lands between
its keystrokes.

## Where each feature lives

This package is split by **functional domain**, one subpackage each, so a `CLAUDE.md` loads only when
you work in the subtree it governs — none of them is loaded just because you opened `feature/`.

- **`window/`** — the multi-boxer's window mechanics: the multi-window click (`x1` / `shift+x1`), the
  window cycler (`x2` / `shift+x2`), the taskbar reorder (`F9`).
- **`group/`** — the party: the group-invitation relay (`F8`).
- **`overlay/`** — the control panel drawn over the game (`shift+space`), and its `OverlayController`.
- **`notification/`** — the features that live on the game's Windows toasts and on the fight's turns:
  the notification auto-focus, the turn auto-focus, auto-pass turns, auto-accept trades, and the whisper
  toast. They coordinate through a
  `claims(Notification)` seam, so read that package before touching any one of them.

## Testing

Feature tests sit **in the same package as the feature they drive**: a test reaches the feature's
package-private `onNotification` to hand it a toast the way `NotificationManager` would. The one shared
helper, `Features` (in this root test package, `public` so every subpackage test can use it), wires a
feature onto a `FakeWindowApi`/`FakeInput` desktop exactly as `MinobotApp` wires it onto the real one.
