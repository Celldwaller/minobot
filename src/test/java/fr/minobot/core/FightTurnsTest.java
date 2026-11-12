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
    @DisplayName("the same fighter after another fighter is a new turn, however short that one's")
    void anotherFighterInBetweenEndsTheTurn() {
        turns.report("Alpha");
        elapse(1000);
        turns.report("Le Ouassingue");
        elapse(FightTurns.LINGER.toMillis());
        turns.report("Alpha");

        assertThat(heard).containsExactly("Alpha", "Le Ouassingue", "Alpha");
    }

    @Test
    @DisplayName("the last fighter's banner, still read in another window after the next one's, is no turn")
    void aBannerLeftOverIsNoTurn() {
        turns.report("Alpha");
        elapse(250);
        turns.report("Bravo");
        elapse(250);
        turns.report("Alpha");
        elapse(250);
        turns.report("Bravo");

        assertThat(heard).containsExactly("Alpha", "Bravo");
    }

    @Test
    @DisplayName("a banner left over does not end the turn it fell into")
    void aBannerLeftOverKeepsTheTurn() {
        turns.report("Alpha");
        elapse(250);
        turns.report("Bravo");
        elapse(250);
        turns.report("Alpha");
        elapse(FightTurns.SAME_TURN.toMillis() - 500);
        turns.report("Bravo");

        assertThat(heard).containsExactly("Alpha", "Bravo");
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
    @DisplayName("no fight before any turn is seen")
    void noFightBeforeATurn() {
        assertThat(turns.inFight()).isFalse();
    }

    @Test
    @DisplayName("a fight goes on until no turn has been seen for a while, a left-over banner included")
    void aFightOutlastsItsLastSighting() {
        turns.report("Alpha");
        elapse(250);
        turns.report("Bravo");
        elapse(250);
        turns.report("Alpha");
        elapse(FightTurns.FIGHT_GOES_ON.toMillis() - 1);
        assertThat(turns.inFight()).isTrue();

        elapse(1);
        assertThat(turns.inFight()).isFalse();
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

    @Test
    @DisplayName("a fight begins at the first turn after a silence, and is told once, before that turn")
    void tellsWhenAFightBegins() {
        final var told = new CopyOnWriteArrayList<String>();
        final var fights = new FightTurns(Runnable::run, now::get);
        fights.onFightStart(() -> told.add("fight"));
        fights.onTurn(told::add);

        fights.report("Alpha");
        elapse(3000);
        fights.report("Bravo");
        elapse(FightTurns.FIGHT_GOES_ON.toMillis());
        fights.report("Alpha");

        assertThat(told).containsExactly("fight", "Alpha", "Bravo", "fight", "Alpha");
    }
}
