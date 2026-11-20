package fr.minobot.feature.window;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.input.BackgroundKeys;
import fr.minobot.core.vision.LoadingError;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Reloads a game window left on the game's loading error, in the background, until it loads.
 *
 * <p>At launch, a window now and then stops on {@code Impossible de lire le fichier de configuration}
 * ({@link LoadingError}), and {@code CTRL+R} — the reload of the browser the game runs in — is all it
 * takes to load it. Pressed in the background ({@link BackgroundKeys}): the player launching eight
 * windows keeps the one they are on.
 *
 * <p>Only a window with no character is looked at: the error comes before the login screen, and a
 * window with a character in it has loaded. A reload takes about two seconds to show the loading screen
 * again; a window on the error {@link #RETRY} after its reload is reloaded again, up to {@link #ATTEMPTS}
 * times before it reaches a character, then left to the player.
 */
public final class LoadingReloader {

    private static final Logger log = LoggerFactory.getLogger(LoadingReloader.class);

    /** How long a window may stay on the error before it is reloaded — a sweep costs a frame of each loading window. */
    static final Duration SWEEP = Duration.ofSeconds(1);

    /** Measured: the error went away 0.3 s after the reload, and the window reached the login screen 1.5 s later. */
    static final Duration RETRY = Duration.ofSeconds(5);

    /** A window still on the error after this many reloads fails for a reason no reload mends. */
    static final int ATTEMPTS = 5;

    private static final int VK_R = Win32.VK_A + ('R' - 'A');

    private final WindowApi api;
    private final BackgroundKeys keys;
    private final WindowManager windows;
    private final ActivityLog activity;
    private final LongSupplier nanos;

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /** The windows reloaded and still loading, by handle; only the sweeping thread touches it. */
    private final Map<Long, Reloads> reloaded = new HashMap<>();

    /**
     * @param last  when the window was last reloaded, on the {@link #nanos} clock
     * @param count how many times since it opened
     */
    private record Reloads(long last, int count) {
    }

    public LoadingReloader(WindowApi api, WindowManager windows, ActivityLog activity) {
        this(api, windows, activity, System::nanoTime);
    }

    /** @param nanos a {@link System#nanoTime()}-like clock */
    LoadingReloader(WindowApi api, WindowManager windows, ActivityLog activity, LongSupplier nanos) {
        this.api = api;
        this.keys = new BackgroundKeys(api);
        this.windows = windows;
        this.activity = activity;
        this.nanos = nanos;
    }

    /**
     * Starts sweeping on a platform thread: every sweep is a run of native calls, and the reload holds
     * {@code CONTROL} in the window's thread, which only a platform thread may.
     */
    public void start() {
        final var sweeper = Thread.ofPlatform().daemon().name("loading-reloader").unstarted(this::sweepForever);
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
                    log.warn("Loading sweep failed; trying again at the next one.", e);
                }
                Thread.sleep(SWEEP);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reloads the loading windows that show the error, and forgets those that reached a character or
     * closed — not those merely back on the loading screen, which may fail again a moment later.
     */
    void sweep() {
        final var loading = windows.scan().stream()
                .filter(window -> !window.hasCharacterName())
                .collect(Collectors.toMap(GameWindow::hwnd, window -> window, (first, _) -> first));
        reloaded.keySet().retainAll(loading.keySet());

        final var now = nanos.getAsLong();
        for (final var window : loading.values()) {
            final var last = reloaded.get(window.hwnd());
            if (last != null && now - last.last() < RETRY.toNanos()) {
                continue;
            }
            if (api.frame(window.hwnd()).filter(LoadingError::isUp).isEmpty()) {
                continue;
            }
            if (last == null || last.count() < ATTEMPTS) {
                reload(window, last == null ? 1 : last.count() + 1, now);
            } else if (last.count() == ATTEMPTS) {
                log.warn("A game window is still on its loading error after {} reloads; left as it is.", ATTEMPTS);
                activity.record(Text.of("activity.loading_given_up", ATTEMPTS));
                reloaded.put(window.hwnd(), new Reloads(now, ATTEMPTS + 1));
            }
        }
    }

    private void reload(GameWindow window, int attempt, long now) {
        if (!keys.pressWithControl(window.hwnd(), VK_R, Win32.SCAN_R)) {
            log.warn("Could not press CTRL+R in a game window on its loading error.");
            return;
        }
        log.info("A game window was on its loading error: reloaded it (attempt {}).", attempt);
        reloaded.put(window.hwnd(), new Reloads(now, attempt));
        activity.record(Text.of("activity.loading_reloaded"));
    }
}
