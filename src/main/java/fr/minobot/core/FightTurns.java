package fr.minobot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The turns of a fight, one announcement per turn, whoever saw it begin.
 *
 * <p>A turn is seen more than once. The game draws a banner for it in every window, and the screen
 * watcher reads it several times over the second it stands, window after window. A feature that ends a
 * turn must hear of it once: a second {@code F1} pressed into a window ends whatever turn it is there by
 * then. So every sighting is reported here, and the features listen here.
 *
 * <p>The same fighter reported again is the same turn, unless another fighter was reported in between or
 * it has been quiet for {@link #SAME_TURN}. The fighter is anyone the banner names — a monster too: a
 * monster's turn between two of a character's is what tells them apart. A fighter reported again within
 * {@link #LINGER} of their last sighting, after another's, is a banner left over and not a turn.
 */
public final class FightTurns {

    private static final Logger log = LoggerFactory.getLogger(FightTurns.class);

    /**
     * How far apart two sightings of one turn may arrive — a banner stands about a second, and is read
     * several times over it. Two turns of the same fighter have another fighter's in between, and that
     * one's banner is what separates them; this only matters when the banner was not read at all.
     */
    static final Duration SAME_TURN = Duration.ofSeconds(2);

    /**
     * How long the last fighter's banner may still be read after the next one's. Every window draws the
     * banner on its own, and the watcher reads them in turn: right after the next fighter's banner in one
     * window, another window can still show the last one's — read as a new turn, that was a second
     * {@code F1} ending the next fighter's turn (seen with eight characters on auto-pass, up to a second
     * late). A real turn comes back no sooner than every other fighter has played, so the only turn this
     * can swallow is one of a fight of two, both passed at once — a turn left to run out, never one ended.
     */
    static final Duration LINGER = Duration.ofMillis(1500);

    /**
     * How long a fight is taken to go on after the last banner of a turn. Only a turn's start is
     * seen, never its end nor the fight's: this must outlast one fighter's whole turn, the game's turn
     * timer included, or the fight is called over in the middle of a slow one.
     */
    static final Duration FIGHT_GOES_ON = Duration.ofSeconds(60);

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> fightListeners = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private final LongSupplier nanos;

    /** Whose turn it is, as last reported; {@code null} before the first. */
    private String current;
    private final Map<String, Long> lastSeen = new HashMap<>();

    /** When any turn was last reported, a left-over banner included; {@code null} before the first. */
    private volatile Long lastSighting;

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

    /**
     * Hears of every fight as its first turn is reported — a turn after {@link #FIGHT_GOES_ON} of
     * silence — before that turn's listeners, and on the reporting thread: a listener only notes it.
     */
    public void onFightStart(Runnable listener) {
        fightListeners.add(listener);
    }

    /** A source saw {@code fighter}'s turn begin — or still saw it going on, or saw it late. */
    public void report(String fighter) {
        report(fighter, nanos.getAsLong());
    }

    /** The same, from a source that knows when it saw the turn — {@code seenAt} on the {@code nanos} clock. */
    public void report(String fighter, long seenAt) {
        if (begins(fighter.toLowerCase(Locale.ROOT))) {
            log.debug("Turn of '{}'.", fighter);
            listeners.forEach(listener -> dispatcher.execute(() -> tell(listener, fighter, seenAt)));
        }
    }

    /** Whether a fight seems to be going on: a turn was reported within {@link #FIGHT_GOES_ON}. */
    public boolean inFight() {
        final var last = lastSighting;
        return last != null && nanos.getAsLong() - last < FIGHT_GOES_ON.toNanos();
    }

    private synchronized boolean begins(String fighter) {
        final var now = nanos.getAsLong();
        if (!inFight()) {
            log.debug("A fight begins.");
            for (final var listener : fightListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.error("Error in a fight-start listener.", e);
                }
            }
        }
        lastSighting = now;
        final var seen = lastSeen.get(fighter);
        if (fighter.equals(current)) {
            lastSeen.put(fighter, now);
            return now - seen >= SAME_TURN.toNanos();
        }
        if (seen != null && now - seen < LINGER.toNanos()) {
            log.debug("'{}' seen again after '{}': a banner left over, not a turn.", fighter, current);
            return false;
        }
        current = fighter;
        lastSeen.put(fighter, now);
        return true;
    }

    /**
     * The delay is logged from the sighting to the listener's return — its focus taken, its key pressed:
     * what the player waits for is the whole of it, not the OCR's share alone.
     */
    private void tell(Consumer<String> listener, String fighter, long seenAt) {
        try {
            listener.accept(fighter);
            log.debug("A listener of '{}'s turn returned {} ms after it was seen.",
                    fighter, (nanos.getAsLong() - seenAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.error("Error in a fight-turn listener.", e);
        }
    }
}
