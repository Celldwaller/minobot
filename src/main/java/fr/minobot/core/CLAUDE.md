# Core

## The foreground is a single resource

Several threads want the one screen; `FocusManager` arbitrates:

- `focus(hwnd)` — deliberate (a hotkey). Waits its turn and **returns whether the window really holds
  the foreground**: a caller that then types must check it.
- `focusIfIdle(hwnd)` — opportunistic (a toast, a turn). Stands aside rather than queue.
- A feature that focuses **and types** holds `focus.takeOver()` for its whole sequence, or an auto-focus
  lands between two keystrokes.

## Concurrency

- `KeyboardMonitor` polls on a platform thread; its hotkey table is replaced, never mutated.
- Every hotkey and notification callback runs on its own virtual thread, so anything may sleep.
- `vision.ScreenWatcher` runs on a platform thread; turns and requests are deduplicated by `FightTurns`
  and `Requests` — listen there, not to the toasts.
