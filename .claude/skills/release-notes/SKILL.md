---
name: release-notes
description: Prepare a Minobot release — the version in the pom, the tag created and pushed to the remote (which publishes it), and the GitHub release description, written and set on the release once its workflow is done. Use when the user asks to prepare a release, a tag or its notes ("prépare la release 1.9.0", "le descriptif du tag").
---

# Release notes

The notes are set on the GitHub release with `gh` (see *Publish the notes*), and shown in the conversation
as **one fenced `markdown` block**, ready as they are.

`gh` is installed and logged in (account `Celldwaller`, `repo` scope). A shell opened before it was
installed may not have it on its `PATH`: call it as `"/c/Program Files/GitHub CLI/gh.exe"` then.

## Gather

1. The tag and the one before it: `git fetch --tags -q; git tag --sort=-v:refname`. If the tag the user
   names does not exist yet, use `HEAD` as the end of the range and say so.
2. The changes: `git log --oneline <previous>..<tag>`, then read the diffs that the subjects leave unclear.
3. The version in `pom.xml`, in two places: the project's `<version>` and `<dist.version>` (the one
   jpackage stamps on `Minobot.exe`, numeric only). Both must be the tag, `1.9.1` — no `-SNAPSHOT`. If
   either differs, set both, run `./mvnw verify`, and commit `chore: set the version to <tag>` before the
   tag is created (see *Tag and push*).
4. The style of the last releases: `gh release list --limit 4`, then `gh release view <tag>` for each.

## Tag and push

The release only exists once the tag is **on the remote**: pushing a `x.y.z` tag is what triggers
`.github/workflows/release.yml`, which builds `Minobot-<tag>.zip` and creates the GitHub release, with
generated notes that *Publish the notes* replaces. A tag left local publishes nothing.

1. `main` is pushed, the version commit of step 3 included: `git status -sb` shows nothing ahead of
   `origin/main`. Otherwise push it first: the tag must name a commit the remote has.
2. Create the tag on that commit, lightweight like the previous ones (`git tag <tag>`), unless it exists
   already.
3. Push the tag alone: `git push origin <tag>` — never `--tags`, which would push every local tag.
4. Ask the user before steps 1 and 3: a pushed tag publishes a release to players.

## Publish the notes

The release is the workflow's to create: its notes can only be set once it has finished, a few minutes
after the tag is pushed. Wait for it rather than handing the notes back to paste.

1. Find the run the tag started: `gh run list --workflow release.yml --limit 1 --json databaseId,headBranch,status`
   — `headBranch` is the tag. It shows up a few seconds after the push: list again until it does.
2. Wait for it: `gh run watch <databaseId> --exit-status`, in the background, and say meanwhile that it is
   building. A failed run published nothing: show its log (`gh run view <databaseId> --log-failed`) and stop.
3. Write the notes (see *Write*) to a file in the scratchpad, then
   `gh release edit <tag> --notes-file <file>`. Check with `gh release view <tag>`, and give the release's
   link: `https://github.com/Celldwaller/minobot/releases/tag/<tag>`.

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
