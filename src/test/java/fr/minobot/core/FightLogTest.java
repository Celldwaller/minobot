package fr.minobot.core;

import fr.minobot.core.domain.Cast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Which lines of the chat are new at each look, and the casts in them. */
class FightLogTest {

    private final AtomicLong now = new AtomicLong();
    private final FightLog fightLog = new FightLog(now::get);
    private final List<Cast> heard = new ArrayList<>();
    private final List<Cast> failed = new ArrayList<>();
    private final List<Cast> settled = new ArrayList<>();

    FightLogTest() {
        fightLog.onCast(heard::add);
        fightLog.onFailure(failed::add);
        fightLog.onSettled(settled::add);
    }

    private void elapse(long millis) {
        now.addAndGet(millis * 1_000_000);
    }

    private void look(String... lines) {
        fightLog.report(List.of(lines));
        elapse(50);
    }

    @Test
    @DisplayName("what the chat showed at the first look was written before it, and is not heard")
    void theFirstLookIsWhatWasThere() {
        look("", "Alpha lance Flèche.", "Bravo perd 10 PDV.");

        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("a cast is heard once, however many looks still show its line")
    void hearsACastOnce() {
        look("a", "b", "c");
        look("b", "c", "Alpha lance Flèche.");
        look("b", "c", "Alpha lance Flèche.");
        look("c", "Alpha lance Flèche.", "(Coup critique)");

        assertThat(heard).containsExactly(new Cast("Alpha", "Flèche", false));
    }

    @Test
    @DisplayName("the same line twice in a row is two casts")
    void tellsIdenticalLinesApart() {
        look("a", "b", "c");
        look("c", "Alpha lance Flèche.", "Alpha lance Flèche.");

        assertThat(heard).hasSize(2);
    }

    @Test
    @DisplayName("a spell that pushes the whole chat out at once still has its line heard, if a look saw it")
    void hearsEveryLineOfABurst() {
        look("a", "b", "c");
        look("b", "c", "Alpha lance Pluie.");
        look("Bravo perd 1 PDV.", "Charlie perd 1 PDV.", "Delta perd 1 PDV.");

        assertThat(heard).containsExactly(new Cast("Alpha", "Pluie", false));
    }

    @Test
    @DisplayName("after a silence — between two fights — the chat is taken as it stands again")
    void aSilenceStartsAgain() {
        look("a", "b", "c");
        elapse(FightLog.SILENCE.toMillis());
        look("b", "c", "Alpha lance Flèche.");

        assertThat(heard).isEmpty();
    }

    @Test
    void findsWhatWasAdded() {
        assertThat(FightLog.added(List.of("a", "b", "c"), List.of("b", "c", "d"))).containsExactly("d");
        assertThat(FightLog.added(List.of("a", "b", "c"), List.of("a", "b", "c"))).isEmpty();
        assertThat(FightLog.added(List.of("a", "b", "c"), List.of("x", "y", "z"))).containsExactly("x", "y", "z");
        assertThat(FightLog.added(List.of("a", "b", "a"), List.of("b", "a", "b"))).containsExactly("b");
    }

    @Test
    @DisplayName("the line under a cast settles it: a critical failure, or anything else")
    void theNextLineSettlesACast() {
        look("a", "b", "c");
        look("c", "Alpha lance Flèche.", "(Echec critique)");
        look("(Echec critique)", "Bravo lance Pluie.");
        assertThat(settled).isEmpty();
        look("Bravo lance Pluie.", "(Coup critique)");

        assertThat(heard).containsExactly(new Cast("Alpha", "Flèche", false), new Cast("Bravo", "Pluie", false));
        assertThat(failed).containsExactly(new Cast("Alpha", "Flèche", false));
        assertThat(settled).containsExactly(new Cast("Bravo", "Pluie", false));
    }
}
