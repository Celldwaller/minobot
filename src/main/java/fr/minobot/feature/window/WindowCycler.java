package fr.minobot.feature.window;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.i18n.Text;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Moves the player from one character to the next — the counterpart of {@code window_cycler.py}.
 *
 * <p>The characters form a ring, in the player's character order, and the two hotkeys walk
 * it in either direction. Only the characters on the <em>current monitor</em> are part of the ring, so
 * on a two-screen setup each screen cycles within itself — which is what makes this worth having over
 * Alt+Tab.
 */
public final class WindowCycler {

    private static final Logger log = LoggerFactory.getLogger(WindowCycler.class);

    private final WindowApi api;
    private final WindowManager windows;
    private final FocusManager focus;
    private final ActivityLog activity;

    public WindowCycler(WindowApi api, WindowManager windows, FocusManager focus, ActivityLog activity) {
        this.api = api;
        this.windows = windows;
        this.focus = focus;
        this.activity = activity;
    }

    public void cycleNext() {
        cycle(Direction.NEXT);
    }

    public void cyclePrev() {
        cycle(Direction.PREVIOUS);
    }

    /** Hands the screen to the character sitting next to the one being played, in that direction. */
    private void cycle(Direction direction) {
        final var ring = windows.windowsOnCurrentMonitor();
        if (ring.isEmpty()) {
            log.debug("No character to cycle through on this monitor.");
            return;
        }

        final var played = indexOf(ring, api.foregroundWindow());
        final var character = ring.get(direction.neighbourOf(played, ring.size()));

        log.debug("Cycling {} to '{}'.", direction, character.name());
        if (focus.focus(character.hwnd())) {
            activity.record(Text.of("activity.switched", character.name()), direction.said());
        }
    }

    /** @return where the played character sits in the ring, or {@code -1} if the player is elsewhere */
    private static int indexOf(List<GameWindow> ring, long hwnd) {
        for (int i = 0; i < ring.size(); i++) {
            if (ring.get(i).hwnd() == hwnd) {
                return i;
            }
        }
        return -1;
    }

    /** Which way round the ring a hotkey walks. */
    private enum Direction {

        NEXT(1),
        PREVIOUS(-1);

        private final int step;

        Direction(int step) {
            this.step = step;
        }

        /**
         * The character this direction leads to.
         *
         * @param played where the player is in the ring, or {@code -1} when they are on something else
         *               entirely (a browser, the desktop): the ring is then entered at the end it is
         *               being walked towards, rather than skipping the first character.
         */
        int neighbourOf(int played, int size) {
            if (played < 0) {
                return this == NEXT ? 0 : size - 1;
            }
            return Math.floorMod(played + step, size);
        }

        /** How the activity list tells which way the player went. */
        Text said() {
            return Text.of(this == NEXT ? "activity.next" : "activity.previous");
        }

        @Override
        public String toString() {
            return this == NEXT ? "next" : "previous";
        }
    }
}
