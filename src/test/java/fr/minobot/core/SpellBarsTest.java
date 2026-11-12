package fr.minobot.core;

import fr.minobot.core.domain.GameWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SpellBarsTest {

    private final AtomicLong now = new AtomicLong();
    private final SpellBars bars = new SpellBars(now::get);
    private final GameWindow alpha = new GameWindow(1, "Alpha - Dofus Retro");

    @Test
    @DisplayName("a bar is up as last seen, while the sighting is fresh")
    void holdsTheLastSighting() {
        assertThat(bars.isUp(1)).isFalse();

        bars.report(alpha, true, now.get());
        assertThat(bars.isUp(1)).isTrue();

        bars.report(alpha, false, now.get());
        assertThat(bars.isUp(1)).isFalse();

        bars.report(alpha, true, now.get());
        now.addAndGet(SpellBars.FRESH.toNanos());
        assertThat(bars.isUp(1)).as("stale").isFalse();
    }
}
