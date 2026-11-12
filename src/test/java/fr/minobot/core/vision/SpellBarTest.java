package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.Point;
import fr.minobot.win32.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.BEIGE;
import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The slots where they were measured on a 2560x1369 window — the weapon's at x 1565, y 1196; the main bar's
 * first at x 1642, y 1197, every 69 across and 72 down; the column's first at x 374, y 475, every 69
 * down; 63 pixels a side — and the bar told apart from a fighter's sheet over it.
 */
class SpellBarTest {

    private static final int BAR = 0xB8B18F;
    private static final int TAB = 0xB4AC8D;
    private static final int ICON = 0x2A6EB0;

    /** The main bar up: its background, a slot's icon every 69 pixels, the Sorts tab lit. */
    private static Capture bar() {
        final var capture = blank(2560, 1369);
        paint(capture, 1550, 1185, 2130, 1345, BAR);
        for (var column = 0; column < 7; column++) {
            paint(capture, 1642 + 69 * column, 1197, 1642 + 69 * column + 63, 1260, ICON);
        }
        paint(capture, 2130, 1185, 2170, 1265, TAB);
        return capture;
    }

    @Test
    @DisplayName("each classic slot is where it was measured, to the pixel")
    void placesTheClassicSlots() {
        assertThat(SpellBar.slots(Hud.CLASSIC)).isEqualTo(23);
        assertThat(SpellBar.slot(Hud.CLASSIC, SpellBar.WEAPON, 2560, 1369)).isEqualTo(new Rect(1565, 1196, 1628, 1259));
        assertThat(SpellBar.slot(Hud.CLASSIC, 1, 2560, 1369)).isEqualTo(new Rect(1642, 1197, 1705, 1260));
        assertThat(SpellBar.slot(Hud.CLASSIC, 7, 2560, 1369).left()).isEqualTo(1642 + 6 * 69);
        assertThat(SpellBar.slot(Hud.CLASSIC, 8, 2560, 1369)).isEqualTo(new Rect(1642, 1269, 1705, 1332));
        assertThat(SpellBar.slot(Hud.CLASSIC, 15, 2560, 1369)).isEqualTo(new Rect(374, 475, 437, 538));
        assertThat(SpellBar.slot(Hud.CLASSIC, 22, 2560, 1369).top()).isEqualTo(475 + 7 * 69);
    }

    @Test
    @DisplayName("each 16:9 slot is where it was measured: the bar two pixels lower, the grid under the map")
    void placesTheWideSlots() {
        assertThat(SpellBar.slots(Hud.WIDE)).isEqualTo(31);
        assertThat(SpellBar.slot(Hud.WIDE, SpellBar.WEAPON, 2560, 1369).top()).isEqualTo(1198);
        assertThat(SpellBar.slot(Hud.WIDE, 1, 2560, 1369).top()).isEqualTo(1199);
        assertThat(SpellBar.slot(Hud.WIDE, 8, 2560, 1369).top()).isEqualTo(1270);
        assertThat(SpellBar.slot(Hud.WIDE, 15, 2560, 1369)).isEqualTo(new Rect(974, 1111, 1034, 1171));
        assertThat(SpellBar.slot(Hud.WIDE, 18, 2560, 1369).left()).isEqualTo(1167);
        assertThat(SpellBar.slot(Hud.WIDE, 30, 2560, 1369)).satisfies(slot -> {
            assertThat(slot.left()).isEqualTo(1167);
            assertThat(slot.top()).isEqualTo(1304);
        });
    }

    @Test
    @DisplayName("the slot under a point is the slot drawn there, in either layout, at any size")
    void findsTheSlotUnderAPoint() {
        for (final var hud : Hud.values()) {
            for (final var size : new int[][]{{2560, 1369}, {1920, 1017}, {1280, 720}}) {
                for (var slot = 0; slot < SpellBar.slots(hud); slot++) {
                    final var drawn = SpellBar.slot(hud, slot, size[0], size[1]);
                    assertThat(SpellBar.slotAt(hud, drawn.center(), size[0], size[1])).hasValue(slot);
                }
            }
        }
        assertThat(SpellBar.slotAt(Hud.CLASSIC, new Point(1708, 1228), 2560, 1369)).as("between two slots").isEmpty();
        assertThat(SpellBar.slotAt(Hud.CLASSIC, new Point(1280, 600), 2560, 1369)).as("the map").isEmpty();
    }

    @Test
    void refusesASlotThatIsNot() {
        assertThatThrownBy(() -> SpellBar.slot(Hud.CLASSIC, 23, 2560, 1369)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("the bar, its spells tab lit, is up")
    void seesTheBar() {
        assertThat(SpellBar.isUp(bar())).isTrue();
    }

    @Test
    @DisplayName("a fighter's sheet over the bar is no bar")
    void aSheetIsNoBar() {
        final var sheet = bar();
        paint(sheet, 1550, 1185, 2130, 1345, BEIGE);

        assertThat(SpellBar.isUp(sheet)).isFalse();
    }

    @Test
    @DisplayName("the items tab is no bar of spells")
    void theItemsTabIsNoBar() {
        final var items = bar();
        paint(items, 2130, 1185, 2170, 1265, 0x494236);

        assertThat(SpellBar.isUp(items)).isFalse();
    }

    @Test
    @DisplayName("of a frame, one region is read")
    void readsOneRegion() {
        final var frame = RecordingFrame.of(bar());

        assertThat(SpellBar.isUp(frame)).isTrue();
        assertThat(frame.regions).hasSize(1);
    }
}
