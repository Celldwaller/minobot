# Core — the Windows mechanics

`core/` is the machinery every feature stands on: `WindowManager`, `FocusManager`, `KeyboardMonitor`,
`NotificationManager`, `FightTurns`, `Requests`, `FlashSuppressor`, `SystemTrayManager`, `ActivityLog`, `WhisperLog`, `input/`
(`Input`, `InputSimulator`), and `domain/` (`GameWindow`, `Character`, `DofusClass`, `Sex`,
`Notification`, `Activity`, `Whisper`). It speaks of windows, handles and messages so the features above
it can speak of characters.

## The two records of what happened

Every feature here acts **while the player is looking at something else** — that is the whole point of
them. A turn ends in a window nobody is watching, a trade is accepted in a blink, a relay walks eight
characters. Left with nothing but the result, the player is asked to trust that the right thing happened.
So there are two logs the panel reads back:

- **`ActivityLog`** — what Minobot did. Every feature writes one line per act (`record(what, detail)`,
  both already in the player's words — the panel does no phrasing of its own), and `WindowManager.refresh`
  adds the one thing the player did not do: a character whose window went away.
- **`WhisperLog`** — the whispers themselves. Written by the whisper toaster, and **outliving the cards
  it raises**: a card stands ten seconds, which is right for a corner of a fight and wrong for the
  message. The card is raised under the id the log minted, so the panel can list the same whisper long
  after, and a click there is the same jump the card offered.

Both are deliberately **small and forgetful** — a bounded deque, oldest dropped, nothing on disk. They
answer *"what did that just do?"*, not *"what happened this session?"*; the day the second question is
asked, it is the log file that should be opened, not these made bigger. They are written from every
hotkey's and every notification's virtual thread and read from the panel's, so each synchronizes on its
own deque. **There is one instance of each, shared** (wired in `MinobotApp`): a feature with a log of its
own would be a feature the panel cannot show.

## A fight turn, a request: two witnesses, heard once

**The direction is to read the game off the screen rather than off its toasts** — a toast goes deaf on a
window the multi-click has posted to, the screen does not. Whispers excepted: they stay on the toasts.

A turn begins in two ways the application can see: the game's **turn toast**, and the **banner** that
opens the turn on the screen, read through the OCR. The toast goes deaf on a window
the multi-click has posted to; the banner does not. Each is reported to **`FightTurns`**, and the features
that act on a turn (`TurnPasser`, `TurnFocuser`) listen there and to neither source: a turn seen as a toast
*and* four times on the banner is **one** turn, because a second `F1` would end whichever turn is up by
then. The same fighter again is the same turn unless another fighter was reported in between or it has
been quiet for `SAME_TURN` (2 s). But a fighter seen again within `LINGER` (1.5 s) of their last sighting,
after another's, is **a banner left over**, not a turn: the watcher reads the windows one by one, and right
after the next fighter's banner in one window another can still show the last one's — read as a turn, it
was a second `F1` ending the next fighter's turn. The banner names monsters too — that is what separates two turns of
one character — so a listener matches the name **exactly** (`WindowManager.windowOf`, never the substring
`findWindow`) and a monster's turn finds no window.

A **request** — a group invitation, a trade, a duel — is the same story per character: a box on the asked
character's screen (`vision.RequestDialog`) and a toast, both worded the same (`domain.Request.in`), both
reported to **`Requests`**, which the invitation relay, the trade accepter and the request auto-focus
listen to. The box stays until answered and is re-read at every round, so a request is the same one as
long as it keeps being seen within `SAME_REQUEST` (5 s). Both feeds share their dedupe (`Sightings`).

`vision.ScreenWatcher` captures **one** window every 250 ms, **round** the characters' windows (not the
login screen; a minimized one gives its look to the next), and hands each capture to every
`ScreenReader`: the banner is in every window, so turns are still read at 4 Hz; a box is only in its own
window, so it is found within one round — a second for four characters. One capture a look is the cost,
whatever the number of readers; the watcher logs what its looks cost at debug, every 40 looks — read
that before optimizing anything. Each reader gets its own `RememberingTextReader`: a banner or a box
standing still is the same strip look after look, and the same pixels are not handed to the OCR twice. It runs **always**, on a **platform** thread (each look is
a native capture). Without an OCR language for the user, `MinobotApp` starts no watcher, and turns and
requests are the toasts' alone.

## Concurrency

- `KeyboardMonitor` polls on a dedicated **platform** thread (a 50 Hz loop of native calls — a virtual
  thread would buy nothing and pinning its carrier would be a trap). Its hotkey table is **replaced,
  never mutated**: a rebind assembles the next one aside and swaps the reference, because the loop is
  reading it at the very moment the player edits a key. And a rebind seeds each key's state from the
  keyboard as it *is* — a key still held is not a fresh press, or naming a hotkey would fire it.
- **Every hotkey and notification callback runs on its own virtual thread**, the equivalent of
  `asyncio.create_task`. A long `inviteAll` must never deafen the other hotkeys.
- Anything that sleeps (the focus sequence, the invitation relay) is therefore free to do so.

## The foreground is a single resource

There is one screen, and several threads want it. `FocusManager` is what keeps them from fighting:

- `focus(hwnd)` is **deliberate** — a hotkey asked for it. It waits its turn, and it **returns whether
  the window really holds the foreground**. A caller that goes on to type *must* check it: keystrokes
  sent to a window that never came up land in whichever one did.
- `focusIfIdle(hwnd)` is **opportunistic** — a toast suggested it. It stands aside rather than queue up.
- A feature that focuses *and types* holds `focus.takeOver()` for its whole sequence. Without it the
  notification auto-focus, which reacts to the same toasts the invitation relay waits on, lands between
  two keystrokes and sends the rest of a `/invite` to another character's window.
