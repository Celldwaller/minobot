package fr.minobot.feature.window;

import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FlashSuppressor;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Point;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Plays the same click on every character at once — the counterpart of {@code multi_window_clicker.py}.
 *
 * <p>The player aims at a spot with their own character, presses the hotkey, and every other character
 * clicks the same spot of the game: they all walk to the same place, all talk to the same NPC. The
 * player never leaves the character they are playing — that is the whole point, and everything below
 * serves it.
 *
 * <p>Which is why the click is <em>posted</em> rather than simulated: it is dropped straight into each
 * window's queue, so the other characters act without their windows ever taking the screen. And why the
 * spot travels as a <em>client</em> position, not a screen one: the same client coordinates land on the
 * same in-game spot in every window, wherever they sit on the desktop. <em>At once</em> is literal:
 * every character is posted on its own virtual thread, so the last window clicks a breath after the
 * first, not a character's worth of delay later.
 *
 * <p><strong>A clicked character goes deaf, and there is no cure but the reset hotkey.</strong> The
 * game only raises its Windows toast for a character nobody is watching, and a posted click is — seen
 * from inside the game — a click like any other: it concludes the player is there and stops notifying.
 * The notification auto-focus, which lives on those toasts, dies with them. This was chased down to the
 * end: a single posted click deafens the window it hits and no other; the toast is never raised at all
 * (the Windows notification database stays empty, so there is nothing to miss); and the game cannot be
 * talked out of it. {@code WM_ACTIVATE}, {@code WM_NCACTIVATE}, {@code WM_KILLFOCUS},
 * {@code WM_ACTIVATEAPP}, a full activation/deactivation cycle, even a real {@code SetFocus} through
 * {@code AttachThreadInput} — all measured, all ignored. Only an activation that genuinely brings the
 * window up on screen re-arms it, which is precisely what {@link #resetCharacters()} does and why it
 * exists. <strong>Do not add a "fix" here that posts messages at the game: that ground is burnt.</strong>
 */
public final class MultiWindowClicker {

    private static final Logger log = LoggerFactory.getLogger(MultiWindowClicker.class);

    /** A posted click can be refused by a window that is busy; one retry is enough in practice. */
    private static final int CLICK_ATTEMPTS = 2;
    private static final int RETRY_MILLIS = 5;

    /** How far up the parent chain the game window under the cursor is looked for. */
    private static final int MAX_PARENT_DEPTH = 10;

    /** Time given to a character to settle on screen before the next one is brought up. */
    private static final int RESET_SETTLE_MILLIS = 5;

    private final WindowApi api;
    private final WindowManager windows;
    private final FocusManager focus;
    private final FlashSuppressor flash;
    private final Settings settings;
    private final ActivityLog activity;

    public MultiWindowClicker(WindowApi api, WindowManager windows, FocusManager focus,
                              FlashSuppressor flash, Settings settings, ActivityLog activity) {
        this.api = api;
        this.windows = windows;
        this.focus = focus;
        this.flash = flash;
        this.settings = settings;
        this.activity = activity;
    }

    /**
     * Clicks the spot the player just aimed at, in every character's game.
     *
     * <p>The player's own character is clicked too: the hotkey is a side button, not the click itself,
     * so nothing has landed there yet.
     *
     * @param cursor where the cursor was when the hotkey went down
     */
    public void clickEveryCharacter(Point cursor) {
        // The desktop as it is now, not as the thirty-second sweep last left it. A character who
        // logged in a moment ago is exactly the one the sweep has not caught yet — and, having no
        // place in the configured order, the one that sorts last. Left to the sweep, the click would
        // skip them without a word, and the player would read it as the last window being unreachable.
        windows.refresh();

        final var characters = windows.orderedWindows();
        if (characters.isEmpty()) {
            log.warn("No character to click.");
            return;
        }

        final var spot = spotAimedAt(cursor, characters);
        if (spot.isEmpty()) {
            return;
        }

        // Every character is clicked at once, each on its own virtual thread — never one after another.
        // A window is a process of its own, and a click posted to its queue is independent of the next:
        // there is no line for them to wait in, and making them wait is precisely the decalage the
        // player feels between the first click and the last. The old sequential loop paced itself with a
        // breathing delay between characters; per-window threads have no "next character" to pace against,
        // so that delay is gone with it. (If a click is ever dropped under the real game, shift+x1 remains
        // the cure — the same one it always was for a deafened window.)
        final var clicked = new CopyOnWriteArrayList<Long>();
        final var clicks = clickable(characters).stream()
                .map(character -> Thread.ofVirtual()
                        .name("multi-click-" + character.name())
                        .start(() -> {
                            if (click(character, spot.get())) {
                                clicked.add(character.hwnd());
                            }
                        }))
                .toList();
        if (!joinAll(clicks)) {
            return;
        }

        activity.record(Text.of("activity.multiclick"), Text.of("activity.characters", clicked.size()));

        // Each of these characters is about to ask Windows for the screen, be refused, and turn orange
        // in the taskbar. The suppressor clears them once that has happened — on its own thread, never
        // on this one.
        flash.watch(clicked);
    }

    /**
     * Waits for every click to be posted.
     *
     * <p>The wait is <strong>unbounded on purpose</strong>. A timeout here does not cancel anything —
     * the click still lands — it only returns before the slow ones have added themselves to
     * {@code clicked}, and a window missing from that list is a window {@link FlashSuppressor} never
     * sweeps: it stays orange for good, and the panel counts one character short. The characters that
     * take the retry path below always finish after a short deadline, so a short deadline drops exactly
     * the same windows every time.
     *
     * <p>It costs no more than the slowest click, not the sum of them: the threads run at once, and
     * joining them one after another simply waits for whichever ends last. That click is itself bounded
     * by {@link #CLICK_ATTEMPTS} attempts spaced by {@link #RETRY_MILLIS}.
     *
     * @return whether every click finished; {@code false} means this thread was interrupted waiting
     */
    private static boolean joinAll(List<Thread> clicks) {
        for (final var click : clicks) {
            try {
                click.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Brings every character up on screen in turn, then hands the screen back to the leader.
     *
     * <p>The cure for what the click above costs: a clicked character leaves an orange taskbar button
     * behind and stops raising its toasts, and nothing undoes either but a genuine visit. Visually
     * disruptive by nature — which is why the player asks for it with a hotkey, and it never happens
     * on its own.
     */
    public void resetCharacters() {
        final var characters = windows.orderedWindows();
        if (characters.isEmpty()) {
            log.warn("No character to reset.");
            return;
        }

        log.info("Resetting {} character(s)...", characters.size());

        // The sequence walks the whole desktop: a toast focusing a character halfway through would
        // leave the player somewhere other than where they were playing.
        try (final var _ = focus.takeOver()) {
            // Visited back to front, so the leader is reached last and is already on top by the time the
            // explicit restore below runs. This reverses the list itself rather than asking for the
            // reversed order: that sorts by rank, and characters absent from the character order all share
            // one rank — so on a default config it would hand back the same order, and the "leader" would
            // be whichever character sorted last alphabetically.
            for (final var character : characters.reversed()) {
                if (!bringUp(character)) {
                    return;
                }
            }

            final var leader = characters.getFirst();
            log.debug("Returning the focus to '{}'.", leader.name());
            focus.focus(leader.hwnd());
        }

        log.info("Every character has been reset.");
        activity.record(Text.of("activity.characters_reset"), Text.of("activity.characters", characters.size()));
    }

    /** @return whether the sequence may go on; {@code false} means the thread was interrupted */
    private boolean bringUp(GameWindow character) {
        if (api.isIconic(character.hwnd())) {
            log.debug("Skipping '{}': the character is minimized.", character.name());
            return true;
        }

        log.debug("Bringing '{}' up on screen.", character.name());
        focus.focus(character.hwnd());

        return true;
    }

    /**
     * The spot the player aimed at, as a position in the client area of the character they aimed with.
     *
     * <p>Empty when the click landed outside the game — on the desktop, on a browser: there is no
     * in-game spot to replay, and translating the screen point per character would fire each of them at
     * whatever happens to sit there in their own view.
     */
    private Optional<Point> spotAimedAt(Point cursor, List<GameWindow> characters) {
        final var handles = characters.stream().map(GameWindow::hwnd).collect(Collectors.toSet());

        // The point may land on a child window (the game's render surface): walk up to the top-level.
        var window = api.windowFromPoint(cursor);
        for (var depth = 0; window != Win32.NULL_HANDLE && depth < MAX_PARENT_DEPTH; depth++) {
            if (handles.contains(window)) {
                return api.screenToClient(window, cursor);
            }
            window = api.parentWindow(window);
        }

        log.debug("The click did not land on a game window; nothing to replay.");
        return Optional.empty();
    }

    /** The characters the click actually reaches: a minimized one has no game to click in. */
    private List<GameWindow> clickable(List<GameWindow> characters) {
        // Read at every click, not held: the player can exclude a character from the overlay at any time.
        final var excluded = settings.get().multiclickExclude().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        return characters.stream()
                .filter(character -> !isExcluded(character, excluded))
                .filter(character -> !api.isIconic(character.hwnd()))
                .toList();
    }

    private boolean isExcluded(GameWindow character, List<String> excluded) {
        final var title = character.title().toLowerCase(Locale.ROOT);
        return excluded.stream().anyMatch(title::contains);
    }

    /** @return whether the click was posted, and the character is therefore about to turn orange */
    private boolean click(GameWindow character, Point spot) {
        if (!api.isWindow(character.hwnd())) {
            log.debug("Skipping '{}': its window is gone.", character.name());
            return false;
        }

        for (var attempt = 0; attempt < CLICK_ATTEMPTS; attempt++) {
            if (post(character.hwnd(), spot)) {
                return true;
            }
            if (!sleep(RETRY_MILLIS)) {
                return false;
            }
        }

        log.warn("Could not click '{}' after {} attempts.", character.name(), CLICK_ATTEMPTS);
        return false;
    }

    /** Posts the down/up pair of a left click. */
    private boolean post(long hwnd, Point spot) {
        final var lparam = Win32.makeLParam(spot.x(), spot.y());

        return api.postMessage(hwnd, Win32.WM_LBUTTONDOWN, Win32.MK_LBUTTON, lparam)
                && api.postMessage(hwnd, Win32.WM_LBUTTONUP, 0, lparam);
    }

    /**
     * @return whether the sleep completed; {@code false} means the thread was interrupted
     */
    private static boolean sleep(int millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
