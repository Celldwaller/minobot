package fr.minobot.core.input;

import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;

import java.time.Duration;

/**
 * Presses a key in a game window that does not hold the keyboard, without giving it the screen — what
 * {@link Input} cannot do, since a key it presses goes to the foreground.
 *
 * <p>The game reads a key only while it believes itself active, and from a window that is not, ignores a
 * posted key — alone, sent to its render child, or after a {@code WM_SETFOCUS}. An activation sent to it,
 * without the screen, is enough; but the key reaches the game a moment after it is posted, and an
 * activation taken back at once takes it back too: it is held {@link #KEY_READ}, then taken back whatever
 * came of the key. Measured against the game, on the end-of-fight screen.
 */
public final class BackgroundKeys {

    private static final Duration KEY_READ = Duration.ofMillis(300);

    private final WindowApi api;

    public BackgroundKeys(WindowApi api) {
        this.api = api;
    }

    /**
     * Presses and releases the key, and returns once the game has had the time to read it.
     *
     * @param virtualKey a {@code Win32.VK_*} constant
     * @param scanCode   the same key's {@code Win32.SCAN_*} constant
     * @return whether the window was told it is active and the key posted to it
     */
    public boolean press(long hwnd, int virtualKey, int scanCode) {
        if (!api.sendMessage(hwnd, Win32.WM_ACTIVATE, Win32.WA_ACTIVE, 0)) {
            return false;
        }
        try {
            final var posted = api.postMessage(hwnd, Win32.WM_KEYDOWN, virtualKey, Win32.keyLParam(scanCode, false))
                    && api.postMessage(hwnd, Win32.WM_KEYUP, virtualKey, Win32.keyLParam(scanCode, true));
            Thread.sleep(KEY_READ);
            return posted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            api.sendMessage(hwnd, Win32.WM_ACTIVATE, Win32.WA_INACTIVE, 0);
        }
    }
}
