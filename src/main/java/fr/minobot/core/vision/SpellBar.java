package fr.minobot.core.vision;

import fr.minobot.win32.Frame;
import fr.minobot.win32.Point;
import fr.minobot.win32.Rect;

import java.util.OptionalInt;

/**
 * Where the slots of a character's spell bar are, and whether the bar is on screen to be marked.
 *
 * <p>The slots are numbered once for all: {@value #WEAPON} is the weapon's, left of the main bar; 1 to 7
 * the main bar's top row and 8 to 14 its bottom row, left to right. From 15 come the extra slots, which
 * the {@link Hud} lays out its own way: the classic one as a column of eight at the left edge of the game,
 * top to bottom (15 to 22); the 16:9 one as a grid of sixteen under the map, row by row (15 to 30) — its
 * first two rows the same eight spells as the column, in the same order, so a layout learned in one holds
 * in the other. They are where the game draws them, whatever is in them — a player's bar is theirs to fill,
 * not ours to read.
 *
 * <p>The bar is not always there to be marked. Hovering a fighter replaces it with that fighter's sheet,
 * and its <em>Obj.</em> tab with the character's items; it is up when the gaps between its slots show its
 * own background and its <em>Sorts</em> tab is the one lit. Slot edges are anti-aliased differently from
 * one column to the next: only the flat colours between them are checked.
 *
 * <p>Positions are the stage's (see {@link GameStage}), measured on a 2560x1369 window.
 */
public final class SpellBar {

    /** The weapon's slot. */
    public static final int WEAPON = 0;

    /** The first of the extra slots, past the main bar's two rows of seven. */
    private static final int ROW = 7;
    private static final int FIRST_EXTRA = 1 + 2 * ROW;

    /** A slot's side, its frame included. */
    private static final double SIDE = 25.6;

    private static final double WEAPON_LEFT = 486.7;

    /** The main bar: its first slot's left edge, and from one slot to the next across. */
    private static final double MAIN_LEFT = 518.0;
    private static final double MAIN_PITCH_X = 28.02;

    /** The classic top edges, of the weapon and of the main bar, and from one row to the next. */
    private static final Bar CLASSIC = new Bar(485.7, 486.1, 29.24,
            new Extra(3.0, 192.9, 1, 8, 28.02, SIDE));

    /** The 16:9 ones: the same bar a little lower, and the grid of four by four under the map. */
    private static final Bar WIDE = new Bar(486.55, 486.96, 28.84,
            new Extra(246.72, 451.22, 4, 4, 26.128, 24.27));

    /** The bar's background, between its slots — where a fighter's sheet shows its own beige. */
    private static final int BACKGROUND = 0xB8B18F;

    /** The gaps checked: right of the first six slots of the top row, half way down it, in either layout. */
    private static final double GAP_OFFSET = SIDE + 1.2;
    private static final double GAP_ROW = CLASSIC.mainTop() + SIDE / 2;
    private static final int GAPS = 6;

    /** The <em>Sorts</em> tab while it is the one lit, at a point clear of its letters. */
    private static final int TAB_LIT = 0xB4AC8D;
    private static final double TAB_X = 720.3;
    private static final double TAB_Y = 483.3;

    /** The part of the stage read: the main bar's top row and the tab, edges included. */
    private static final double READ_LEFT = MAIN_LEFT;
    private static final double READ_RIGHT = TAB_X + 1;
    private static final double READ_TOP = TAB_Y;
    private static final double READ_BOTTOM = CLASSIC.mainTop() + SIDE;

    private SpellBar() {
    }

    /** How many slots a layout has: the weapon, two rows of seven, and its extra slots. */
    public static int slots(Hud hud) {
        return FIRST_EXTRA + bar(hud).extra().count();
    }

    /** Whether the frame shows the spell bar, with its spells: no fighter's sheet over it, no items. */
    public static boolean isUp(Frame frame) {
        final var stage = GameStage.of(frame);
        return Region.of(frame, stage.x(READ_LEFT), stage.y(READ_TOP), stage.x(READ_RIGHT), stage.y(READ_BOTTOM))
                .filter(bar -> bar.is(stage.x(TAB_X), stage.y(TAB_Y), TAB_LIT))
                .filter(bar -> gapsShow(bar, stage))
                .isPresent();
    }

    private static boolean gapsShow(Region bar, GameStage stage) {
        final var y = stage.y(GAP_ROW);
        for (var gap = 0; gap < GAPS; gap++) {
            if (!bar.is(stage.x(MAIN_LEFT + gap * MAIN_PITCH_X + GAP_OFFSET), y, BACKGROUND)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where a slot is drawn in a window's client area, in client coordinates.
     *
     * @param width  the client area's width
     * @param height the client area's height
     */
    public static Rect slot(Hud hud, int slot, int width, int height) {
        final var stage = GameStage.of(width, height);
        final var box = box(hud, slot);
        return new Rect(stage.x(box[0]), stage.y(box[1]), stage.x(box[0] + box[2]), stage.y(box[1] + box[2]));
    }

    /** The slot under a point of a client area this wide and high, in client coordinates. */
    public static OptionalInt slotAt(Hud hud, Point point, int width, int height) {
        final var stage = GameStage.of(width, height);
        final var x = stage.stageX(point.x());
        final var y = stage.stageY(point.y());
        for (var slot = 0; slot < slots(hud); slot++) {
            final var box = box(hud, slot);
            if (x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[2]) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** A slot's left edge, top edge and side, in stage coordinates. */
    private static double[] box(Hud hud, int slot) {
        if (slot < 0 || slot >= slots(hud)) {
            throw new IndexOutOfBoundsException("There is no spell slot " + slot + " in the " + hud + " layout");
        }
        final var bar = bar(hud);
        if (slot == WEAPON) {
            return new double[]{WEAPON_LEFT, bar.weaponTop(), SIDE};
        }
        if (slot < FIRST_EXTRA) {
            return new double[]{MAIN_LEFT + (slot - 1) % ROW * MAIN_PITCH_X,
                    bar.mainTop() + (slot - 1) / ROW * bar.mainPitchY(), SIDE};
        }
        final var extra = bar.extra();
        final var index = slot - FIRST_EXTRA;
        return new double[]{extra.left() + index % extra.columns() * extra.pitch(),
                extra.top() + index / extra.columns() * extra.pitch(), extra.side()};
    }

    private static Bar bar(Hud hud) {
        return hud == Hud.WIDE ? WIDE : CLASSIC;
    }

    /** A layout's top edges for the weapon and the main bar, its rows' pitch, and its extra slots. */
    private record Bar(double weaponTop, double mainTop, double mainPitchY, Extra extra) {
    }

    /** The extra slots: the first one's corner, laid out row by row, all at one pitch and of one side. */
    private record Extra(double left, double top, int columns, int rows, double pitch, double side) {

        int count() {
            return columns * rows;
        }
    }
}
