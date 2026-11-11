package fr.minobot.core.vision;

import fr.minobot.win32.Capture;

import java.util.Arrays;

/**
 * Captures with the game's interface painted where the game draws it on a real 2560x1369 window: the turn
 * banner's strip at x 735..1142, y 94..129; the request box's title bar at x 862..1698, y 358..411 and its
 * body below, down to y 705.
 */
final class BannerCaptures {

    static final int BEIGE = 0xD5CFAA;
    static final int INK = 0x000000;
    static final int TITLE_BAR = 0x514A3C;

    /** A black 2560x1369 window with the banner's strip up, and a block of ink where the name begins. */
    static Capture banner() {
        final var capture = blank(2560, 1369);
        paint(capture, 735, 94, 1143, 130, BEIGE);
        paint(capture, 746, 100, 760, 121, INK);
        return capture;
    }

    /** A black 2560x1369 window with a request box up, and a block of ink where the question is. */
    static Capture requestBox() {
        final var capture = blank(2560, 1369);
        paint(capture, 862, 358, 1699, 412, TITLE_BAR);
        paint(capture, 862, 412, 1699, 706, BEIGE);
        paint(capture, 970, 450, 1590, 515, INK);
        return capture;
    }

    static Capture blank(int width, int height) {
        return new Capture(width, height, new int[width * height]);
    }

    static void paint(Capture capture, int left, int top, int right, int bottom, int rgb) {
        for (var y = top; y < bottom; y++) {
            Arrays.fill(capture.pixels(), y * capture.width() + left, y * capture.width() + right, rgb);
        }
    }

    private BannerCaptures() {
    }
}
