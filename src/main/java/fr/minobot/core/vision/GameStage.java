package fr.minobot.core.vision;

import fr.minobot.win32.Capture;

/**
 * Where the game's own picture sits inside a capture of its window.
 *
 * <p>The game draws a fixed {@value #WIDTH}x{@value #HEIGHT} stage, scaled uniformly to fit the window
 * and centred between black bars — on a wide screen, bars left and right. Every position of the game's
 * interface is therefore written once, in stage coordinates, and lands on the same spot of the interface
 * whatever the window's size: measured on a 2560x1369 window, the stage was 1827 pixels wide from x = 366.
 */
record GameStage(double scale, double left, double top) {

    static final int WIDTH = 742;
    static final int HEIGHT = 556;

    static GameStage of(Capture capture) {
        final var scale = Math.min((double) capture.width() / WIDTH, (double) capture.height() / HEIGHT);
        return new GameStage(scale,
                (capture.width() - WIDTH * scale) / 2,
                (capture.height() - HEIGHT * scale) / 2);
    }

    /** The capture column of a stage abscissa. */
    int x(double stageX) {
        return (int) Math.round(left + stageX * scale);
    }

    /** The capture row of a stage ordinate. */
    int y(double stageY) {
        return (int) Math.round(top + stageY * scale);
    }

    /** A stage length, in capture pixels. */
    int length(double stageLength) {
        return (int) Math.round(stageLength * scale);
    }
}
