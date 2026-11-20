package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.input.BackgroundKeys;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Closes the end-of-fight screen the game opens in every fighting character's window, but one's.
 *
 * <p>It hears of the fight's end from the {@link FightLog}: the chat's {@code Combat terminé} line. For a
 * while after it, the screen watcher looks for the screen in every window and tells it here
 * ({@link #seen}), which presses {@code ESCAPE} in that window, in the background ({@link BackgroundKeys}):
 * no window takes the screen. Only where the screen is up: a character who was not in the fight has none,
 * and {@code ESCAPE} opens its game menu.
 *
 * <p>The screen is left open in the window the player reads it in — the main character's, the first of
 * their order, unless it is minimized; then the one on screen. A minimized window shows nothing to look
 * for, and keeps its screen.
 */
public final class ResultsCloser {

    private static final Logger log = LoggerFactory.getLogger(ResultsCloser.class);

    /**
     * The screen opens within a second or so of the chat's line; one seen long after is one the player
     * opened again, and theirs to close.
     */
    static final Duration WAIT = Duration.ofSeconds(10);

    private final WindowApi api;
    private final BackgroundKeys keys;
    private final WindowManager windows;
    private final ActivityLog activity;
    private final Executor dispatcher;
    private final LongSupplier nanos;

    /** The fight's end being closed after, {@code null} before the first. */
    private volatile Sweep sweep;

    /**
     * @param kept    the window whose screen is left open
     * @param until   when the screens stop being looked for, on the {@link #nanos} clock
     * @param pressed the windows pressed in already: once the screen is closed, {@code ESCAPE} opens the menu
     */
    private record Sweep(long kept, long until, Set<Long> pressed) {
    }

    public ResultsCloser(WindowApi api, WindowManager windows, FightLog fightLog, ActivityLog activity) {
        this(api, windows, fightLog, activity, Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime);
    }

    /**
     * @param dispatcher where each press runs, off the watcher's thread
     * @param nanos      a {@link System#nanoTime()}-like clock
     */
    public ResultsCloser(WindowApi api, WindowManager windows, FightLog fightLog, ActivityLog activity,
                         Executor dispatcher, LongSupplier nanos) {
        this.api = api;
        this.keys = new BackgroundKeys(api);
        this.windows = windows;
        this.activity = activity;
        this.dispatcher = dispatcher;
        this.nanos = nanos;

        fightLog.onFightEnd(this::fightEnded);
    }

    void fightEnded() {
        final var foreground = api.foregroundWindow();
        final var kept = windows.orderedWindows().stream().findFirst()
                .filter(main -> !api.isIconic(main.hwnd()))
                .map(GameWindow::hwnd)
                .orElse(foreground);
        sweep = new Sweep(kept, nanos.getAsLong() + WAIT.toNanos(), ConcurrentHashMap.newKeySet());
    }

    /** Whether the screens are being looked for — what spares the watcher looking at every look. */
    public boolean sweeping() {
        final var current = sweep;
        return current != null && nanos.getAsLong() < current.until();
    }

    /**
     * An end-of-fight screen seen in a character's window — told by the screen watcher, on its thread,
     * which the press must not hold up.
     */
    public void seen(GameWindow window) {
        final var current = sweep;
        if (!sweeping() || window.hwnd() == current.kept() || !current.pressed().add(window.hwnd())) {
            return;
        }
        dispatcher.execute(() -> close(window));
    }

    private void close(GameWindow window) {
        if (!keys.press(window.hwnd(), Win32.VK_ESCAPE, Win32.SCAN_ESCAPE)) {
            log.warn("Could not press ESCAPE in '{}': its end-of-fight screen was left open.", window.name());
            return;
        }
        log.info("Closed '{}'s end-of-fight screen.", window.name());
        activity.record(Text.of("activity.results_closed", window.name()));
    }
}
