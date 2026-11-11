package fr.minobot.feature.notification;

import fr.minobot.core.FightTurns;
import fr.minobot.core.NotificationManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;

import java.util.List;
import java.util.Locale;

/**
 * Turns the game's turn toast — {@code C'est à "Bravo" de jouer} — into a turn reported to
 * {@link FightTurns}, where the turn banner read off the screen reports too.
 *
 * <p>It answers nothing itself: the features that act on a turn listen to {@link FightTurns}, so a turn
 * seen both as a toast and as a banner is acted on once. That is also why it {@linkplain #claims claims}
 * the toast: the auto-focus must not jump to the character a second time on it.
 */
public final class TurnToasts {

    /**
     * What the turn toast says, the character taken from the title the way every game toast is written.
     * A list so the game's other languages can be added without touching the logic.
     */
    private static final List<String> TURN_KEYWORDS = List.of("jouer");

    private final FightTurns turns;

    public TurnToasts(NotificationManager notifications, FightTurns turns) {
        this.turns = turns;

        notifications.register(this::onNotification);
    }

    /** Runs on a virtual thread, one per notification, from {@link NotificationManager}. */
    void onNotification(Notification notification) {
        if (claims(notification)) {
            turns.report(GameWindow.nameIn(notification.title()));
        }
    }

    /** Whether a toast is the game announcing a turn — pure, so the auto-focus can consult it. */
    public boolean claims(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title)) {
            return false;
        }
        final var text = notification.message().toLowerCase(Locale.ROOT);
        return TURN_KEYWORDS.stream().anyMatch(text::contains);
    }
}
