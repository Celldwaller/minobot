package fr.minobot.core.input;

import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;

import java.time.Duration;
import java.util.List;

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
        return chord(hwnd, List.of(new Key(virtualKey, scanCode)));
    }

    /**
     * Presses the key with {@code CONTROL} held, as {@link #press} does the key alone. Must be called
     * from a platform thread (see {@link WindowApi#whileKeyHeld}).
     *
     * @return whether the window's {@code CONTROL} was held, the window told it is active and the keys
     *         posted to it
     */
    public boolean pressWithControl(long hwnd, int virtualKey, int scanCode) {
        return api.whileKeyHeld(hwnd, Win32.VK_CONTROL, () -> chord(hwnd,
                List.of(new Key(Win32.VK_CONTROL, Win32.SCAN_CONTROL), new Key(virtualKey, scanCode))));
    }

    /** The keys pressed in order, and released in the reverse one. */
    private boolean chord(long hwnd, List<Key> keys) {
        if (!api.sendMessage(hwnd, Win32.WM_ACTIVATE, Win32.WA_ACTIVE, 0)) {
            return false;
        }
        try {
            var posted = true;
            for (final var key : keys) {
                posted = posted && api.postMessage(hwnd, Win32.WM_KEYDOWN, key.virtualKey(),
                        Win32.keyLParam(key.scanCode(), false));
            }
            for (final var key : keys.reversed()) {
                posted = posted && api.postMessage(hwnd, Win32.WM_KEYUP, key.virtualKey(),
                        Win32.keyLParam(key.scanCode(), true));
            }
            Thread.sleep(KEY_READ);
            return posted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            api.sendMessage(hwnd, Win32.WM_ACTIVATE, Win32.WA_INACTIVE, 0);
        }
    }

    private record Key(int virtualKey, int scanCode) {
    }
}
