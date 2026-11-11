# Minobot

A quality-of-life application for **Dofus Retro**, for players running several accounts at once. It
sits silently in the background, listens for hotkeys and Windows notifications, and acts on the game
windows.

## Build

```shell
./mvnw verify              # compile + tests
./mvnw -Pdist verify       # → target/dist/Minobot/Minobot.exe (portable, runtime included)
java -jar target/minobot.jar
```

The Maven wrapper downloads Maven itself; only a **JDK 25** is required. If `JAVA_HOME` does not
point at it, prefix the command: `JAVA_HOME=C:\path\to\jdk-25 ./mvnw verify`.

## Architecture

```
fr.minobot
├── Main            Entry point; single-instance lock on a ServerSocket (127.0.0.1:12345).
├── app/            MinobotApp (wires everything), Config, Settings, Feature, ConfigLoader, LoggerSetup.
├── win32/          WindowApi (the interface), User32 (the FFM implementation), Win32, Point, Capture
│                   (a window's pixels); TextReader (OCR) and WindowsOcr (Windows.Media.Ocr over FFM).
├── core/           The Windows mechanics: WindowManager, FocusManager, KeyboardMonitor,
│                   NotificationManager, FightTurns and Requests (each fight turn, each request made
│                   of a character — invitation, trade — heard once), FlashSuppressor,
│                   SystemTrayManager, Input / InputSimulator, and the two records the panel reads
│                   back: ActivityLog, WhisperLog.
│   ├── vision/     Reading the game off a capture: GameStage (where the 742x556 stage sits in a
│                   window), TurnBanner (the name on the banner that opens every fight turn),
│                   RequestDialog (the invitation or trade box), ScreenWatcher (captures one window
│                   every 250 ms, round the characters, and hands it to every ScreenReader).
│   └── domain/     GameWindow (a character's window, and their name), Character (the player's part of
│                   a character: name, class, sex), DofusClass, Sex, Notification (a toast),
│                   Request (an invitation or a trade asked of a character, and its wording),
│                   Activity (one thing Minobot did), Whisper (one message received).
├── ui/             OverlayView (the panel), OverlayContent, OverlayActions. Interfaces only.
└── feature/        The user-facing features, split by functional domain:
    ├── window/         multi-window click, cycler, taskbar reorder.
    ├── group/          the invitation relay.
    ├── overlay/        the control panel and OverlayController.
    └── notification/   the toast- and turn-driven features (auto-focus, auto-pass, auto-accept,
                        whisper).
```

**A handful of interfaces are the only doors to the outside world:** `win32.WindowApi` (the screen,
captures included), `win32.TextReader` (the OCR), `core.Input` (the keyboard and mouse), and the `ui`
views we draw — `OverlayView` (the panel), `ToastView` (the whisper stack), `BannerView` (the auto-pass
banner). Everything else is code that runs in a test, on any OS, with no game running — which is why
there are 309 tests.

**Keep it that way.** Do not call `user32.dll`, `combase.dll`, `java.awt.Robot` or Swing from anywhere
but their implementations: a feature that reaches past `WindowApi` becomes untestable.

**Reading the game off the screen** is measured, never guessed: `fr.minobot.tools.CaptureProbe` (a
developer's tool, not a feature) saves what Minobot sees of every game window to `captures/` and
re-reads a saved folder with `--read`. A position or colour in `core/vision` comes from such captures.

### A feature speaks of characters; only the core speaks of windows

A `long` is a handle, `PostMessage` is a message, `SW_HIDE` is a flag — and none of them are what the
player asked for. The player asked for *every character to click the same spot*, for *the next character*,
for *their characters back in order in the taskbar*. A feature that reads like the story the player would
tell is a feature whose bugs are visible: `GroupManager` says a leader invites, the invitee accepts, and
the relay stops — and the reason the relay must stop is right there to be seen.

So a feature loops over **characters** (`GameWindow`, which carries a `name()`), and it keeps whatever
Windows demands — the retry on a refused `PostMessage`, the `lParam`, the `SW_RESTORE` — in one named
leaf at the bottom of the file. **The technical detail is not hidden, it is placed**: it stops being the
first thing read, and the constraint that justifies it is the comment above it.

### Where the rest of this document lives

This file stays small on purpose — it is loaded into context **every session**. The detail is placed
next to the code it governs, in a `CLAUDE.md` per package that loads only when you work in that subtree:

- **`src/main/java/fr/minobot/app/CLAUDE.md`** — `Config` (what a player may change), the `Feature` enum,
  and the two-tier config on disk (`Settings`, `OverlayState`, `config.json` vs `overlay.json`).
- **`src/main/java/fr/minobot/core/CLAUDE.md`** — concurrency (the polling thread, the swapped hotkey
  table, virtual-thread callbacks) and **the foreground as a single resource** (`FocusManager`). Read it
  before adding any feature: they all compete for the foreground.
- **`src/main/java/fr/minobot/feature/CLAUDE.md`** — a slim index; each feature's behaviour lives in
  its subpackage's own `CLAUDE.md` (`window/`, `group/`, `overlay/`, `notification/`), loaded only when
  you work there.
- **`src/main/java/fr/minobot/ui/CLAUDE.md`** — the interface/impl seam for the screen and the
  anti-focus discipline the two Swing classes (`SwingOverlay`, `SwingToastStack`) must obey.

## Conventions

- **Code and comments in English.**
- A comment states a constraint the code cannot show — a Windows quirk, a race, a reason. Never what
  the next line does.
- Windows handles are plain `long` (`Win32.NULL_HANDLE` for none), which is what an `HWND` is on the
  Win64 ABI.
- A native call that *cannot be made* (a wrong FFM signature) throws `Win32Exception`. A call that
  *fails* (a dead handle, a refused focus) returns `false` / `Optional.empty()` — that is normal, and
  the callers tolerate it.
- Never pack a mouse `lParam` by hand: call `Win32.makeLParam`. It is the likeliest silent bug of the
  whole codebase, and it is locked down by `Win32Test`.
- Tests drive `FakeWindowApi` (an in-memory desktop) and `FakeInput`. If a change is hard to test,
  that is a signal about the change, not about the tests.
