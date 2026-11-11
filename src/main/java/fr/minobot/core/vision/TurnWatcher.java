package fr.minobot.core.vision;

import fr.minobot.core.FightTurns;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Win32Exception;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches one game window for the banner that opens every fight turn, and reports whose turn it is to
 * {@link FightTurns}.
 *
 * <p>One window is enough: the banner is drawn in every fighter's window. The foreground one is taken
 * when it is the game's, since it is certainly painted; any character's window otherwise — a background
 * window captures just as well.
 *
 * <p>The banner stands about a second, so a look every {@link #POLL} catches each one several times;
 * {@link FightTurns} makes one turn of them. A look with no banner up costs a capture and three pixels —
 * the OCR runs only on a banner.
 */
public final class TurnWatcher {

    private static final Logger log = LoggerFactory.getLogger(TurnWatcher.class);

    static final Duration POLL = Duration.ofMillis(250);

    private final WindowApi api;
    private final WindowManager windows;
    private final TurnBanner banner;
    private final FightTurns turns;

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    public TurnWatcher(WindowApi api, WindowManager windows, TurnBanner banner, FightTurns turns) {
        this.api = api;
        this.windows = windows;
        this.banner = banner;
        this.turns = turns;
    }

    /**
     * Starts watching on a platform thread: every look is a native capture, and a native call pins a
     * virtual thread's carrier for its whole length.
     */
    public void start() {
        final var watcher = Thread.ofPlatform().daemon().name("turn-watcher").unstarted(this::watch);
        if (thread.compareAndSet(null, watcher)) {
            watcher.start();
            log.info("Watching the fight's turn banners.");
        }
    }

    public void stop() {
        final var current = thread.getAndSet(null);
        if (current != null) {
            current.interrupt();
        }
    }

    private void watch() {
        try {
            while (thread.get() == Thread.currentThread()) {
                lookOnce();
                Thread.sleep(POLL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A look that fails is the next look's to retry — except a bug, which would fail at every look. */
    private void lookOnce() {
        try {
            look();
        } catch (Win32Exception e) {
            log.error("The turn banner cannot be read; no longer watching.", e);
            thread.set(null);
        } catch (RuntimeException e) {
            log.error("Unexpected error while watching the turn banner.", e);
        }
    }

    /** One look: whose turn the banner names now, if one is up. */
    void look() {
        watchedWindow()
                .flatMap(window -> api.capture(window.hwnd()))
                .flatMap(banner::fighter)
                .ifPresent(turns::report);
    }

    private Optional<GameWindow> watchedWindow() {
        final var front = windows.foregroundGameWindow().filter(GameWindow::hasCharacterName);
        if (front.isPresent()) {
            return front;
        }
        windows.ensureFresh();
        return windows.windows().stream().filter(GameWindow::hasCharacterName).findFirst();
    }
}
