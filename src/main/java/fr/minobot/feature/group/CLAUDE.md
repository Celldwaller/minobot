# The party

Forming the group. One feature for now: the invitation relay. It **speaks of characters**
(`GameWindow`) and keeps whatever Windows demands in one named leaf at the bottom — see the root
`CLAUDE.md`. It waits on the same invitations the request auto-focus in `notification/` hears of, and it
competes with it for the foreground; read `core/CLAUDE.md` (*The foreground is a single resource*) first.

## Group invitation — `F8`

A relay: the first character invites the second, who accepts and invites the third, and so on. Each
step waits for the invited character to be **asked** — the box on their screen, through `core.Requests` — rather than guessing a delay. Before each `/invite`
it calls `Requests.expect(invitee)`, which points the screen watcher at that one window until the box is
seen: left to its round, the watcher reached it a whole round late, two seconds a step with eight
characters. Nothing within five seconds and the relay **stops
there**: the ENTER that accepts an
invitation, pressed in a window that has none on screen, opens the chat instead — and the next
`/invite` is then typed into the game rather than into the chat. The relay owns the foreground from
end to end (`focus.takeOver()`); the same invitations feed the request auto-focus, which must not answer them
here.

**This is why the relay is tested against the auto-focus.** `GroupManagerTest` reports each invitation
through `Features.requests()`, which tells both `GroupManager` and the `notification/RequestFocuser`, and
hands an attack toast to the `NotificationListener` mid-command: it proves the relay keeps the
foreground while it types. That cross-feature test is the reason `NotificationListener.onNotification`
is `public` where the other toast handlers are package-private: the relay lives in another package and
must drive it directly.
