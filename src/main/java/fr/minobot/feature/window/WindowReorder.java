package fr.minobot.feature.window;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts the characters back in order in the taskbar — the counterpart of {@code window_reorder.py}.
 *
 * <p>The player wants their taskbar buttons in the player's character order, and Windows
 * offers no way to move a button: the only lever is the order in which the windows appear. So the
 * characters all leave the screen, and come back one by one in the order they should be in.
 *
 * <p>They are <em>gone</em> in the middle of this: a sequence that dies there leaves the player with no
 * characters and no way to get them back. Every failure path therefore ends in
 * {@link #bringEverybodyBack()}.
 */
public final class WindowReorder {

    private static final Logger log = LoggerFactory.getLogger(WindowReorder.class);

    /** Time the taskbar needs to notice the buttons are gone before we start adding them back. */
    private static final int TASKBAR_SETTLE_MILLIS = 500;

    /** Without this pause, Windows batches the windows and the order comes out arbitrary. */
    private static final int BETWEEN_WINDOWS_MILLIS = 100;

    private final WindowApi api;
    private final WindowManager windows;
    private final FocusManager focus;
    private final ActivityLog activity;

    private final AtomicBoolean running = new AtomicBoolean();

    public WindowReorder(WindowApi api, WindowManager windows, FocusManager focus, ActivityLog activity) {
        this.api = api;
        this.windows = windows;
        this.focus = focus;
        this.activity = activity;
    }

    /** Takes every character off the screen, then brings them back in the configured order. */
    public void reorderTaskbar() {
        if (!running.compareAndSet(false, true)) {
            log.warn("The window reorder sequence is already running.");
            return;
        }

        // The characters are off the screen in the middle of this: a toast focusing one of them here
        // would be focusing a window that is nowhere to be seen.
        try (final var _ = focus.takeOver()) {
            windows.refresh();
            final var characters = windows.orderedWindows();
            if (characters.isEmpty()) {
                log.warn("No character to reorder.");
                return;
            }

            log.info("Reordering {} character(s) to the configured order.", characters.size());

            takeThemOffScreen(characters);
            Thread.sleep(TASKBAR_SETTLE_MILLIS);
            bringThemBackInOrder(characters);

            final var leader = characters.getFirst();
            if (api.isWindow(leader.hwnd())) {
                log.info("Handing the screen to the first character: '{}'.", leader.name());
                focus.focus(leader.hwnd());
            }

            log.info("Taskbar reorder complete.");
            activity.record(Text.of("activity.taskbar_reordered"), Text.of("activity.characters", characters.size()));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("The reorder sequence was interrupted; bringing the characters back.");
            bringEverybodyBack();
        } catch (RuntimeException e) {
            log.error("Error during the reorder sequence; bringing the characters back.", e);
            bringEverybodyBack();
        } finally {
            running.set(false);
        }
    }

    private void takeThemOffScreen(List<GameWindow> characters) {
        for (final var character : characters) {
            if (api.isWindow(character.hwnd())) {
                api.showWindow(character.hwnd(), Win32.SW_HIDE);
            }
        }
    }

    /** One by one: the taskbar builds its buttons in the order the windows come back. */
    private void bringThemBackInOrder(List<GameWindow> characters) throws InterruptedException {
        for (final var character : characters) {
            if (!api.isWindow(character.hwnd())) {
                continue;
            }

            api.showWindow(character.hwnd(), Win32.SW_SHOW);

            // The player wants their characters enlarged as well as back in order. SW_MAXIMIZE also
            // un-minimizes — which the taskbar needs anyway, a window left iconic comes back hidden
            // from it — so it subsumes the SW_RESTORE this used to do. Maximizing a window already
            // maximized is a no-op, which is the "if it is not already" the player asked for.
            api.showWindow(character.hwnd(), Win32.SW_MAXIMIZE);

            Thread.sleep(BETWEEN_WINDOWS_MILLIS);
        }
    }

    /** Last resort: a character left off the screen is a character the player cannot get back. */
    private void bringEverybodyBack() {
        for (final var character : windows.windows()) {
            if (api.isWindow(character.hwnd())) {
                api.showWindow(character.hwnd(), Win32.SW_SHOW);
            }
        }
    }
}
