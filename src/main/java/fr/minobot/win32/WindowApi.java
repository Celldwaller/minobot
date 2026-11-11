package fr.minobot.win32;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Every Win32 call the application makes, behind one interface.
 *
 * <p>Nothing outside this package may call {@code user32.dll} directly: the core and the features
 * take a {@code WindowApi}, so they can be driven by a fake in tests and run on any OS. The real
 * implementation is {@link User32}.
 *
 * <p>Window handles are plain {@code long}s (see {@link Win32#NULL_HANDLE} for the absent one),
 * which is what an {@code HWND} is on the Win64 ABI.
 */
public interface WindowApi {

    /** Every top-level window, in the Z-order that {@code EnumWindows} walks. */
    List<Long> topLevelWindows();

    /** The window's title, or the empty string if it has none or the handle is stale. */
    String windowText(long hwnd);

    boolean isWindowVisible(long hwnd);

    /**
     * The full path of the executable behind the window's process, or the empty string when it cannot
     * be read — a stale handle, or a process the caller may not open.
     *
     * <p>This is what tells a game window from anything else, the character's name aside. A title can
     * lie both ways: a login screen carries no character name yet ({@code "Dofus Retro v1.48.18"}), and
     * a browser tab or a wiki page merely mentions the game. The process behind the window does neither
     * — every game window, named or not, runs {@code Dofus Retro.exe}, and nothing else does.
     */
    String executablePath(long hwnd);

    /** Whether the window is minimized. */
    boolean isIconic(long hwnd);

    /** Whether the handle still designates an existing window. */
    boolean isWindow(long hwnd);

    /**
     * @param command one of {@link Win32#SW_HIDE}, {@link Win32#SW_SHOW}, {@link Win32#SW_MAXIMIZE},
     *                {@link Win32#SW_RESTORE}
     */
    void showWindow(long hwnd, int command);

    /**
     * Makes the window the foreground one.
     *
     * @return whether Windows accepted — it refuses when the caller does not own the foreground,
     *         which is what the ALT-press trick in the focus manager works around
     */
    boolean setForegroundWindow(long hwnd);

    void bringWindowToTop(long hwnd);

    /** The foreground window, or {@link Win32#NULL_HANDLE} when no window has the focus. */
    long foregroundWindow();

    /**
     * Posts a message to the window's queue without waiting for it to be handled.
     *
     * @param lparam for mouse messages, pack the coordinates with {@link Win32#makeLParam}
     */
    boolean postMessage(long hwnd, int message, long wparam, long lparam);

    /** Converts screen coordinates into the window's client coordinates, empty if the handle is stale. */
    Optional<Point> screenToClient(long hwnd, Point screen);

    /** Converts the window's client coordinates into screen coordinates, empty if the handle is stale. */
    Optional<Point> clientToScreen(long hwnd, Point client);

    /**
     * The window's <em>client</em> area, in screen coordinates — empty if the handle is stale.
     *
     * <p>The game, and nothing but the game: not the title bar Windows draws above it, nor the border
     * around it. That is what the overlay covers, and it is why this is the client area rather than the
     * window's bounds — the panel must not sit over the minimize, maximize and close buttons.
     */
    Optional<Rect> clientArea(long hwnd);

    /**
     * The window's client area as it is drawn right now — empty if the handle is stale, the window is
     * minimized, or Windows refused the capture.
     *
     * <p>It works on a window <em>behind</em> others and never touches the focus: the window is asked to
     * paint itself into memory ({@code PrintWindow}), the screen is not read. That is what lets Minobot
     * look at a background character without bringing them up — and why a minimized window, which
     * paints nothing, cannot be captured.
     */
    Optional<Capture> capture(long hwnd);

    /**
     * The window under the given screen point — possibly a child window, hence
     * {@link #parentWindow(long)} to walk back up to the game window.
     */
    long windowFromPoint(Point screen);

    /** The window's parent, or {@link Win32#NULL_HANDLE} for a top-level window. */
    long parentWindow(long hwnd);

    /** Whether the key is held down right now, polled rather than queued ({@code GetAsyncKeyState}). */
    boolean isKeyDown(int virtualKey);

    /** The mouse cursor's screen position. */
    Optional<Point> cursorPosition();

    /** The monitor the window sits on — two windows share a monitor iff this returns the same handle. */
    long monitorFromWindow(long hwnd);

    /**
     * Clears the orange "wants your attention" state of a taskbar button, without touching the focus.
     *
     * <p>A no-op while the button is still blinking: it clears what the blinking leaves behind.
     */
    void stopFlashing(long hwnd);

    /**
     * How many times Windows flashes a taskbar button when it refuses an application the foreground.
     *
     * <p>Empty when the setting cannot be read, which is a reason to change nothing.
     */
    OptionalInt foregroundFlashCount();

    /**
     * Sets that count for the whole user session — {@code 0} to stop the flashing altogether.
     *
     * @return whether Windows accepted the change
     */
    boolean setForegroundFlashCount(int count);
}
