package fr.minobot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The turns of a fight, one announcement per turn, whoever saw it begin.
 *
 * <p>A turn is seen more than once. The game raises a toast for it — unless the window has gone deaf to
 * toasts, which a posted click does to it — and draws a banner that the screen watcher reads several
 * times over the second it stands. A feature that ends a turn must hear of it once: a second {@code F1}
 * pressed into a window ends whatever turn it is there by then. So every source reports here, and the
 * features listen here, to neither source in particular.
 *
 * <p>The same fighter reported again is the same turn, unless another fighter was reported in between or
 * it has been quiet for {@link #SAME_TURN}. The fighter is anyone the banner names — a monster too: a
 * monster's turn between two of a character's is what tells them apart.
 */
public final class FightTurns {

    private static final Logger log = LoggerFactory.getLogger(FightTurns.class);

    /**
     * How far apart two sightings of one turn may arrive — the toast and the banner of a turn come within
     * a second of each other. Two turns of the same fighter have another fighter's in between, and that
     * one's banner is what separates them; this only matters when the banner was not read at all.
     */
    static final Duration SAME_TURN = Duration.ofSeconds(2);

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private final LongSupplier nanos;

    private String lastFighter;
    private long lastSeenNanos;

    public FightTurns() {
        this(Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime);
    }

    /**
     * @param dispatcher where each listener runs; a listener focuses and types, so it must not run on
     *                   the thread of the source that reported the turn
     * @param nanos      a {@link System#nanoTime()}-like clock
     */
    public FightTurns(Executor dispatcher, LongSupplier nanos) {
        this.dispatcher = dispatcher;
        this.nanos = nanos;
    }

    /** Hears of every turn as it begins, with the fighter's name as the game writes it. */
    public void onTurn(Consumer<String> listener) {
        listeners.add(listener);
    }

    /** A source saw {@code fighter}'s turn begin — or still saw it going on. */
    public void report(String fighter) {
        if (isNewTurn(fighter)) {
            log.debug("Turn of '{}'.", fighter);
            listeners.forEach(listener -> dispatcher.execute(() -> tell(listener, fighter)));
        }
    }

    private synchronized boolean isNewTurn(String fighter) {
        final var now = nanos.getAsLong();
        final var same = lastFighter != null
                && lastFighter.toLowerCase(Locale.ROOT).equals(fighter.toLowerCase(Locale.ROOT))
                && now - lastSeenNanos < SAME_TURN.toNanos();
        lastFighter = fighter;
        lastSeenNanos = now;
        return !same;
    }

    private static void tell(Consumer<String> listener, String fighter) {
        try {
            listener.accept(fighter);
        } catch (RuntimeException e) {
            log.error("Error in a fight-turn listener.", e);
        }
    }
}
