package fr.minobot.core.vision;

import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Win32Exception;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Looks at the characters' windows, one per {@link #POLL} — one per {@link #FIGHT_POLL} during a fight —
 * in turn, and hands each capture to every {@link ScreenReader}.
 *
 * <p>In turn, because what is read is not all in the same place. The banner that opens a fight turn is
 * drawn in every window, so it is caught at every look, whichever window it is — four to ten times over
 * the second it stands. A request is drawn only in the window of the character asked, and stays until it is
 * answered, so it is caught once the round comes to that window: a second for four characters.
 *
 * <p>One capture a look, whatever the number of readers: a capture is the cost (about 20 ms of a 2560
 * px window, measured: see {@link LookTimes}), and the readers check a few pixels before they ask the OCR anything. A window that cannot
 * be captured — minimized — gives its look to the next one.
 */
public final class ScreenWatcher {

    private static final Logger log = LoggerFactory.getLogger(ScreenWatcher.class);

    static final Duration POLL = Duration.ofMillis(250);

    /**
     * The pace while a fight goes on. A turn is only heard of once a look catches its banner, so half a
     * pace is what every auto-pass waits on average before anything else; out of a fight, a request box
     * stays until answered and the slower pace is enough. A capture costs about 25 ms in game: the
     * watching thread is busy a quarter of the fight.
     */
    static final Duration FIGHT_POLL = Duration.ofMillis(100);

    private final WindowApi api;
    private final WindowManager windows;
    private final List<ScreenReader> readers;
    private final BooleanSupplier fighting;

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /** Where the round stands; only the watching thread moves it. */
    private int next;

    /** Only the watching thread adds to it. */
    private final LookTimes times = new LookTimes();

    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers) {
        this(api, windows, readers, () -> false);
    }

    /** @param fighting whether a fight goes on, asked before every look */
    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers,
                         BooleanSupplier fighting) {
        this.api = api;
        this.windows = windows;
        this.readers = List.copyOf(readers);
        this.fighting = fighting;
    }

    /**
     * Starts watching on a platform thread: every look is a native capture, and a native call pins a
     * virtual thread's carrier for its whole length.
     */
    public void start() {
        final var watcher = Thread.ofPlatform().daemon().name("screen-watcher").unstarted(this::watch);
        if (thread.compareAndSet(null, watcher)) {
            watcher.start();
            log.info("Watching the game windows.");
        }
    }

    public void stop() {
        final var current = thread.getAndSet(null);
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * A look every {@link #pace()}, the look's own time included: slept after it, a look of 150 ms made
     * the turn banner read every 400 ms, not every 250.
     */
    private void watch() {
        try {
            while (thread.get() == Thread.currentThread()) {
                final var start = System.nanoTime();
                final var pace = pace();
                lookOnce();
                final var left = pace.minusNanos(System.nanoTime() - start);
                if (left.isPositive()) {
                    Thread.sleep(left);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Duration pace() {
        return fighting.getAsBoolean() ? FIGHT_POLL : POLL;
    }

    /** A look that fails is the next look's to retry — except a bug, which would fail at every look. */
    private void lookOnce() {
        try {
            look();
        } catch (Win32Exception e) {
            log.error("The game windows cannot be read; no longer watching.", e);
            thread.set(null);
        } catch (RuntimeException e) {
            log.error("Unexpected error while watching the game windows.", e);
        }
    }

    /** One look: the next character's window in the round, read by every reader. */
    void look() {
        windows.ensureFresh();
        final var characters = windows.windows().stream().filter(GameWindow::hasCharacterName).toList();
        for (var tried = 0; tried < characters.size(); tried++) {
            final var window = characters.get(Math.floorMod(next++, characters.size()));
            final var start = System.nanoTime();
            final var capture = api.capture(window.hwnd());
            if (capture.isPresent()) {
                final var captured = System.nanoTime();
                readers.forEach(reader -> reader.read(window, capture.get(), start));
                times.add(captured - start, System.nanoTime() - captured);
                return;
            }
        }
    }

    /**
     * What the looks cost, summed over {@link #REPORTED_EVERY} of them and logged at debug: a look runs
     * four to ten times a second for the whole session, so its price is measured, not assumed — and a line per
     * look would drown the log.
     */
    private static final class LookTimes {

        private static final int REPORTED_EVERY = 40;

        private int looks;
        private long capturing;
        private long reading;
        private long slowestCapture;
        private long slowestReading;

        void add(long captureNanos, long readingNanos) {
            looks++;
            capturing += captureNanos;
            reading += readingNanos;
            slowestCapture = Math.max(slowestCapture, captureNanos);
            slowestReading = Math.max(slowestReading, readingNanos);
            if (looks == REPORTED_EVERY) {
                log.debug("Over {} looks: capture {} ms on average ({} at worst), reading {} ms ({} at worst).",
                        looks, millis(capturing / looks), millis(slowestCapture),
                        millis(reading / looks), millis(slowestReading));
                looks = 0;
                capturing = reading = slowestCapture = slowestReading = 0;
            }
        }

        private static String millis(long nanos) {
            return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
        }
    }
}
