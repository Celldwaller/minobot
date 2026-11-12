package fr.minobot.win32;

import java.util.Optional;

/**
 * What a window's client area showed at one instant, read a region at a time.
 *
 * <p>A {@link Capture} is one, held whole in memory. The compositor's frames ({@link GraphicsCapture})
 * stay on the GPU, and only the regions asked for are copied out: the readers of the screen want a strip
 * and a box, a few hundred kilobytes, where the whole of a 2560 px window is fourteen megabytes.
 *
 * <p>A frame from {@link WindowApi#frame} is read before the next one of the same window is asked for:
 * the compositor's moves on to the newer, and an older one then reads nothing.
 */
public interface Frame {

    int width();

    int height();

    /**
     * The pixels between these edges — right and bottom exclusive, as in a {@link Rect}.
     *
     * @return empty when they could not be read — which is a reason to read nothing into the frame
     * @throws IndexOutOfBoundsException if the edges are not inside the frame
     */
    Optional<Capture> region(int left, int top, int right, int bottom);
}
