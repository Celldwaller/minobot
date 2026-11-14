package fr.minobot.core.vision;

import fr.minobot.core.domain.GameWindow;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Whether each window's spell bar was last seen up — with its spells on it, and nothing over it — so a
 * mark is drawn on a slot only while the slot shows what it marks; and in which {@link Hud}, which says
 * where its slots are.
 */
public final class SpellBars {

    /**
     * How long a sighting holds. The window looked at is read at every look, every 50 ms; past
     * this, what it showed is not known any more, and a mark is better left off than drawn over a sheet.
     */
    static final Duration FRESH = Duration.ofMillis(600);

    private final LongSupplier nanos;

    /** Window handle → when it was last seen, and how. */
    private final Map<Long, Sighting> seen = new ConcurrentHashMap<>();

    public SpellBars() {
        this(System::nanoTime);
    }

    /** @param nanos a {@link System#nanoTime()}-like clock */
    public SpellBars(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** @param seenAt when the bar was seen, on the {@code nanos} clock */
    public void report(GameWindow window, Hud hud, boolean up, long seenAt) {
        seen.put(window.hwnd(), new Sighting(hud, up, seenAt));
    }

    /** The layout the window was last seen in — the classic one until it is seen. */
    public Hud hud(long hwnd) {
        final var last = seen.get(hwnd);
        return last == null ? Hud.CLASSIC : last.hud();
    }

    /** Whether the window's bar was last seen up, lately. */
    public boolean isUp(long hwnd) {
        final var last = seen.get(hwnd);
        return last != null && last.up() && nanos.getAsLong() - last.at() < FRESH.toNanos();
    }

    private record Sighting(Hud hud, boolean up, long at) {
    }
}
