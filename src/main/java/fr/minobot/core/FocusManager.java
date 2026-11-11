package fr.minobot.core;

import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.input.Input;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Brings a game window to the foreground — the counterpart of {@code focus_manager.py}.
 *
 * <p>Windows refuses {@code SetForegroundWindow} to a process that does not already own the
 * foreground. The workaround, is to press ALT first: it makes the
 * OS treat the call as user-driven. It is timing- and policy-dependent, which is why it is the first
 * thing to check against the real game.
 *
 * <p>There are two kinds of focus here, and the difference matters. A <em>deliberate</em>
 * {@link #focus(long)} comes from a hotkey: it waits its turn and it reports whether it worked, so a
 * caller that is about to type knows whether the keystrokes will land in the window it asked for. An
 * {@link #focusIfIdle(long)} comes from a toast: it is a convenience, and it stands aside rather than
 * take the screen from under a sequence that is halfway through typing a command.
 *
 * <p>Every step sleeps, so this is meant to be called from a virtual thread (a hotkey callback),
 * never from the polling loop.
 */
public final class FocusManager {

    private static final Logger log = LoggerFactory.getLogger(FocusManager.class);

    /**
     * Left to the ALT press before the foreground is asked for. Its cost is paid on every turn focused
     * and passed, and its failure is loud: the "failed on first attempt" warning.
     */
    private static final int ALT_SETTLE_MILLIS = 20;
    private static final int RESTORE_SETTLE_MILLIS = 100;

    /** How long Windows is given to hand the foreground over before the focus is called a failure. */
    private static final Duration FOREGROUND_TIMEOUT = Duration.ofMillis(600);
    /** Asking the OS who holds the foreground is cheap; every poll slept past the handover is not. */
    private static final int FOREGROUND_POLL_MILLIS = 5;

    /**
     * Left to the window once it holds the foreground, before its new owner may type into it.
     *
     * <p>Being the foreground window and being ready to receive a keystroke are not the same instant:
     * the activation messages are still on their way. Too short, and the first keystroke — an auto-pass
     * {@code F1} — is lost without a word.
     */
    private static final int ACTIVATION_MILLIS = 30;

    /** Two toasts landing together — a burst — must not fight over the screen. */
    private static final Duration COOLDOWN = Duration.ofMillis(100);

    private final WindowApi api;
    private final Input input;

    /** One focus at a time: two of them interleaved leave the foreground anywhere at all. */
    private final ReentrantLock foreground = new ReentrantLock();

    /** Scripted sequences in progress: a toast never takes the foreground away from one. */
    private final AtomicInteger sequences = new AtomicInteger();

    private volatile boolean everFocused;
    private volatile long lastFocusNanos;

    public FocusManager(WindowApi api, Input input) {
        this.api = api;
        this.input = input;
    }

    /**
     * The foreground, reserved for a scripted sequence: close it to give it back.
     *
     * <p>Meant for a try-with-resources, so that every path out of the sequence — including a failure —
     * releases it.
     */
    public interface Reservation extends AutoCloseable {

        @Override
        void close();
    }

    /**
     * Reserves the foreground for a sequence that focuses windows and types into them.
     *
     * <p>A sequence owns the screen from end to end. Without this, the notification auto-focus reacts
     * to the very toasts the sequence is waiting for, and its focus lands between two of the
     * sequence's keystrokes — which then go to another character's window.
     */
    public Reservation takeOver() {
        // Under the lock: any focus already in flight finishes first, and every focus started after
        // this returns sees the reservation.
        foreground.lock();
        try {
            sequences.incrementAndGet();
        } finally {
            foreground.unlock();
        }
        return sequences::decrementAndGet;
    }

    /**
     * Brings the window to the foreground and waits until it is really there.
     *
     * @return whether the window holds the foreground and is ready to be typed into
     */
    public boolean focus(long hwnd) {
        foreground.lock();
        try {
            return bringToForeground(hwnd);
        } finally {
            foreground.unlock();
        }
    }

    /**
     * The same focus, but one that yields: it does nothing while a sequence holds the foreground, or
     * while another focus is under way, or in the wake of one.
     *
     * <p>This is what a toast asks for. It does not queue: by the time it got its turn, the character
     * it is about would be old news anyway.
     *
     * @return whether the window was focused, {@code false} if the request stood aside
     */
    public boolean focusIfIdle(long hwnd) {
        if (reserved() || withinCooldown()) {
            log.debug("Focus on HWND {} stands aside: the foreground is busy.", hwnd);
            return false;
        }
        if (!foreground.tryLock()) {
            log.debug("Focus on HWND {} stands aside: another focus is under way.", hwnd);
            return false;
        }

        try {
            // Re-read under the lock: a sequence may have taken over while we were deciding.
            return !reserved() && bringToForeground(hwnd);
        } finally {
            foreground.unlock();
        }
    }

    private boolean reserved() {
        return sequences.get() > 0;
    }

    private boolean withinCooldown() {
        return everFocused && System.nanoTime() - lastFocusNanos < COOLDOWN.toNanos();
    }

    private boolean bringToForeground(long hwnd) {
        final var name = GameWindow.nameIn(api.windowText(hwnd));

        if (api.foregroundWindow() == hwnd) {
            log.debug("'{}' already holds the focus.", name);
            return true;
        }

        log.debug("Focusing '{}' (HWND: {}).", name, hwnd);
        final var start = System.nanoTime();
        try {
            // 1. Wake up the OS's focus logic; without this, step 4 is silently refused.
            input.pressKey(KeyEvent.VK_ALT);
            Thread.sleep(ALT_SETTLE_MILLIS);

            // 2. Un-minimize it, otherwise it comes back to the foreground still iconic.
            if (api.isIconic(hwnd)) {
                api.showWindow(hwnd, Win32.SW_RESTORE);
                Thread.sleep(RESTORE_SETTLE_MILLIS);
            }

            // 3. and 4. Raise it, then give it the focus.
            api.bringWindowToTop(hwnd);
            api.setForegroundWindow(hwnd);

            if (settled(hwnd)) {
                log.debug("Successfully focused '{}' in {} ms.", name, (System.nanoTime() - start) / 1_000_000);
                return true;
            }

            log.warn("Focus on '{}' failed on first attempt, retrying...", name);
            api.showWindow(hwnd, Win32.SW_SHOW);
            api.setForegroundWindow(hwnd);

            if (settled(hwnd)) {
                log.debug("Successfully focused '{}' on second attempt, in {} ms.",
                        name, (System.nanoTime() - start) / 1_000_000);
                return true;
            }

            log.error("Could not focus '{}'.", name);
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.debug("Focus of '{}' interrupted.", name);
            return false;
        } catch (RuntimeException e) {
            log.error("Unexpected error while focusing '{}'.", name, e);
            return false;
        } finally {
            lastFocusNanos = System.nanoTime();
            everFocused = true;
        }
    }

    /**
     * Waits for the window to actually hold the foreground, then for it to be ready to be typed into.
     *
     * <p>{@code SetForegroundWindow} reports a success it did not always achieve, and hands the
     * foreground over asynchronously: the only way to know is to ask the OS until it agrees.
     */
    private boolean settled(long hwnd) throws InterruptedException {
        final var deadline = System.nanoTime() + FOREGROUND_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (api.foregroundWindow() == hwnd) {
                Thread.sleep(ACTIVATION_MILLIS);
                return true;
            }
            Thread.sleep(FOREGROUND_POLL_MILLIS);
        }
        return false;
    }
}
