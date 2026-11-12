package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 16:9 layout told by its column's border, top to bottom of a 2560x1369 window: x 360 to 366 when the
 * column is left of the stage, x 2193 to 2199 when it is right of it; and its spell column, kept instead of
 * the grid, by the beige down x 400 to 410.
 */
class HudTest {

    static final int BORDER = 0x8C8368;
    private static final int SPELL_COLUMN = 0xB8B18F;

    /** A 2560x1369 window in the 16:9 layout: its column's border painted. */
    static Capture wide() {
        final var capture = blank(2560, 1369);
        paint(capture, 360, 0, 366, 1369, BORDER);
        return capture;
    }

    /** The same, its column right of the stage. */
    static Capture wideChatRight() {
        final var capture = blank(2560, 1369);
        paint(capture, 2193, 0, 2199, 1369, BORDER);
        return capture;
    }

    @Test
    void readsTheWideLayout() {
        assertThat(Hud.of(wide())).isEqualTo(Hud.WIDE);
        assertThat(Hud.WIDE.isWide()).isTrue();
    }

    @Test
    @DisplayName("the column right of the stage is the 16:9 layout too")
    void readsTheWideLayoutWithItsChatRight() {
        assertThat(Hud.of(wideChatRight())).isEqualTo(new Hud(Hud.Chat.RIGHT, true));
        assertThat(new Hud(Hud.Chat.RIGHT, true).isWide()).isTrue();
        assertThat(Hud.CLASSIC.isWide()).isFalse();
    }

    @Test
    @DisplayName("the 16:9 layout may keep the spell column instead of the grid, its chat on either side")
    void readsTheKeptSpellColumn() {
        final var left = wide();
        paint(left, 400, 460, 410, 1030, SPELL_COLUMN);
        final var right = wideChatRight();
        paint(right, 400, 460, 410, 1030, SPELL_COLUMN);

        assertThat(Hud.of(left)).isEqualTo(new Hud(Hud.Chat.LEFT, false));
        assertThat(Hud.of(right)).isEqualTo(new Hud(Hud.Chat.RIGHT, false));
    }

    @Test
    @DisplayName("a spell column broken anywhere is the grid's place, not the column")
    void aBrokenColumnIsNotOne() {
        final var capture = wide();
        paint(capture, 400, 460, 410, 1000, SPELL_COLUMN);

        assertThat(Hud.of(capture)).isEqualTo(Hud.WIDE);
    }

    @Test
    @DisplayName("the classic layout has its column, and is the classic layout")
    void theClassicColumnIsClassic() {
        final var capture = blank(2560, 1369);
        paint(capture, 400, 460, 410, 1030, SPELL_COLUMN);

        assertThat(Hud.of(capture)).isEqualTo(Hud.CLASSIC);
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
