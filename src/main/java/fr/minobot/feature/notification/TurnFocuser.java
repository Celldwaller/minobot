package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;

/**
 * Takes the player to the character whose turn it is — the auto-focus, for a turn.
 *
 * <p>It used to be the turn toast that did this, through the notification auto-focus. But a window the
 * multi-click has posted to goes deaf to toasts, and its turns then came and went with nobody called.
 * It listens to {@link FightTurns} instead, which hears of the turn from the toast <em>or</em> from the
 * banner on the screen.
 *
 * <p>It yields like every auto-focus ({@link Summoner}), so never from under auto-pass, which holds the
 * foreground while it ends the turn. A monster's turn, or another player's character's, names nobody with
 * a window here, and moves nothing.
 */
public final class TurnFocuser {

    private final WindowManager windows;
    private final Summoner summoner;

    public TurnFocuser(WindowManager windows, FocusManager focus, FightTurns turns, ActivityLog activity) {
        this.windows = windows;
        this.summoner = new Summoner(windows, focus, activity);

        turns.onTurn(this::onTurn);
    }

    void onTurn(String fighter) {
        windows.windowOf(fighter).ifPresent(character -> summoner.summon(character, "their turn"));
    }
}
