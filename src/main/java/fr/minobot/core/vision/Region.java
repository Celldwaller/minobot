package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.Frame;

import java.util.Optional;

/**
 * The one part of a frame a reader looks at, copied out once and read in the frame's own coordinates.
 *
 * <p>Once, because a frame's region may be a copy off the GPU: a reader takes the smallest rectangle that
 * holds everything it checks and reads, and checks and reads within it.
 */
record Region(Capture pixels, int left, int top) {

    static Optional<Region> of(Frame frame, int left, int top, int right, int bottom) {
        return frame.region(left, top, right, bottom).map(pixels -> new Region(pixels, left, top));
    }

    /** Whether the pixel at this frame coordinate is in the region, and of that colour. */
    boolean is(int x, int y, int rgb) {
        final var inX = x - left;
        final var inY = y - top;
        return inX >= 0 && inY >= 0 && inX < pixels.width() && inY < pixels.height() && pixels.rgb(inX, inY) == rgb;
    }

    /** The part between these frame coordinates — right and bottom exclusive. */
    Capture crop(int left, int top, int right, int bottom) {
        return pixels.crop(left - this.left, top - this.top, right - this.left, bottom - this.top);
    }
}
