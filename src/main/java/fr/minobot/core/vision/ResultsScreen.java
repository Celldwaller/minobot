package fr.minobot.core.vision;

import fr.minobot.win32.Frame;

/**
 * Tells, off a capture of a game window, whether the end-of-fight screen is up — by its close button.
 *
 * <p>The screen is shown in one of two sizes, the player's choice: reduced, a strip of the character's
 * own gains whose place is fixed; or whole, a table of every fighter, centred on the map and as tall as
 * the fight was crowded. Either way its close button sits at the right of its title bar, at a fixed
 * abscissa for each size: it is looked for down those two columns, from under the game's own buttons at
 * the top right of the stage to the bottom of the map.
 *
 * <p>The button is an orange square with a white cross: its middle is white, orange either side and above
 * and below. The size button left of it is the same orange, a column apart. Positions are the stage's
 * (see {@link GameStage}), measured on a 2560x1369 window with the screen in each size; nothing else in
 * 1282 captures of fights and maps matched, the 16:9 display's among them.
 */
public final class ResultsScreen {

    private static final int ORANGE = 0xFF6100;
    private static final int WHITE = 0xFFFFFF;

    /** The close button's centre column: the whole screen's, then the reduced one's. */
    private static final double[] COLUMNS = {715.8, 553.2};

    /** How far from the centre the button is orange all round, the cross's arms aside. */
    private static final double ARM = 3;

    /** Under the game's own buttons, top right of the stage; down to the bottom of the map. */
    private static final double TOP = 16;
    private static final double BOTTOM = 432;

    private ResultsScreen() {
    }

    public static boolean isUp(Frame frame) {
        final var stage = GameStage.of(frame);
        final var arm = stage.length(ARM);
        final var top = Math.max(stage.y(TOP), arm);
        final var bottom = Math.min(stage.y(BOTTOM), frame.height() - arm);
        for (final var column : COLUMNS) {
            final var x = stage.x(column);
            if (x - arm < 0 || x + arm >= frame.width() || top >= bottom) {
                continue;
            }
            final var found = Region.of(frame, x - arm, top - arm, x + arm + 1, bottom + arm)
                    .filter(strip -> hasButton(strip, x, arm, top, bottom))
                    .isPresent();
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasButton(Region strip, int x, int arm, int top, int bottom) {
        for (var y = top; y < bottom; y++) {
            if (strip.is(x, y, WHITE)
                    && strip.is(x - arm, y, ORANGE) && strip.is(x + arm, y, ORANGE)
                    && strip.is(x, y - arm, ORANGE) && strip.is(x, y + arm, ORANGE)) {
                return true;
            }
        }
        return false;
    }
}
