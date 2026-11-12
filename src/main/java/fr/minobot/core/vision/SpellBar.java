package fr.minobot.core.vision;

import fr.minobot.win32.Frame;
import fr.minobot.win32.Point;
import fr.minobot.win32.Rect;

import java.util.OptionalInt;

/**
 * Where the slots of a character's spell bar are, and whether the bar is on screen to be marked.
 *
 * <p>The slots are numbered once for all: {@value #WEAPON} is the weapon's, left of the main bar; 1 to 7
 * the main bar's top row and 8 to 14 its bottom row, left to right; 15 to 22 the column at the left edge
 * of the game, top to bottom. They are where the game draws them, whatever is in them — a player's bar is
 * theirs to fill, not ours to read.
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

    /** The slots in a row of the main bar, and in the column. */
    private static final int ROW = 7;
    private static final int COLUMN = 8;

    /** How many slots there are: the weapon, two rows of seven, and the column of eight. */
    public static final int SLOTS = 1 + 2 * ROW + COLUMN;

    /** A slot's side, its frame included. */
    private static final double SIDE = 25.6;

    private static final double WEAPON_LEFT = 486.7;
    private static final double WEAPON_TOP = 485.7;

    /** The main bar: its first slot's corner, and from one slot to the next. */
    private static final double MAIN_LEFT = 518.0;
    private static final double MAIN_TOP = 486.1;
    private static final double MAIN_PITCH_X = 28.02;
    private static final double MAIN_PITCH_Y = 29.24;

    /** The column at the left edge. */
    private static final double COLUMN_LEFT = 3.0;
    private static final double COLUMN_TOP = 192.9;
    private static final double COLUMN_PITCH = 28.02;

    /** The bar's background, between its slots — where a fighter's sheet shows its own beige. */
    private static final int BACKGROUND = 0xB8B18F;

    /** The gaps checked: right of the first six slots of the top row, half way down it. */
    private static final double GAP_OFFSET = SIDE + 1.2;
    private static final int GAPS = 6;

    /** The <em>Sorts</em> tab while it is the one lit, at a point clear of its letters. */
    private static final int TAB_LIT = 0xB4AC8D;
    private static final double TAB_X = 720.3;
    private static final double TAB_Y = 483.3;

    /** The part of the stage read: the main bar's top row and the tab, edges included. */
    private static final double READ_LEFT = MAIN_LEFT;
    private static final double READ_RIGHT = TAB_X + 1;
    private static final double READ_TOP = TAB_Y;
    private static final double READ_BOTTOM = MAIN_TOP + SIDE;

    private SpellBar() {
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
        final var y = stage.y(MAIN_TOP + SIDE / 2);
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
    public static Rect slot(int slot, int width, int height) {
        final var stage = GameStage.of(width, height);
        final var left = left(slot);
        final var top = top(slot);
        return new Rect(stage.x(left), stage.y(top), stage.x(left + SIDE), stage.y(top + SIDE));
    }

    /** The slot under a point of a client area this wide and high, in client coordinates. */
    public static OptionalInt slotAt(Point point, int width, int height) {
        final var stage = GameStage.of(width, height);
        final var x = stage.stageX(point.x());
        final var y = stage.stageY(point.y());
        for (var slot = 0; slot < SLOTS; slot++) {
            if (x >= left(slot) && x < left(slot) + SIDE && y >= top(slot) && y < top(slot) + SIDE) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    private static double left(int slot) {
        checkIndex(slot);
        if (slot == WEAPON) {
            return WEAPON_LEFT;
        }
        if (slot <= 2 * ROW) {
            return MAIN_LEFT + (slot - 1) % ROW * MAIN_PITCH_X;
        }
        return COLUMN_LEFT;
    }

    private static double top(int slot) {
        checkIndex(slot);
        if (slot == WEAPON) {
            return WEAPON_TOP;
        }
        if (slot <= 2 * ROW) {
            return MAIN_TOP + (slot - 1) / ROW * MAIN_PITCH_Y;
        }
        return COLUMN_TOP + (slot - 1 - 2 * ROW) * COLUMN_PITCH;
    }

    private static void checkIndex(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IndexOutOfBoundsException("There is no spell slot " + slot);
        }
    }
}
