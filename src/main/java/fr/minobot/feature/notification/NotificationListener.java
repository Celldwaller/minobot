package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.NotificationManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;
import fr.minobot.i18n.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Takes the player to the character who needs them — the counterpart of {@code notification_listener.py}.
 *
 * <p>The game raises a Windows toast whenever a background character is attacked, messaged or invited.
 * This turns that toast into a move: the character who called out gets the screen, without the player
 * having to look for them.
 *
 * <p>It is a move that <em>yields</em>: it never takes the screen from under a player who is in the
 * middle of something, and never from under one of the scripted sequences.
 *
 * <p>And it <em>stands aside</em> for a toast another feature answers. A whisper becomes a card at the
 * edge of the game; a turn or a request is answered by the turn and request auto-focus, which hear of
 * it from the screen too, and a second jump here would be one too many. The predicate is how it knows to
 * leave those alone; every other toast, an attack, it still takes the player to.
 */
public final class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final WindowManager windows;
    private final FocusManager focus;
    private final ActivityLog activity;

    /** Whether a toast is being handled silently elsewhere, in which case this listener does not focus. */
    private final Predicate<Notification> handledSilently;

    public NotificationListener(WindowManager windows, FocusManager focus,
                                NotificationManager notifications, ActivityLog activity) {
        this(windows, focus, notifications, activity, _ -> false);
    }

    public NotificationListener(WindowManager windows, FocusManager focus,
                                NotificationManager notifications, ActivityLog activity,
                                Predicate<Notification> handledSilently) {
        this.windows = windows;
        this.focus = focus;
        this.activity = activity;
        this.handledSilently = handledSilently;

        notifications.register(this::onNotification);
    }

    /**
     * Runs on a virtual thread, one per notification, from {@link NotificationManager}.
     *
     * <p>Public where the other toast features keep theirs package-private: the auto-focus is the one
     * every other feature is measured <em>against</em> — the invitation relay in another package must
     * prove a toast does not pull the screen from under its keystrokes — so its handler is driven
     * directly from those features' own tests.
     */
    public void onNotification(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title)) {
            return; // some other application's toast
        }

        if (handledSilently.test(notification)) {
            log.debug("Leaving '{}' to a feature that answers it in place.", title);
            return; // e.g. an exchange between two of our own characters: accepted without a jump
        }

        log.debug("The game is calling out: {} - {}", title, notification.message());

        final var character = GameWindow.nameIn(title);
        if (character.isBlank()) {
            log.warn("Could not tell which character '{}' is about.", title);
            return;
        }

        windows.findWindow(character).ifPresentOrElse(
                window -> goTo(window, notification.message()),
                () -> log.warn("No window found for the character '{}'.", character));
    }

    /**
     * Hands the screen to the character, unless someone else is using it.
     *
     * <p>A toast is a convenience, and the group invitation relay waits on these very toasts: taking
     * the screen in the middle of one would send the rest of its {@code /invite} to this character's
     * window.
     *
     * <p>Only a move that actually happened is noted: one that stood aside moved nobody, and a panel
     * claiming otherwise would be a panel the player stops believing.
     */
    private void goTo(GameWindow character, String reason) {
        if (focus.focusIfIdle(character.hwnd())) {
            activity.record(Text.of("activity.switched", character.name()), Text.verbatim(reason));
        }
    }
}
