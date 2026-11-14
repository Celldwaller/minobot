package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.i18n.Text;

/**
 * Brings up a character the game is calling for — their turn, a request — the way every auto-focus does.
 *
 * <p>It yields ({@link FocusManager#focusIfIdle}): never from under a player who is busy, never from
 * under a feature holding the foreground. A character already in front is left as they are, and only a
 * move that happened is noted: a panel claiming moves that did not happen is a panel the player stops
 * believing.
 */
final class Summoner {

    private final WindowManager windows;
    private final FocusManager focus;
    private final ActivityLog activity;

    Summoner(WindowManager windows, FocusManager focus, ActivityLog activity) {
        this.windows = windows;
        this.focus = focus;
        this.activity = activity;
    }

    void summon(GameWindow character, Text reason) {
        if (isInFront(character)) {
            return;
        }
        if (focus.focusIfIdle(character.hwnd())) {
            activity.record(Text.of("activity.switched", character.name()), reason);
        }
    }

    private boolean isInFront(GameWindow character) {
        return windows.foregroundGameWindow().filter(front -> front.hwnd() == character.hwnd()).isPresent();
    }
}
