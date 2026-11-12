# UI

`ui/` holds interfaces and data only; Swing lives in `components/`, `overlay/`, `toast/`, `banner/` and `marks/`,
and nowhere else. Every Swing surface obeys:

- **Never take the foreground**: `setFocusableWindowState(false)`, which is enough — do not add
  `WS_EX_NOACTIVATE`. So nothing is typed and clicks are routed by hand (no `JPopupMenu`, no Swing DnD).
- **Every size goes through `Scale`** (`px()`, `font()`); sum widths after scaling, not before.
- Colours come from `Theme`; one ember accent, rationed. Fonts are named by weight (`Fonts.MEDIUM`), never
  `Font.BOLD`, and only glyphs the shipped faces have (`→` is not one).
- Inside a cell renderer, narrow with `clip(...)`, never `setClip(...)`.
