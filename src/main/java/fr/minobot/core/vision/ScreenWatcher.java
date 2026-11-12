package fr.minobot.core.vision;

import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Frame;
import fr.minobot.win32.Win32;
import fr.minobot.win32.Win32Exception;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Looks at the characters' windows, one per {@link #POLL} — one per {@link #FIGHT_POLL} during a fight —
 * in turn, and hands each frame to every {@link ScreenReader}.
 *
 * <p>In turn, because what is read is not all in the same place. The banner that opens a fight turn is
 * drawn in every window, so it is caught at every look, whichever window it is — four to twenty times over
 * the second it stands. A request is drawn only in the window of the character asked, and stays until it is
 * answered, so it is caught once the round comes to that window: a second for four characters.
 *
 * <p>One frame a window and a look, whatever the number of readers, each of which copies out the one region it reads
 * and checks a few pixels of it before it asks the OCR anything. The frame is the compositor's, already
 * drawn: taking it costs about 0.2 ms and a region 0.3 ms, where having a 2560 px window paint itself
 * cost 20 (measured: see {@link LookTimes}). A window that cannot be read — minimized — gives its look to
 * the next one.
 *
 * <p>What is read in one window only, and must not miss a beat, is {@link Pinned} to it: read at every
 * look, whichever window the round is at — the fight log in the chat, which scrolls away under a spell
 * that hits five fighters, and the spell bar of the window the player looks at. A window looked at twice
 * in one look gives one frame to both.
 */
public final class ScreenWatcher {

    private static final Logger log = LoggerFactory.getLogger(ScreenWatcher.class);

    static final Duration POLL = Duration.ofMillis(250);

    /**
     * The pace while a fight goes on. A turn is only heard of once a look catches its banner, so half a
     * pace is what every auto-pass waits on average before anything else; and a cast is only heard of
     * while its line is still in the chat — from the half second of its animation until the damage it
     * deals pushes it out. Out of a fight, a request box stays until answered and the slower pace is
     * enough. A look costs one to two milliseconds, its OCR aside: the watching thread is idle almost all
     * of the fight.
     */
    static final Duration FIGHT_POLL = Duration.ofMillis(50);

    /**
     * A reader of one window — chosen at every look among the characters', in the player's order — read
     * at every look.
     */
    public record Pinned(Function<List<GameWindow>, Optional<GameWindow>> window, ScreenReader reader) {
    }

    private final WindowApi api;
    private final WindowManager windows;
    private final List<ScreenReader> readers;
    private final List<Pinned> pinned;
    private final BooleanSupplier fighting;
    private final LongSupplier awaited;

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /** Where the round stands; only the watching thread moves it. */
    private int next;

    /** The frames of the look under way, by window; only the watching thread touches it. */
    private final Map<Long, Optional<Seen>> frames = new HashMap<>();

    /** Only the watching thread adds to it. */
    private final LookTimes times = new LookTimes();

    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers) {
        this(api, windows, readers, () -> false);
    }

    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers,
                         BooleanSupplier fighting) {
        this(api, windows, readers, fighting, () -> Win32.NULL_HANDLE);
    }

    /**
     * @param fighting whether a fight goes on, asked before every look
     * @param awaited  the window a request is awaited on ({@link Win32#NULL_HANDLE} for none), asked
     *                 before every look
     */
    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers,
                         BooleanSupplier fighting, LongSupplier awaited) {
        this(api, windows, readers, List.of(), fighting, awaited);
    }

    /** @param pinned the readers of one window each, read at every look */
    public ScreenWatcher(WindowApi api, WindowManager windows, List<ScreenReader> readers, List<Pinned> pinned,
                         BooleanSupplier fighting, LongSupplier awaited) {
        this.api = api;
        this.windows = windows;
        this.readers = List.copyOf(readers);
        this.pinned = List.copyOf(pinned);
        this.fighting = fighting;
        this.awaited = awaited;
    }

    /**
     * Starts watching on a platform thread: every look is a native call, and a native call pins a
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
        return fighting.getAsBoolean() || awaited.getAsLong() != Win32.NULL_HANDLE ? FIGHT_POLL : POLL;
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

    /**
     * One look: the next character's window in the round, read by every reader. The round follows the
     * player's character order, the cycler's: the turns of a fight come in roughly that order, so the next
     * window looked at tends to be the one whose banner comes next.
     *
     * <p>A window a request is awaited on is looked at instead, every look, and the round waits: the
     * relay is held up until its box is seen, and nothing else on screen is.
     */
    void look() {
        final var characters = windows.orderedWindows().stream().filter(GameWindow::hasCharacterName).toList();
        frames.clear();
        try {
            lookRound(characters);
            for (final var reader : pinned) {
                reader.window().apply(characters).ifPresent(window -> frame(window)
                        .ifPresent(seen -> read(() -> reader.reader().read(window, seen.frame(), seen.at()))));
            }
        } finally {
            frames.clear();
            times.looked();
        }
    }

    private void lookRound(List<GameWindow> characters) {
        final var expected = awaited.getAsLong();
        final var awaitedWindow = characters.stream().filter(window -> window.hwnd() == expected).findFirst();
        if (awaitedWindow.isPresent() && lookAt(awaitedWindow.get())) {
            return;
        }
        for (var tried = 0; tried < characters.size(); tried++) {
            if (lookAt(characters.get(Math.floorMod(next++, characters.size())))) {
                return;
            }
        }
    }

    /** Whether the window's frame could be had, and so was read. */
    private boolean lookAt(GameWindow window) {
        final var seen = frame(window);
        seen.ifPresent(it -> read(() -> readers.forEach(reader -> reader.read(window, it.frame(), it.at()))));
        return seen.isPresent();
    }

    /** The window's frame for this look: taken once, however many readers read it. */
    private Optional<Seen> frame(GameWindow window) {
        return frames.computeIfAbsent(window.hwnd(), hwnd -> {
            final var start = System.nanoTime();
            final var frame = api.frame(hwnd);
            frame.ifPresent(_ -> times.captured(System.nanoTime() - start));
            return frame.map(it -> new Seen(it, start));
        });
    }

    private void read(Runnable reading) {
        final var start = System.nanoTime();
        reading.run();
        times.read(System.nanoTime() - start);
    }

    /** A frame, and when it was asked for, on the {@link System#nanoTime()} clock. */
    private record Seen(Frame frame, long at) {
    }

    /**
     * What the looks cost, summed over {@link #REPORTED_EVERY} of them and logged at debug: a look runs
     * four to twenty times a second for the whole session, so its price is measured, not assumed — and a
     * line per look would drown the log. A look's frames and readings are summed: the round's window and
     * the pinned ones.
     */
    private static final class LookTimes {

        private static final int REPORTED_EVERY = 40;

        private int looks;
        private long capturing;
        private long reading;
        private long lookCapture;
        private long lookReading;
        private long slowestCapture;
        private long slowestReading;

        void captured(long nanos) {
            lookCapture += nanos;
        }

        void read(long nanos) {
            lookReading += nanos;
        }

        void looked() {
            looks++;
            capturing += lookCapture;
            reading += lookReading;
            slowestCapture = Math.max(slowestCapture, lookCapture);
            slowestReading = Math.max(slowestReading, lookReading);
            lookCapture = lookReading = 0;
            if (looks == REPORTED_EVERY) {
                log.debug("Over {} looks: frames {} ms on average ({} at worst), reading {} ms ({} at worst).",
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
