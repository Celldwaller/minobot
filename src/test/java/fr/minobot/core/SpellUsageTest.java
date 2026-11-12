package fr.minobot.core;

import fr.minobot.core.domain.Cast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** What each fighter cast this fight, as the fight log and the turns tell it. */
class SpellUsageTest {

    private final AtomicLong now = new AtomicLong();
    private final FightLog fightLog = new FightLog(now::get);
    private final SpellUsage usage = new SpellUsage(fightLog);

    private void cast(String line) {
        fightLog.report(List.of("before"));
        fightLog.report(List.of(line));
        fightLog.report(List.of("after"));
    }

    @Test
    @DisplayName("each fighter's casts, once each, in the order first cast")
    void remembersWhatEachCast() {
        cast("Alpha lance Flèche.");
        cast("Bravo attaque avec Marteau.");
        cast("alpha lance Pluie.");
        cast("Alpha lance Fleche.");

        assertThat(usage.of("Alpha")).extracting(Cast::action).containsExactly("Flèche", "Pluie");
        assertThat(usage.of("Bravo")).containsExactly(new Cast("Bravo", "Marteau", true));
        assertThat(usage.of("Charlie")).isEmpty();
    }

    @Test
    @DisplayName("a spell cast again is told: the challenge is lost")
    void tellsARepeat() {
        final var repeats = new ArrayList<Cast>();
        usage.onRepeat(repeats::add);

        cast("Alpha lance Flèche.");
        cast("Alpha lance Flèche.");

        assertThat(repeats).containsExactly(new Cast("Alpha", "Flèche", false));
    }

    @Test
    @DisplayName("the fight's end, read in the chat, forgets what was cast")
    void forgetsAtTheFightsEnd() {
        cast("Alpha lance Flèche.");
        fightLog.report(List.of("after", "Combat termine (ouvrir l'ecran de fin de"));

        assertThat(usage.of("Alpha")).isEmpty();
    }

    @Test
    @DisplayName("a critical failure is no cast: the spell may be cast again, and that is no repeat")
    void aCriticalFailureIsNoCast() {
        final var repeats = new ArrayList<Cast>();
        usage.onRepeat(repeats::add);
        fightLog.report(List.of("before"));

        fightLog.report(List.of("before", "Alpha lance Flèche."));
        assertThat(usage.of("Alpha")).as("crossed out as soon as read").hasSize(1);
        fightLog.report(List.of("Alpha lance Flèche.", "(Echec critique)"));
        assertThat(usage.of("Alpha")).isEmpty();

        cast("Alpha lance Flèche.");
        assertThat(usage.of("Alpha")).hasSize(1);
        assertThat(repeats).isEmpty();
    }

    @Test
    @DisplayName("a spell cast again that fails leaves the first cast standing, and breaks nothing")
    void aFailedRepeatKeepsTheFirst() {
        final var repeats = new ArrayList<Cast>();
        usage.onRepeat(repeats::add);

        cast("Alpha lance Flèche.");
        fightLog.report(List.of("Alpha lance Flèche.", "(Échec critique)"));

        assertThat(usage.of("Alpha")).hasSize(1);
        assertThat(repeats).isEmpty();
    }
}
