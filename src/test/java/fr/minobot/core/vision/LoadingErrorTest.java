package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The game's loading error, as measured on a 2560x1369 window: the red link to its solutions at x 383 to
 * 818, y 1188 to 1212; the orange buttons for the logs from x 1922 to 2165, the upper one y 1243 to 1281.
 */
class LoadingErrorTest {

    private static final int RED = 0xFF0000;
    private static final int ORANGE = 0xFF6100;

    /** The link's text, a stroke of it every few columns, and the upper button. */
    private static Capture withError(int width, int height, double scale, int left) {
        final var capture = blank(width, height);
        for (var x = left + at(17, scale); x < left + at(452, scale); x += at(8, scale)) {
            paint(capture, x, at(1190, scale), x + at(3, scale), at(1210, scale), RED);
        }
        paint(capture, left + at(1559, scale), at(1243, scale), left + at(1799, scale), at(1281, scale), ORANGE);
        return capture;
    }

    private static int at(int measured, double scale) {
        return (int) Math.round(measured * scale);
    }

    @Test
    void seesTheError() {
        assertThat(LoadingError.isUp(withError(2560, 1369, 1, 366))).isTrue();
    }

    @Test
    @DisplayName("sees it on a smaller window, where the stage is scaled down")
    void seesItScaled() {
        assertThat(LoadingError.isUp(withError(1280, 684, 0.5, 183))).isTrue();
    }

    @Test
    @DisplayName("wants both the red link and the orange buttons: neither alone is the error")
    void wantsBoth() {
        final var linkOnly = withError(2560, 1369, 1, 366);
        paint(linkOnly, 1925, 1243, 2165, 1281, 0);
        final var buttonsOnly = withError(2560, 1369, 1, 366);
        paint(buttonsOnly, 383, 1188, 820, 1212, 0);

        assertThat(LoadingError.isUp(linkOnly)).isFalse();
        assertThat(LoadingError.isUp(buttonsOnly)).isFalse();
        assertThat(LoadingError.isUp(blank(2560, 1369))).isFalse();
    }
}
