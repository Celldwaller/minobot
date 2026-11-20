package fr.minobot.core.vision;

import fr.minobot.win32.Frame;

/**
 * Tells, off a capture of a game window, whether the game failed to load: the error a window is
 * sometimes left on at launch, {@code Erreur : Impossible de lire le fichier de configuration}, which a
 * reload clears.
 *
 * <p>Told by two things the loading screen shows only then, whatever art it draws: the red link under
 * the error, to its solutions, on the black under the art; and the two orange buttons that copy and show
 * the logs, bottom right. Pure colours, counted rather than read: the art behind changes at every launch,
 * and the words with the player's language. Positions are the stage's (see {@link GameStage}), measured
 * on six 2560x1369 windows left on the error at once, each with its own art: the link's line was 1545
 * pixels of pure red in every one, the buttons' strip 3332 of pure orange.
 */
public final class LoadingError {

    private static final int RED = 0xFF0000;
    private static final int ORANGE = 0xFF6100;

    /** The link's line, from the left edge of the stage. */
    private static final Area LINK = new Area(6.7, 481.3, 184.2, 493.5, 0.05);

    /** Across the upper button, between its rounded ends. */
    private static final Area BUTTONS = new Area(633, 505.6, 728.4, 519.9, 0.2);

    private LoadingError() {
    }

    public static boolean isUp(Frame frame) {
        final var stage = GameStage.of(frame);
        return LINK.holds(frame, stage, RED) && BUTTONS.holds(frame, stage, ORANGE);
    }

    /**
     * @param share how much of the area must be of the colour: about half of what was measured, for
     *              the words of another language
     */
    private record Area(double left, double top, double right, double bottom, double share) {

        boolean holds(Frame frame, GameStage stage, int rgb) {
            final var x0 = Math.max(stage.x(left), 0);
            final var y0 = Math.max(stage.y(top), 0);
            final var x1 = Math.min(stage.x(right), frame.width());
            final var y1 = Math.min(stage.y(bottom), frame.height());
            if (x0 >= x1 || y0 >= y1) {
                return false;
            }
            return frame.region(x0, y0, x1, y1)
                    .filter(pixels -> {
                        var matching = 0;
                        for (final var pixel : pixels.pixels()) {
                            if (pixel == rgb) {
                                matching++;
                            }
                        }
                        return matching >= share * pixels.pixels().length;
                    })
                    .isPresent();
        }
    }
}
