package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.input.BackgroundKeys;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Closes the end-of-fight screen the game opens in every character's window, but one's.
 *
 * <p>It hears of the fight's end from the {@link FightLog}: the chat's {@code Combat terminé} line. The
 * screen is left open in the window the player reads it in — the main character's, the first of their
 * order, unless it is minimized; then the one on screen. A minimized window keeps its screen too: what
 * an activation does to a window that is not drawn was never seen.
 *
 * <p>The game closes the screen on {@code ESCAPE}, pressed in every window at once, in the background
 * ({@link BackgroundKeys}): no window takes the screen.
 */
public final class ResultsCloser {

    private static final Logger log = LoggerFactory.getLogger(ResultsCloser.class);

    /** The chat writes its line as the screen opens: a key pressed before the screen is drawn is lost. */
    private static final Duration SETTLE = Duration.ofSeconds(1);

    private final WindowApi api;
    private final BackgroundKeys keys;
    private final WindowManager windows;
    private final ActivityLog activity;

    public ResultsCloser(WindowApi api, WindowManager windows, FightLog fightLog, ActivityLog activity) {
        this.api = api;
        this.keys = new BackgroundKeys(api);
        this.windows = windows;
        this.activity = activity;

        // The fight log tells on its reading thread, which must not wait out the screen's opening.
        fightLog.onFightEnd(() -> Thread.ofVirtual().name("results-closer").start(this::afterSettling));
    }

    private void afterSettling() {
        try {
            Thread.sleep(SETTLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        closeResults();
    }

    /** Closes the screen in every window but the one the player reads it in. */
    void closeResults() {
        final var ordered = windows.orderedWindows();
        final var kept = ordered.stream().findFirst()
                .filter(main -> !api.isIconic(main.hwnd()))
                .map(GameWindow::hwnd)
                .orElseGet(api::foregroundWindow);

        final var closed = new CopyOnWriteArrayList<String>();
        final var presses = ordered.stream()
                .filter(window -> window.hwnd() != kept && !api.isIconic(window.hwnd()))
                .map(window -> Thread.ofVirtual().name("results-closer-" + window.name()).start(() -> {
                    if (keys.press(window.hwnd(), Win32.VK_ESCAPE, Win32.SCAN_ESCAPE)) {
                        closed.add(window.name());
                    } else {
                        log.warn("Could not press ESCAPE in '{}': its end-of-fight screen was left open.", window.name());
                    }
                }))
                .toList();
        if (!joinAll(presses) || closed.isEmpty()) {
            return;
        }
        log.info("Closed the end-of-fight screen of {}.", closed);
        activity.record(Text.of("activity.results_closed"), Text.of("activity.characters", closed.size()));
    }

    /** @return whether every press finished; {@code false} means this thread was interrupted waiting */
    private static boolean joinAll(List<Thread> presses) {
        try {
            for (final var press : presses) {
                press.join();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
