package fr.minobot.feature.window;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maximizes every game window the launcher opens, once, as soon as it shows up.
 *
 * <p>The game opens its windows at a size of its own, and the player wants them full screen. Only a
 * window <em>new</em> to Minobot is touched, and only the first time it is seen: the windows already open
 * when Minobot starts are arranged as the player left them, and a window the player restores afterwards
 * stays restored.
 *
 * <p>A window is known by its handle, not its character: it opens at the login screen, nameless, and
 * keeps its handle once a character logs in — logging in is not a new window to maximize again.
 */
public final class WindowMaximizer {

    private static final Logger log = LoggerFactory.getLogger(WindowMaximizer.class);

    /** How long a freshly launched window may stay at its first size — the sweep costs well under a millisecond. */
    static final Duration SWEEP = Duration.ofSeconds(1);

    private final WindowApi api;
    private final WindowManager windows;
    private final ActivityLog activity;

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /**
     * Every game window seen so far, or {@code null} before the first sweep, which only takes stock.
     * Only the sweeping thread touches it.
     */
    private Set<Long> known;

    public WindowMaximizer(WindowApi api, WindowManager windows, ActivityLog activity) {
        this.api = api;
        this.windows = windows;
        this.activity = activity;
    }

    /** Starts sweeping on a platform thread: every sweep is a run of native calls. */
    public void start() {
        final var sweeper = Thread.ofPlatform().daemon().name("window-maximizer").unstarted(this::sweepForever);
        if (thread.compareAndSet(null, sweeper)) {
            sweeper.start();
        }
    }

    public void stop() {
        final var current = thread.getAndSet(null);
        if (current != null) {
            current.interrupt();
        }
    }

    private void sweepForever() {
        try {
            while (thread.get() == Thread.currentThread()) {
                try {
                    sweep();
                } catch (RuntimeException e) {
                    log.warn("Window sweep failed; trying again at the next one.", e);
                }
                Thread.sleep(SWEEP);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** Maximizes the game windows that appeared since the last sweep. */
    void sweep() {
        final var onScreen = windows.scan();

        if (known == null) {
            known = new HashSet<>();
            onScreen.forEach(window -> known.add(window.hwnd()));
            return;
        }

        // A handle is forgotten only once its window is destroyed — not merely hidden, as the taskbar
        // reorder hides every character for a moment — so Windows may hand it to a new window later.
        known.removeIf(hwnd -> !api.isWindow(hwnd));

        for (final var window : onScreen) {
            if (known.add(window.hwnd())) {
                maximize(window);
            }
        }
    }

    private void maximize(GameWindow window) {
        // Minimized by the player before the sweep came round: maximizing would pull it back up.
        if (api.isIconic(window.hwnd())) {
            return;
        }
        log.info("New game window '{}': maximizing it.", window.title());
        api.showWindow(window.hwnd(), Win32.SW_MAXIMIZE);
        activity.record("Window maximized", window.hasCharacterName() ? window.name() : "new window");
    }
}
