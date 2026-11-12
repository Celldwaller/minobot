package fr.minobot.core.vision;

import fr.minobot.win32.Frame;

import java.util.Locale;

/**
 * How a game window lays its interface out — the game's own options, set window by window: where its chat
 * is, and whether its extra spells are a grid or a column.
 *
 * <p>{@link #CLASSIC} keeps everything on the stage: the chat under the map, the spell column at its left
 * edge. The game's <em>16:9</em> display keeps the stage where it was and draws a column beside it, outside
 * the stage — left of it, or right of it at the player's choice: the chat, taller and in a smaller type.
 * The main spell bar stays, two pixels lower; below the map, a grid of sixteen slots takes the column of
 * eight's place — unless the player keeps the column, and the grid's place shows the minimap.
 *
 * <p>The column is told by its border, a band of {@code 0x8C8368} against the stage's edge, from top to
 * bottom: where the classic layout leaves the black bar of a window wider than the stage, or nothing at all.
 * The spell column is told by its gaps, the bar's beige between each two slots, above the first and below
 * the last. Measured on a 2560x1369 window, the only size it was seen at: the column's positions are written
 * beside the stage, in stage coordinates, which is where it was — a window of another shape may place it
 * otherwise.
 *
 * @param grid whether the extra spells are the 16:9 grid of sixteen, not the column of eight
 */
public record Hud(Chat chat, boolean grid) {

    /** Where a window's chat is. */
    public enum Chat {
        UNDER_MAP,
        LEFT,
        RIGHT
    }

    public static final Hud CLASSIC = new Hud(Chat.UNDER_MAP, false);

    /** The 16:9 display as the game first shows it. */
    public static final Hud WIDE = new Hud(Chat.LEFT, true);

    private static final int BORDER = 0x8C8368;

    /**
     * The middle of the border, left of the stage or right of it, and three rows down it: clear of the
     * chat's panes and of the bar.
     */
    private static final double LEFT_BORDER_X = -1.6;
    private static final double RIGHT_BORDER_X = 742.8;
    private static final double[] BORDER_ROWS = {100, 300, 500};

    /** The spell column's beige, down its middle: in the gap above its first slot, then one slot apart. */
    private static final int COLUMN_BEIGE = 0xB8B18F;
    private static final double COLUMN_X = 15.6;
    private static final double COLUMN_FIRST_GAP = 191.7;
    private static final double COLUMN_PITCH = 28.02;
    private static final int COLUMN_GAPS = 9;

    /** Whether it is the 16:9 display. */
    public boolean isWide() {
        return chat != Chat.UNDER_MAP;
    }

    /** The layout a frame shows; a frame whose border cannot be read is taken for the classic one. */
    public static Hud of(Frame frame) {
        final var stage = GameStage.of(frame);
        final Chat chat;
        if (hasBorder(frame, stage, LEFT_BORDER_X)) {
            chat = Chat.LEFT;
        } else if (hasBorder(frame, stage, RIGHT_BORDER_X)) {
            chat = Chat.RIGHT;
        } else {
            return CLASSIC;
        }
        return new Hud(chat, !hasSpellColumn(frame, stage));
    }

    private static boolean hasBorder(Frame frame, GameStage stage, double stageX) {
        final var x = stage.x(stageX);
        final var top = stage.y(BORDER_ROWS[0]);
        final var bottom = stage.y(BORDER_ROWS[BORDER_ROWS.length - 1]) + 1;
        if (x < 0 || x >= frame.width() || bottom > frame.height()) {
            return false;
        }
        return Region.of(frame, x, top, x + 1, bottom)
                .filter(column -> {
                    for (final var row : BORDER_ROWS) {
                        if (!column.is(x, stage.y(row), BORDER)) {
                            return false;
                        }
                    }
                    return true;
                })
                .isPresent();
    }

    private static boolean hasSpellColumn(Frame frame, GameStage stage) {
        final var x = stage.x(COLUMN_X);
        final var top = stage.y(COLUMN_FIRST_GAP);
        final var bottom = stage.y(COLUMN_FIRST_GAP + (COLUMN_GAPS - 1) * COLUMN_PITCH) + 1;
        return Region.of(frame, x, top, x + 1, bottom)
                .filter(column -> {
                    for (var gap = 0; gap < COLUMN_GAPS; gap++) {
                        if (!column.is(x, stage.y(COLUMN_FIRST_GAP + gap * COLUMN_PITCH), COLUMN_BEIGE)) {
                            return false;
                        }
                    }
                    return true;
                })
                .isPresent();
    }

    /** As the probe prints it: {@code classic}, {@code 16:9, chat right, column}. */
    @Override
    public String toString() {
        return isWide()
                ? "16:9, chat " + chat.name().toLowerCase(Locale.ROOT) + (grid ? ", grid" : ", column")
                : "classic";
    }
}
