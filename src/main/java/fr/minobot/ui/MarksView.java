package fr.minobot.ui;

import fr.minobot.win32.Rect;

/**
 * The spell marks over the game — another door to the outside world beside {@link BannerView} and the
 * others, and kept just as narrow.
 *
 * <p>It is drawn over the whole of the game and takes none of it: what is not a mark lets the mouse
 * through to the game below, and nothing in it is clicked. Whether it is up, and what is on it, is the
 * controller's to decide.
 */
public interface MarksView {

    /**
     * Draws the marks, or redraws them in place if they are already up.
     *
     * @param anchor the character's client area in screen coordinates; the marks are in its coordinates
     */
    void show(MarksContent content, Rect anchor);

    void hide();

    boolean isVisible();
}
