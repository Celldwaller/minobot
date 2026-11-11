package fr.minobot.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** How sightings of turns become turns: every source reports, every listener hears each turn once. */
class FightTurnsTest {

    private final AtomicLong now = new AtomicLong();
    private final FightTurns turns = new FightTurns(Runnable::run, now::get);
    private final List<String> heard = new CopyOnWriteArrayList<>();

    FightTurnsTest() {
        turns.onTurn(heard::add);
    }

    private void elapse(long millis) {
        now.addAndGet(millis * 1_000_000);
    }

    @Test
    @DisplayName("a banner read four times and a toast make one turn")
    void hearsATurnOnce() {
        turns.report("Alpha");
        elapse(250);
        turns.report("Alpha");
        elapse(250);
        turns.report("alpha");
        elapse(250);
        turns.report("Alpha");

        assertThat(heard).containsExactly("Alpha");
    }

    @Test
    @DisplayName("the same fighter after another fighter is a new turn, however soon")
    void anotherFighterInBetweenEndsTheTurn() {
        turns.report("Alpha");
        elapse(300);
        turns.report("Le Ouassingue");
        elapse(300);
        turns.report("Alpha");

        assertThat(heard).containsExactly("Alpha", "Le Ouassingue", "Alpha");
    }

    @Test
    @DisplayName("the same fighter after a quiet spell is a new turn — the one between was not seen")
    void aQuietSpellEndsTheTurn() {
        turns.report("Alpha");
        elapse(FightTurns.SAME_TURN.toMillis());
        turns.report("Alpha");

        assertThat(heard).containsExactly("Alpha", "Alpha");
    }

    @Test
    @DisplayName("a banner standing long is one turn: every sighting keeps it alive")
    void aLongBannerIsOneTurn() {
        for (var i = 0; i < 12; i++) {
            turns.report("Bravo");
            elapse(250);
        }

        assertThat(heard).containsExactly("Bravo");
    }

    @Test
    @DisplayName("a listener that throws does not keep the turn from the others")
    void survivesAFailingListener() {
        final var others = new FightTurns(Runnable::run, now::get);
        final var told = new CopyOnWriteArrayList<String>();
        others.onTurn(_ -> {
            throw new IllegalStateException("boom");
        });
        others.onTurn(told::add);

        others.report("Bravo");

        assertThat(told).containsExactly("Bravo");
    }
}
