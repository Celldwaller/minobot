package fr.minobot.core.vision;

import fr.minobot.win32.Frame;

/**
 * Where the game's own picture sits inside a frame of its window.
 *
 * <p>The game draws a fixed {@value #WIDTH}x{@value #HEIGHT} stage, scaled uniformly to fit the window
 * and centred between black bars — on a wide screen, bars left and right. Every position of the game's
 * interface is therefore written once, in stage coordinates, and lands on the same spot of the interface
 * whatever the window's size: measured on a 2560x1369 window, the stage was 1827 pixels wide from x = 366.
 */
record GameStage(double scale, double left, double top) {

    static final int WIDTH = 742;
    static final int HEIGHT = 556;

    static GameStage of(Frame frame) {
        return of(frame.width(), frame.height());
    }

    /** The stage of a client area this wide and high — a window's, when there is no frame of it at hand. */
    static GameStage of(int width, int height) {
        final var scale = Math.min((double) width / WIDTH, (double) height / HEIGHT);
        return new GameStage(scale, (width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2);
    }

    /** The frame column of a stage abscissa. */
    int x(double stageX) {
        return (int) Math.round(left + stageX * scale);
    }

    /** The frame row of a stage ordinate. */
    int y(double stageY) {
        return (int) Math.round(top + stageY * scale);
    }

    /** The stage abscissa of a frame column. */
    double stageX(double frameX) {
        return (frameX - left) / scale;
    }

    /** The stage ordinate of a frame row. */
    double stageY(double frameY) {
        return (frameY - top) / scale;
    }

    /** A stage length, in frame pixels. */
    int length(double stageLength) {
        return (int) Math.round(stageLength * scale);
    }
}
