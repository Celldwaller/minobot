# Minobot

A background quality-of-life tool for **Dofus Retro** multi-boxers: it listens for hotkeys, Windows
toasts and what the game shows on screen (OCR), and acts on the game windows.

## Build

```shell
./mvnw verify              # compile + tests
./mvnw -Pdist verify       # → target/dist/Minobot/Minobot.exe (portable, runtime included)
```

Requires a **JDK 25** (`JAVA_HOME=C:\path\to\jdk-25 ./mvnw verify` if needed).

## Rules

- **The outside world goes through a few interfaces only:** `win32.WindowApi`, `win32.TextReader`,
  `core.Input`, and the `ui` views (`OverlayView`, `ToastView`, `BannerView`). Never call `user32.dll`,
  `combase.dll`, `java.awt.Robot` or Swing from anywhere but their implementations — everything else must
  stay testable with `FakeWindowApi` / `FakeInput`, on any OS, with no game running.
- **A feature speaks of characters (`GameWindow`), not of handles.** Windows details (`PostMessage`,
  `lParam`, retries) go in one named leaf at the bottom of the file, with the constraint as its comment.
- **The foreground is a single resource** — read `core/CLAUDE.md` before adding a feature.
- Screen positions and colours in `core/vision` are measured with `fr.minobot.tools.CaptureProbe`, never
  guessed.

## Conventions

- Code and comments in English. A comment states a constraint the code cannot show, never what the next
  line does.
- Windows handles are plain `long` (`Win32.NULL_HANDLE` for none).
- A native call that *cannot be made* throws `Win32Exception`; a call that *fails* returns `false` /
  `Optional.empty()`, and callers tolerate it.
- Never pack a mouse `lParam` by hand: use `Win32.makeLParam`.
- A `Config` field is a claim that a player has a reason to change it; timings and game constants live
  next to the code that reads them. Never hoist a config value into a field: read `Settings` at each use.
