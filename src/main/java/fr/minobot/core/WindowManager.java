package fr.minobot.core;

import fr.minobot.app.Settings;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Detects and tracks the game windows — the counterpart of {@code window_manager.py}.
 *
 * <p>Everything it knows about Windows comes through {@link WindowApi}, so the ordering, the
 * monitor filtering and the character-name extraction are all testable on any OS.
 */
public final class WindowManager {

    private static final Logger log = LoggerFactory.getLogger(WindowManager.class);

    /** What a game window's title carries: {@code "Bravo - Dofus Retro v1.48.18"}. */
    private static final List<String> GAME_KEYWORDS = List.of("Dofus");

    /**
     * The executable every game window runs, whatever its title says — the discriminant a title cannot
     * give. Matched on the leaf of the process's image path, case-insensitively.
     */
    private static final String GAME_EXECUTABLE = "Dofus Retro.exe";

    /** Windows are opened and closed by hand, so the list may lag by this much without harm. */
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);

    /** How far past the configured order an unlisted window is pushed. */
    private static final int UNRANKED_OFFSET = 1000;

    private final WindowApi api;
    private final Settings settings;
    private final ActivityLog activity;

    /**
     * The last refresh, or {@code null} until the first one.
     *
     * <p>The window list and the time it was taken are one indivisible fact: held apart, a reader
     * could pair a fresh list with an old timestamp and refresh for nothing — or worse, the other way
     * round.
     */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public WindowManager(WindowApi api, Settings settings) {
        this(api, settings, new ActivityLog());
    }

    /** @param activity where a character leaving the screen is noted, for the panel to show */
    public WindowManager(WindowApi api, Settings settings, ActivityLog activity) {
        this.api = api;
        this.settings = settings;
        this.activity = activity;
    }

    /** Re-enumerates the desktop and keeps the windows whose title carries a game keyword. */
    public synchronized void refresh() {
        final var fresh = scan();
        noteWhoLeft(snapshot.getAndSet(new Snapshot(fresh, System.nanoTime())), fresh);

        log.debug("Detected {} game window(s).", fresh.size());
        for (final var window : fresh) {
            log.debug("  -> Found: '{}' (HWND: {})", window.title(), window.hwnd());
        }
    }

    /**
     * The game windows on screen right now, in enumeration order — without touching the tracked list.
     *
     * <p>For a watcher that sweeps far more often than the list needs refreshing: a refresh also decides
     * who left, and a window hidden for an instant by the taskbar reorder has not left.
     */
    public List<GameWindow> scan() {
        final var found = new ArrayList<GameWindow>();
        for (final var hwnd : api.topLevelWindows()) {
            if (!api.isWindowVisible(hwnd)) {
                continue;
            }
            final var title = api.windowText(hwnd);
            if (isGameWindow(hwnd, title)) {
                found.add(new GameWindow(hwnd, title));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Notes the characters that were on screen at the last sweep and are not at this one.
     *
     * <p>A character who closed their window is the one thing here the player did not do themselves, and
     * it silently changes what every other feature will act on — the click no longer reaches them, the
     * cycler steps over them. Their place in the order is kept, which is the reassurance the note carries.
     *
     * <p>The very first sweep tells nobody anything: every window is new to it, so nobody has left.
     */
    private void noteWhoLeft(Snapshot previous, List<GameWindow> fresh) {
        if (previous == null) {
            return;
        }

        final var stillHere = fresh.stream().map(GameWindow::name).collect(Collectors.toSet());
        previous.windows().stream()
                .filter(GameWindow::hasCharacterName)
                .map(GameWindow::name)
                .filter(name -> !stillHere.contains(name))
                .forEach(name -> activity.record(Text.of("activity.window_closed", name),
                        Text.of("activity.kept_in_order")));
    }

    /** Whether a title is the game's — a window's, or a toast's, which carry the same keywords. */
    public static boolean isGameTitle(String title) {
        return GAME_KEYWORDS.stream().anyMatch(title::contains);
    }

    /**
     * Whether a window is the game's — the process is the truth, the title only a fallback.
     *
     * <p>Every game window runs {@code Dofus Retro.exe}, and only the game does: this is what a title
     * cannot tell us. A window at the login screen carries no character name yet
     * ({@code "Dofus Retro v1.48.18"}), and a browser tab or a wiki page merely <em>mentions</em> the
     * game — the two are indistinguishable by title, but never by process. Left to the title alone, the
     * overlay would not open on a not-yet-logged-in window, and might open over the browser.
     *
     * <p>The title check remains, second, for the case the process cannot be read at all (a refused
     * open, a handle that died between enumeration and the query): a named window still gets in by the
     * shape of its title. The common case — a named game window — matches the title first and never
     * pays for the process query.
     */
    private boolean isGameWindow(long hwnd, String title) {
        return isGameWindowTitle(title) || runsTheGame(hwnd);
    }

    /** Whether the window's process is {@code Dofus Retro.exe} — the leaf of its full image path. */
    private boolean runsTheGame(long hwnd) {
        final var path = api.executablePath(hwnd);
        final var leaf = path.substring(path.lastIndexOf('\\') + 1);
        return leaf.equalsIgnoreCase(GAME_EXECUTABLE);
    }

    /**
     * Whether a title has a game <em>window</em>'s shape — the fallback discriminant when the process is
     * unreadable, and stricter than {@link #isGameTitle}.
     *
     * <p>The game writes {@code "<character> - Dofus Retro v1.48.18"}: the keyword opens the suffix, right
     * after the name. The keyword must <em>begin</em> what the game appended, not merely appear somewhere
     * in the title — a browser tab that mentions the game buries it mid-sentence. This does not catch a
     * login screen with no name, which is why {@link #isGameWindow} leans on the process first.
     *
     * <p>Kept separate from {@link #isGameTitle}, which stays as it is for toasts: a toast is a
     * notification, never a window that could hold the foreground, and its title drops the version
     * ({@code "<character> - Dofus Retro"}), so there is no window shape to demand of it.
     */
    public static boolean isGameWindowTitle(String title) {
        final var suffix = GameWindow.suffixIn(title);
        return GAME_KEYWORDS.stream().anyMatch(suffix::startsWith);
    }

    /** Refreshes only if the list has gone stale. */
    public void ensureFresh() {
        final var last = snapshot.get();
        if (last == null) {
            refresh();
            return;
        }
        if (System.nanoTime() - last.takenNanos() > REFRESH_INTERVAL.toNanos()) {
            log.info("Window list is stale, refreshing...");
            refresh();
        }
    }

    /** The windows detected at the last refresh, in enumeration order. */
    public List<GameWindow> windows() {
        final var last = snapshot.get();
        return last == null ? List.of() : last.windows();
    }

    /**
     * The window in the foreground <em>if it is the game's</em> — decided live from the process, not
     * looked up in the tracked list.
     *
     * <p>The overlay belongs to whatever game window the player is actually on, and that is not always
     * a window the 30-second sweep has on file: a character just launched sits at the login screen for
     * a while, and a login window is exactly the one the sweep may not have caught yet — or may not
     * surface at all. Asking the foreground window's own process sidesteps the sweep entirely: if the
     * player is looking at {@code Dofus Retro.exe}, that is where the panel goes.
     */
    public Optional<GameWindow> foregroundGameWindow() {
        final var hwnd = api.foregroundWindow();
        if (hwnd == Win32.NULL_HANDLE) {
            return Optional.empty();
        }
        final var title = api.windowText(hwnd);
        return isGameWindow(hwnd, title) ? Optional.of(new GameWindow(hwnd, title)) : Optional.empty();
    }

    /**
     * The character name a title carries — a window's, or a toast's, which the game writes the same way.
     *
     * <p>{@code "Bravo - Dofus Retro v1.48.18"} yields {@code "Bravo"}.
     */
    public String extractCharacterName(String title) {
        return GameWindow.nameIn(title);
    }

    /** Finds a character's window: first by exact name, then by a substring of the whole title. */
    public Optional<GameWindow> findWindow(String characterName) {
        ensureFresh();
        final var wanted = characterName.toLowerCase(Locale.ROOT);
        final var candidates = windows();

        for (final var window : candidates) {
            if (window.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                log.debug("Found exact match for '{}': '{}'", characterName, window.title());
                return Optional.of(window);
            }
        }

        for (final var window : candidates) {
            if (window.title().toLowerCase(Locale.ROOT).contains(wanted)) {
                log.debug("Found partial match for '{}': '{}'", characterName, window.title());
                return Optional.of(window);
            }
        }

        log.warn("Could not find any window for character '{}'.", characterName);
        return Optional.empty();
    }

    /**
     * A character's window by their exact name, and nothing else.
     *
     * <p>For a name read off a fight, which may be anyone's: a monster's or another player's character's
     * is no window of ours, and the substring match of {@link #findWindow} would hand the monster
     * {@code Tofu} the window of the character {@code Tofulette}.
     */
    public Optional<GameWindow> windowOf(String characterName) {
        ensureFresh();
        return windows().stream()
                .filter(GameWindow::hasCharacterName)
                .filter(window -> window.name().equalsIgnoreCase(characterName))
                .findFirst();
    }

    public List<GameWindow> orderedWindows() {
        return orderedWindows(false);
    }

    /**
     * The game windows in the player's character order, unlisted ones last.
     *
     * <p>Sorted alphabetically first, then stably by rank: windows sharing a rank — and all the
     * unlisted ones do — stay in alphabetical order instead of in the arbitrary order Windows
     * enumerated them in. Reversing flips the ranks only, so the alphabetical tie-break holds.
     */
    public List<GameWindow> orderedWindows(boolean reverse) {
        ensureFresh();

        final var ordered = new ArrayList<>(windows());
        ordered.sort(Comparator.comparing(GameWindow::title));

        final var byConfiguredRank = Comparator.comparingInt(this::rank);
        ordered.sort(reverse ? byConfiguredRank.reversed() : byConfiguredRank);

        return List.copyOf(ordered);
    }

    private int rank(GameWindow window) {
        // Read at every call, not held: the overlay's drag & drop must be in effect on the next cycle.
        final var cycleOrder = settings.get().characterOrder();
        final var title = window.title().toLowerCase(Locale.ROOT);

        for (var i = 0; i < cycleOrder.size(); i++) {
            if (title.contains(cycleOrder.get(i).toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return cycleOrder.size() + UNRANKED_OFFSET;
    }

    /** The ordered windows that are not minimized. */
    public List<GameWindow> activeOrderedWindows() {
        return orderedWindows().stream()
                .filter(window -> !api.isIconic(window.hwnd()))
                .toList();
    }

    /** Two windows sit on the same screen exactly when this returns the same handle for both. */
    public long monitorOf(long hwnd) {
        return api.monitorFromWindow(hwnd);
    }

    /** The active windows sharing a monitor with whatever currently holds the focus. */
    public List<GameWindow> windowsOnCurrentMonitor() {
        final var foreground = api.foregroundWindow();
        if (foreground == Win32.NULL_HANDLE) {
            return activeOrderedWindows();
        }

        final var currentMonitor = monitorOf(foreground);
        final var sameMonitor = activeOrderedWindows().stream()
                .filter(window -> monitorOf(window.hwnd()) == currentMonitor)
                .toList();

        if (sameMonitor.isEmpty()) {
            log.debug("No game windows found on the current monitor.");
        }
        return sameMonitor;
    }

    /** One refresh: the windows it found, and when. {@code windows} is always an immutable list. */
    private record Snapshot(List<GameWindow> windows, long takenNanos) {
    }
}
