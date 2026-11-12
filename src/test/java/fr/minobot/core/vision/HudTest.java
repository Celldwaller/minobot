package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/** The 16:9 layout told by its column's border, x 360 to 366 of a 2560x1369 window, top to bottom. */
class HudTest {

    static final int BORDER = 0x8C8368;

    /** A 2560x1369 window in the 16:9 layout: its column's border painted. */
    static Capture wide() {
        final var capture = blank(2560, 1369);
        paint(capture, 360, 0, 366, 1369, BORDER);
        return capture;
    }

    @Test
    void readsTheWideLayout() {
        assertThat(Hud.of(wide())).isEqualTo(Hud.WIDE);
    }

    @Test
    @DisplayName("a black bar left of the stage is the classic layout")
    void readsTheClassicLayout() {
        assertThat(Hud.of(blank(2560, 1369))).isEqualTo(Hud.CLASSIC);
    }

    @Test
    @DisplayName("a window with no room left of the stage is the classic layout")
    void aNarrowWindowIsClassic() {
        assertThat(Hud.of(blank(742, 556))).isEqualTo(Hud.CLASSIC);
    }

    @Test
    @DisplayName("a border that does not run the whole height is no border")
    void aPartialBorderIsNotOne() {
        final var capture = blank(2560, 1369);
        paint(capture, 360, 0, 366, 600, BORDER);

        assertThat(Hud.of(capture)).isEqualTo(Hud.CLASSIC);
    }

    @Test
    @DisplayName("an unreadable frame is taken for the classic layout")
    void anUnreadableFrameIsClassic() {
        assertThat(Hud.of(RecordingFrame.unreadable(wide()))).isEqualTo(Hud.CLASSIC);
    }
}
