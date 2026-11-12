package fr.minobot.core;

import fr.minobot.core.domain.Cast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The fight log, line by line as the chat writes it, each line heard of once — and the casts in it.
 *
 * <p>The chat is read whole at every look — its last few lines — and most looks find the lines the last
 * one found. What is new is what comes after the longest run of the last look's lines that the new look
 * starts with: the chat scrolled up by as many lines as were written. Two identical lines in a row — the
 * same spell cast twice — are told apart by their place, not their text.
 *
 * <p>A line too long for the chat goes on under it, and the 16:9 chat is narrow: a cast whose line does
 * not end with its full stop takes the next line as the end of its spell's name, when that line reads like
 * one — a full stop at its end, and none of the other lines' words.
 *
 * <p>A cast is told as soon as its line is read, then <em>settled</em> by the line that follows it: a
 * critical failure — {@code (Échec critique)}, written right under the cast — means the spell was not
 * cast after all ({@link #onFailure}); anything else, that it was ({@link #onSettled}).
 *
 * <p>A look after a silence is not compared with the last: the chat is only read during a fight, and what
 * it shows when a fight begins was written before. That look is taken as it stands, and only what comes
 * after it is new.
 */
public final class FightLog {

    private static final Logger log = LoggerFactory.getLogger(FightLog.class);

    /** Longer than any pause between two looks of a fight, shorter than the time from one fight to the next. */
    static final Duration SILENCE = Duration.ofSeconds(2);

    /**
     * Words of the other lines of the log, which the end of a spell's name has none of — nor its opening
     * bracket: {@code (Coup critique)}, {@code [21:01] …}. Matched against the line with a space before it.
     */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            " perd | lance | attaque avec | gagne | entre | est | : |^ [(\\[]");

    private final List<Consumer<Cast>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Cast>> failureListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Cast>> settledListeners = new CopyOnWriteArrayList<>();
    private final LongSupplier nanos;

    /** What the last look read, and when; only the reading thread touches them. */
    private List<String> last = List.of();
    private long lastAt;
    private boolean read;

    /** The cast on the last line heard, until the next line settles it; {@code null} for none. */
    private Cast unsettled;

    public FightLog() {
        this(System::nanoTime);
    }

    /** @param nanos a {@link System#nanoTime()}-like clock */
    public FightLog(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /**
     * Hears of every cast as its line is first read, on the reading thread: a listener that does more than
     * note it hands the rest to a thread of its own.
     */
    public void onCast(Consumer<Cast> listener) {
        listeners.add(listener);
    }

    /** Hears of a cast that failed critically — told by {@link #onCast} first, and not cast after all. */
    public void onFailure(Consumer<Cast> listener) {
        failureListeners.add(listener);
    }

    /** Hears of a cast that did not fail, once the line after it says so. */
    public void onSettled(Consumer<Cast> listener) {
        settledListeners.add(listener);
    }

    /** The chat's last lines as a look read them, oldest first. */
    public synchronized void report(List<String> lines) {
        final var now = nanos.getAsLong();
        final var continued = read && now - lastAt < SILENCE.toNanos();
        final var fresh = continued ? added(last, lines) : List.<String>of();
        if (!continued) {
            unsettled = null;
        }
        last = List.copyOf(lines);
        lastAt = now;
        read = true;
        for (var index = 0; index < fresh.size(); index++) {
            final var line = fresh.get(index);
            if (unsettled != null) {
                final var failed = isFailure(line);
                tell(failed ? failureListeners : settledListeners, unsettled);
                unsettled = null;
                if (failed) {
                    continue;
                }
            }
            final var heard = Cast.in(line);
            if (heard.isEmpty()) {
                continue;
            }
            var cast = heard.get();
            if (!line.strip().endsWith(".") && index + 1 < fresh.size() && endsAName(fresh.get(index + 1))) {
                final var rest = fresh.get(++index).strip();
                cast = new Cast(cast.fighter(), cast.action() + " " + rest.substring(0, rest.length() - 1), cast.weapon());
            }
            log.debug("{} cast '{}'{}.", cast.fighter(), cast.action(), cast.weapon() ? " (weapon)" : "");
            tell(listeners, cast);
            unsettled = cast;
        }
    }

    /** Whether a line reads like the end of a spell's name cut off the line above. */
    private static boolean endsAName(String line) {
        final var stripped = line.strip();
        return stripped.length() > 1 && stripped.endsWith(".") && !NOT_A_NAME.matcher(" " + stripped).find();
    }

    /** {@code (Échec critique)} — read as {@code (Echec critique)} as often as not. */
    private static boolean isFailure(String line) {
        return Cast.key(line).equals("(echec critique)");
    }

    /** The lines of {@code now} that {@code before} did not end with. */
    static List<String> added(List<String> before, List<String> now) {
        for (var kept = Math.min(before.size(), now.size()); kept > 0; kept--) {
            if (before.subList(before.size() - kept, before.size()).equals(now.subList(0, kept))) {
                return now.subList(kept, now.size());
            }
        }
        return now;
    }

    private static void tell(List<Consumer<Cast>> listeners, Cast cast) {
        for (final var listener : listeners) {
            try {
                listener.accept(cast);
            } catch (RuntimeException e) {
                log.error("Error in a cast listener.", e);
            }
        }
    }
}
