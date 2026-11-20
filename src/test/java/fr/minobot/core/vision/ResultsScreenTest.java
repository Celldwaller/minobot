package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-of-fight screen's close button, as measured on a 2560x1369 window: x 2117 to 2141 with the
 * screen whole, x 1716 to 1741 with it reduced — an orange square, a white cross across its middle.
 */
class ResultsScreenTest {

    private static final int ORANGE = 0xFF6100;
    private static final int WHITE = 0xFFFFFF;

    /** A close button, its cross reduced to the white middle the reader checks. */
    private static Capture withButton(int left, int top) {
        final var capture = blank(2560, 1369);
        paint(capture, left, top, left + 25, top + 24, ORANGE);
        paint(capture, left + 10, top + 10, left + 15, top + 14, WHITE);
        return capture;
    }

    @Test
    @DisplayName("sees the whole screen, wherever the crowd of the fight puts its title bar")
    void seesTheWholeScreen() {
        assertThat(ResultsScreen.isUp(withButton(2117, 308))).isTrue();
        assertThat(ResultsScreen.isUp(withButton(2117, 120))).isTrue();
    }

    @Test
    void seesTheReducedScreen() {
        assertThat(ResultsScreen.isUp(withButton(1716, 874))).isTrue();
    }

    @Test
    @DisplayName("nothing on a window with no screen up, nor in the game's own buttons above the map")
    void seesNothingElse() {
        assertThat(ResultsScreen.isUp(blank(2560, 1369))).isFalse();
        assertThat(ResultsScreen.isUp(withButton(2117, 8))).isFalse();
    }

    @Test
    @DisplayName("an orange square with no cross is not the button")
    void wantsTheCross() {
        final var capture = blank(2560, 1369);
        paint(capture, 2117, 308, 2142, 332, ORANGE);

        assertThat(ResultsScreen.isUp(capture)).isFalse();
    }
}
