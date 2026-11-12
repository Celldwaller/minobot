package fr.minobot.core.vision;

import fr.minobot.win32.Frame;

/**
 * How a game window lays its interface out — the game's own option, set window by window.
 *
 * <p>{@link #CLASSIC} keeps everything on the stage: the chat under the map, the spell column at its left
 * edge. {@link #WIDE} — the game's <em>16:9</em> display — keeps the stage where it was and draws a column
 * left of it, outside the stage: the chat, taller and in a smaller type, and below the map a grid of sixteen
 * slots where the column of eight was. The main spell bar stays, two pixels lower.
 *
 * <p>The column is told by its right border, a band of {@code 0x8C8368} against the stage's left edge, from
 * top to bottom: where the classic layout leaves the black bar of a window wider than the stage, or nothing
 * at all. Measured on a 2560x1369 window, the only size it was seen at: the column's positions are written
 * left of the stage, in stage coordinates, which is where it was — a window of another shape may place it
 * otherwise.
 */
public enum Hud {

    CLASSIC,
    WIDE;

    private static final int BORDER = 0x8C8368;

    /** The middle of the border, and three rows down it: clear of the chat's panes and of the bar. */
    private static final double BORDER_X = -1.6;
    private static final double[] BORDER_ROWS = {100, 300, 500};

    /** The layout a frame shows; a frame whose border cannot be read is taken for the classic one. */
    public static Hud of(Frame frame) {
        final var stage = GameStage.of(frame);
        final var x = stage.x(BORDER_X);
        if (x < 0) {
            return CLASSIC;
        }
        final var top = stage.y(BORDER_ROWS[0]);
        final var bottom = stage.y(BORDER_ROWS[BORDER_ROWS.length - 1]) + 1;
        if (bottom > frame.height()) {
            return CLASSIC;
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
                .map(_ -> WIDE)
                .orElse(CLASSIC);
    }
}
