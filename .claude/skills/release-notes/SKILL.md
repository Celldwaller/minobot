---
name: release-notes
description: Write the GitHub release description for a Minobot tag. Use when the user asks to prepare a release, a tag or its notes ("prépare la release 1.9.0", "le descriptif du tag").
---

# Release notes

The user pastes the output straight into GitHub: hand back **one fenced `markdown` block** and nothing to
adapt.

## Gather

1. The tag and the one before it: `git fetch --tags -q; git tag --sort=-v:refname`. If the tag the user
   names does not exist yet, use `HEAD` as the end of the range and say so.
2. The changes: `git log --oneline <previous>..<tag>`, then read the diffs that the subjects leave unclear.
3. The version in `pom.xml`, in two places: the project's `<version>` and `<dist.version>` (the one
   jpackage stamps on `Minobot.exe`, numeric only). Both must be the tag, `1.9.1` — no `-SNAPSHOT`. If
   either differs, set both, run `./mvnw verify`, and commit `chore: set the version to <tag>` before the
   tag is created; say so above the notes, and that the commit must be pushed before tagging.
4. The style of the last releases. `gh` is not installed; the repository is public:
   `Invoke-RestMethod "https://api.github.com/repos/Celldwaller/minobot/releases?per_page=4"` and read each
   `body`.

## Write

- In **English**, whatever language the conversation is in.
- Sections as `## Features`, `## Fixes`, `## Chores`, `## Docs` — only the ones with something in them, in
  that order.
- One bullet per change a **player** would notice, said from their side (what changes for them), not the
  commit subject. A big feature gets a bold name: `- **Name**: what it does`.
- Code, file names, paths, hotkeys and durations in backticks.
- Commits a player never sees (refactors, tests, build) go under Chores in a few words, or are dropped.
- Last line, after a blank line:
  `**Full Changelog**: https://github.com/Celldwaller/minobot/compare/<previous>...<tag>`

## Example

```markdown
## Features
- Settings (`config.json`, `overlay.json`, `spell-layouts.json`) and logs are now stored in `%APPDATA%\Minobot` instead of next to `Minobot.exe`, so they survive replacing the folder with a new version
- On first launch, the files left next to `Minobot.exe` by a previous version are moved there automatically

## Docs
- Noted the turn illustration setting that Auto-pass turns needs

**Full Changelog**: https://github.com/Celldwaller/minobot/compare/1.8.3...1.9.0
```
