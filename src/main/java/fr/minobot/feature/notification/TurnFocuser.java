package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;

/**
 * Takes the player to the character whose turn it is — the auto-focus, for a turn.
 *
 * <p>It used to be the turn toast that did this, through the notification auto-focus. But a window the
 * multi-click has posted to goes deaf to toasts, and its turns then came and went with nobody called.
 * It listens to {@link FightTurns} instead, which hears of the turn from the toast <em>or</em> from the
 * banner on the screen.
 *
 * <p>It yields like the auto-focus ({@link FocusManager#focusIfIdle}): never from under a player who is
 * busy, never from under auto-pass, which holds the foreground while it ends the turn. A monster's turn,
 * or another player's character's, names nobody with a window here, and moves nothing.
 */
public final class TurnFocuser {

    private final WindowManager windows;
    private final FocusManager focus;
    private final ActivityLog activity;

    public TurnFocuser(WindowManager windows, FocusManager focus, FightTurns turns, ActivityLog activity) {
        this.windows = windows;
        this.focus = focus;
        this.activity = activity;

        turns.onTurn(this::onTurn);
    }

    void onTurn(String fighter) {
        windows.windowOf(fighter)
                .filter(character -> !isInFront(character))
                .ifPresent(this::goTo);
    }

    private boolean isInFront(GameWindow character) {
        return windows.foregroundGameWindow().filter(front -> front.hwnd() == character.hwnd()).isPresent();
    }

    /** Only a move that happened is noted, as the auto-focus does. */
    private void goTo(GameWindow character) {
        if (focus.focusIfIdle(character.hwnd())) {
            activity.record("Switched to " + character.name(), "their turn");
        }
    }
}
