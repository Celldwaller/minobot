package fr.minobot.feature.notification;

import fr.minobot.core.FightTurns;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Notification;

import java.util.List;
import java.util.Locale;

/**
 * Recognises the game's turn toast — {@code C'est à "Bravo" de jouer} — so the notification auto-focus
 * leaves it alone.
 *
 * <p>The toast reports nothing: a turn is read off the screen and heard from {@link FightTurns}, because
 * the toast goes deaf on a window the multi-click has posted to and the banner does not. But the toast is
 * still raised, and the turn auto-focus already answers its turn — a jump on the toast would be a second.
 */
public final class TurnToasts {

    /**
     * What the turn toast says, the character taken from the title the way every game toast is written.
     * A list so the game's other languages can be added without touching the logic.
     */
    private static final List<String> TURN_KEYWORDS = List.of("jouer");

    private TurnToasts() {
    }

    /** Whether a toast is the game announcing a turn — pure, so the auto-focus can consult it. */
    public static boolean claims(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title) || notification.message() == null) {
            return false;
        }
        final var text = notification.message().toLowerCase(Locale.ROOT);
        return TURN_KEYWORDS.stream().anyMatch(text::contains);
    }
}
