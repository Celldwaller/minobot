package fr.minobot.win32;

import java.util.Arrays;

/**
 * What a window's client area looked like at one instant: its pixels, row by row from the top-left,
 * each an opaque {@code 0xRRGGBB}.
 *
 * <p>The array is the record's own and is never copied, so equality is identity: a capture is a
 * snapshot to read pixels from, not a value to compare.
 */
public record Capture(int width, int height, int[] pixels) {

    public Capture {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "A " + width + "x" + height + " capture cannot hold " + pixels.length + " pixels");
        }
    }

    /** The colour at a client coordinate, as {@code 0xRRGGBB}. */
    public int rgb(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") is outside a " + width + "x" + height + " capture");
        }
        return pixels[y * width + x];
    }

    /** The part of the capture between these edges — right and bottom exclusive, as in a {@link Rect}. */
    public Capture crop(int left, int top, int right, int bottom) {
        if (left < 0 || top < 0 || right > width || bottom > height || left >= right || top >= bottom) {
            throw new IndexOutOfBoundsException(
                    "[" + left + ", " + top + ", " + right + ", " + bottom + ") is not inside a " + width + "x" + height + " capture");
        }
        final var cropped = new int[(right - left) * (bottom - top)];
        for (var y = top; y < bottom; y++) {
            System.arraycopy(pixels, y * width + left, cropped, (y - top) * (right - left), right - left);
        }
        return new Capture(right - left, bottom - top, cropped);
    }

    /** The capture framed by a border of one colour, {@code margin} pixels wide on every side. */
    public Capture padded(int margin, int rgb) {
        final var paddedWidth = width + 2 * margin;
        final var padded = new int[paddedWidth * (height + 2 * margin)];
        Arrays.fill(padded, rgb);
        for (var y = 0; y < height; y++) {
            System.arraycopy(pixels, y * width, padded, (y + margin) * paddedWidth + margin, width);
        }
        return new Capture(paddedWidth, height + 2 * margin, padded);
    }

    /**
     * Whether every pixel is the same colour.
     *
     * <p>That is what a capture Windows could not really take looks like: a window that draws through
     * the GPU and was not asked to render its full content comes back uniformly black, not as an error.
     */
    public boolean isUniform() {
        final var first = pixels[0];
        for (final var pixel : pixels) {
            if (pixel != first) {
                return false;
            }
        }
        return true;
    }
}
