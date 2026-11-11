package fr.minobot.core.vision;

import fr.minobot.win32.Capture;

import java.util.Arrays;

/**
 * Captures with the turn banner painted where the game draws it — the strip measured on a real 2560x1369
 * capture, at x 735..1142 and y 94..129.
 */
final class BannerCaptures {

    static final int BEIGE = 0xD5CFAA;
    static final int INK = 0x000000;

    /** A black 2560x1369 window with the banner's strip up, and a block of ink where the name begins. */
    static Capture banner() {
        final var capture = blank(2560, 1369);
        paint(capture, 735, 94, 1143, 130, BEIGE);
        paint(capture, 746, 100, 760, 121, INK);
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
