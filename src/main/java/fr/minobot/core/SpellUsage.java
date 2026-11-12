package fr.minobot.core;

import fr.minobot.core.domain.Cast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What every fighter has cast in the fight going on — the spells and the weapon, once each — which is
 * what the <em>Économe</em> challenge forbids casting again.
 *
 * <p>Heard from {@link FightLog}, and forgotten when it reads the fight's end. A cast is
 * counted as soon as it is read, so its slot is crossed out at once, and taken back if the next line says
 * it failed critically: a failed spell may be cast again. A spell cast again is only told once the log has
 * said it did not fail.
 *
 * <p>Whoever shows it still asks whether a fight goes on: an end the chat was not read for — its window
 * not in the fight, the fight messages hidden — leaves the last fight's casts until a reset.
 */
public final class SpellUsage {

    private static final Logger log = LoggerFactory.getLogger(SpellUsage.class);

    private final List<Consumer<Cast>> repeatListeners = new CopyOnWriteArrayList<>();

    /** Fighter, in lower case → what they cast, by {@link Cast#key}, in the order they first cast it. */
    private final Map<String, Map<String, Cast>> cast = new HashMap<>();

    /** The last cast heard, until the log settles it, and whether it was cast before. */
    private Cast unsettled;
    private boolean again;

    public SpellUsage(FightLog fightLog) {
        fightLog.onCast(this::heard);
        fightLog.onFailure(this::failed);
        fightLog.onSettled(this::settled);
        fightLog.onFightEnd(this::clear);
    }

    /**
     * Hears of a fighter casting again what they already cast this fight — once the log says it did not
     * fail — on the reading thread.
     */
    public void onRepeat(Consumer<Cast> listener) {
        repeatListeners.add(listener);
    }

    /** What the fighter cast this fight, in the order they first cast it. */
    public synchronized List<Cast> of(String fighter) {
        return List.copyOf(cast.getOrDefault(fighter.toLowerCase(Locale.ROOT), Map.of()).values());
    }

    synchronized void heard(Cast heard) {
        unsettled = heard;
        again = cast.computeIfAbsent(heard.fighter().toLowerCase(Locale.ROOT), _ -> new LinkedHashMap<>())
                .putIfAbsent(Cast.key(heard.action()), heard) != null;
    }

    /** A failed spell is no cast — unless it had been cast before, which still stands. */
    synchronized void failed(Cast failed) {
        if (failed.equals(unsettled) && !again) {
            final var of = cast.get(failed.fighter().toLowerCase(Locale.ROOT));
            if (of != null) {
                of.remove(Cast.key(failed.action()));
            }
        }
        unsettled = null;
    }

    void settled(Cast settled) {
        final boolean repeated;
        synchronized (this) {
            repeated = settled.equals(unsettled) && again;
            unsettled = null;
        }
        if (repeated) {
            log.info("{} cast '{}' again this fight.", settled.fighter(), settled.action());
            repeatListeners.forEach(listener -> listener.accept(settled));
        }
    }

    /** Forgets every cast — as a fight's end does, or the player when the chat missed it. */
    public synchronized void clear() {
        cast.clear();
        unsettled = null;
    }
}
