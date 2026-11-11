# The features that answer the game's calls

The features that answer what the game calls out: a character attacked, invited, messaged, offered a
trade, or with a turn to play. Turns and requests are read off the **screen** only, which keeps speaking
for a window gone deaf to toasts (see `core/CLAUDE.md`); the rest — an attack, a whisper — comes in a
**Windows toast**. These features turn that call into an action. Each **speaks of characters** (`GameWindow`) and keeps the Windows detail —
the French wording a message is matched by — in one named constant. Read `core/CLAUDE.md` (*The
foreground is a single resource*) before touching any of them: they all react to the same stream and
compete for the one screen.

## The `claims` seams

The **notification auto-focus** (`NotificationListener`) answers *every* game toast by pulling the
screen to the character it names. Some toasts, though, are answered elsewhere — a whisper becomes a card,
a turn or a request is answered from the screen, its toast only claimed — and the auto-focus must not
also jump to them. So each of those features exposes a **pure `claims(Notification)`**, and the auto-focus is built
with the **OR** of them (combined in `MinobotApp`): both sides decide the same, from the same toast,
with no handshake and no race. A new feature that answers a toast silently adds its `claims` to that OR
and nothing else.

`NotificationListener.onNotification` is **`public`** where the handlers here are package-private: the
invitation relay in `group/` is tested directly against it (a toast must not steal the foreground from
the relay's keystrokes), and that test lives in another package.

## Turns and requests — heard from `FightTurns` and `Requests`, not from a toast

The turn and request features do **not** register with `NotificationManager`. A turn is reported to
`core.FightTurns` by `core.vision.ScreenWatcher` alone — the banner on the screen, read several times and
from several windows, which is why it goes through a hub that makes it one turn (see `core/CLAUDE.md`).
The game's turn toast (`C'est à "Bravo" de jouer`) is **heard by no one**: `TurnToasts` is nothing but a
pure static `claims`, kept with that wording, in the auto-focus's OR so the toast does not pull the
screen a second time. The **turn auto-focus** (`TurnFocuser`) takes the player to the character whose turn
it is with the same yielding `focusIfIdle`, and leaves the character already in front, a monster, or
another player's character alone. A turn's fighter is matched by **exact** name (`WindowManager.windowOf`).

Requests go the same way: the box on screen (`vision.RequestDialog`) is reported to `core.Requests`, and
`RequestToasts.claims` (the wording is `domain.Request.in`, shared with the box) keeps the invitation,
trade and duel toasts from the notification auto-focus. The **request auto-focus** (`RequestFocuser`) answers them, through the same
`Summoner` as the turn auto-focus — and it has a `claims` seam of its own, a
`BiPredicate<GameWindow, Request>`: it stands aside for the trades `ExchangeAccepter.claims` answers in
place. The invitation relay (`group/`) listens to `Requests` too. A duel only the request auto-focus
answers: it takes the player to the challenged character, and the answer is theirs.

## Auto-pass turns — the overlay's switch, a toggle key, and a banner

Plays a table of alts on its own. In a fight, whoever's turn it is gets reported to `FightTurns` (above),
and when the switch is on, `TurnPasser` turns that turn into a keypress:
it brings the character up and presses the game's end-turn key (`F1`, a constant — the key is the
game's, not the player's). A background character's turn ends without the player lifting a finger.

It passes **every** character's turn, the foreground one included: the switch is the player saying they
have stepped away, not that they are playing one of the seats — so there is no "except the one I am on".
That is why it is **off by default**, and why the switch is drawn to be unmistakable.

Like the invitation relay it focuses a window and then types into it, so it holds the foreground for the
length of that (`focus.takeOver()`): the turn auto-focus hears of the same turn, and without the
takeover its focus would land between the focus and the keypress and end a turn in the wrong window. A
`ReentrantLock` serializes one pass against the next for the same reason. It is a **combat** feature and
the group invitation is done **before** the fight — the two are not meant to share the screen, and do
not. The switch is read live at every turn; unlike the order and the
keybinds its **state** is **session-only** — not persisted, so a restart forgets it and finds it off,
which is the safe default for a feature that ends turns on its own.

**Two ways to flip it, and one to see it.** The overlay carries the switch, and — new — an
`AUTO_PASS_TURN` hotkey (default `shift+middle`) toggles it too. That hotkey is the odd one in the
`Feature` enum: its action *toggles the switch* rather than firing a one-shot, and a blank combination
only leaves it keyless (the switch still works) instead of disabling the feature. `MinobotApp` binds it
to `OverlayController.flipAutoPassTurn`, so a press and the panel's pill land on the same `settings.update`
and stay in step. The *key combination* is persisted like every other keybind; the on/off *state* is not.

`AutoPassBanner` is the **visible half**: while the switch is on it hangs an ember **pill** at the
**top-centre** of the foreground game — a live dot, `AUTO-PASS TURNS`, and *every character passes* — so a
feature that otherwise shows nothing but turns ending by themselves is unmistakably on. It answers **no
toast**: it watches the switch through `Settings.onChange` and, like the panel and the whisper stack,
**follows** the game window on a 30 ms virtual-thread loop.

**The banner cannot be dismissed, only obeyed.** Its one button is *Turn off*, and it switches the feature
off (`BannerActions.turnOff` → `settings.update`, the same `auto_pass_turn` the overlay's pill and the
hotkey flip); the banner then goes because the switch went, not the other way round. There is deliberately
**no cross that hides the sign and leaves the turns ending**: a player who has stepped away comes back to
a game that has been playing itself, and the only thing telling them so is this pill — a version they
could have waved away an hour earlier would be worse than no banner at all. (It *did* work that way once,
with a `dismissed` flag and a cross; the flag is gone, and so is the state where the feature ran unsigned.)

Its UI is cut on the same interface/impl seam as the others (`ui/BannerView`, `ui/BannerContent`,
`ui/BannerActions`, `ui/banner/SwingBanner`), so all the deciding is tested with no screen
(`AutoPassBannerTest`). `BannerContent` carries **two** strings — the heading names the feature in caps for
the glance, the message spells out the consequence for the player who stops — because neither sentence
does both jobs well.

## Auto-accept trades — no hotkey, the overlay's switch

Accepts a trade one of the player's own characters asks of another. When A opens an exchange with B,
it is **B** that the game asks — a box on B's screen, `Alpha te propose de faire un échange` — and the
question **names the asker**. It hears of it from `core.Requests`, so the box read look after look makes
one request and one ENTER. That name is the whole discriminator: the trade is *internal* when it is
the name of one of the player's own windows (the receiver excepted), and it accepts it for them; a
stranger's is left exactly as it was.

The accept presses the game's accept key (`ENTER`, a constant). Sending a keystroke needs the window to
hold the keyboard, so — for now — the accept is a **blink**: `takeOver()`, focus B, press, then focus
straight back to where the player was (read before the blink). `accept()` is the one method to change if
a truly background posted keystroke is ever shown to work against the game.

**This is where it "thinks differently" from the auto-focus.** The request auto-focus answers every
request, so on its own it would jump to B and undo the point of accepting in place. So `RequestFocuser`
takes `ExchangeAccepter::claims` and stands aside when it fires; `claims(receiver, request)` is a **pure
function** of the request, the switch and the windows, so both features decide the same without a
handshake and without a race. A **stranger's** trade is not claimed: the auto-focus takes the player to
the receiving window and does nothing else, which is exactly what a real trade offer deserves. The asker
is matched by their **whole name** (`WindowManager.windowOf`: `SuperAlpha` is not our `Alpha`). **On by default** — passing items between one's own accounts is the daily bread of
multi-boxing, and it only ever fires on a trade a player's own character asked for — and session-only
like the other switch (not persisted to `overlay.json`).

## Whisper toast — no hotkey

Turns a private message into a quiet card instead of a jump. A whisper — a game toast whose message reads
`de <sender> : <line>` — would otherwise pull the whole screen to the whispered character through the
notification auto-focus, which is too much for one line of chat. So `WhisperToaster` stands in
front of that toast: it recognises the whisper by the **shape of its message** (a constant pattern kept
by the feature, the French wording being the game's), and rather than move the player it shows a small
card at the **left edge of the game**, centred top to bottom — who was whispered (`GameWindow.nameIn`
the title), by whom, and what they said. The card lives five seconds (`TOAST_LIFETIME`), a **close cross**
takes it down early, and a **click** on it focuses the receiver so the player can answer (on a virtual
thread — `FocusManager` sleeps). Several whispers **stack**, newest at the bottom.

It is a feature that registers with `NotificationManager`, speaks of characters, and exposes a **pure
`claims(Notification)`** the auto-focus consults. Its one novelty is a **UI surface**, cut on the overlay's interface/impl seam so all the
deciding stays testable with no screen: `ui/ToastContent` (the immutable snapshot), `ui/ToastActions`
(`open`/`dismiss`), `ui/ToastView` (the narrow port), and `ui/SwingToastStack` (the only whisper class
that knows Swing — the **same anti-focus discipline** as the overlay: `JWindow`, always-on-top,
`setFocusableWindowState(false)`, nothing typed, clicks routed by hand; **do not add `WS_EX_NOACTIVATE`**).
Unlike the panel the window is sized to the **stack alone**, so it blocks only a narrow band, not the
whole game. Like the panel it **follows**: one virtual thread re-reads the foreground game window every
30 ms and keeps the stack pinned to it, or takes it away out of the game — and the same poll is where a
card **dies of old age**, so there is no timer thread per card. The stack is drawn at `overlay_scale`,
the panel's, so it reads at the same size on the same monitor. It is **always active**: a whisper always
toasts instead of focusing, so there is no `Config` field and no switch — it is a direct replacement, and
adds nothing to the player's settings.

**The card fades; the whisper does not.** Every whisper it raises is first written to `core.WhisperLog`,
and the card is raised **under the id the log minted** — so the overlay can list the same message long
after its ten seconds are up, and a click there is the same jump the card offered. The two lifetimes are
why the log is not a field of this class: a card is a thing on screen, a whisper is a thing that happened.
Ten seconds is the right life for a card in the corner of a fight and the wrong one for the line itself,
which the game gives back only by switching to that character and reading the chat.

## Notification auto-focus — no hotkey

The game raises a toast when a background character is attacked, messaged or invited; that toast pulls
the focus to them. It is a *smart* focus: if the player is typing at that moment, their keystrokes are
not stolen.

It **stands aside**, though, for a toast answered elsewhere: a `Predicate<Notification>` it is built with
tells it which toasts to leave alone — a whisper already a card at the edge, a turn or a request, which
the turn and request auto-focus answer from the screen. The predicate is the **OR** of the
whisper toaster's, the turn toasts' and the request toasts' `claims`, combined in `MinobotApp`; every
other toast — an attack — it still takes.
