package fr.minobot.feature.notification;

import fr.minobot.core.NotificationManager;
import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Request;

import java.time.Duration;

/**
 * Takes the game's request toasts — {@code Bravo t'invite à rejoindre son groupe.}, {@code Bravo te
 * propose de faire un échange.}, {@code Bravo te défie.} — as a hint of where to look, and nothing more.
 *
 * <p>The toast reports nothing: a request is read off the screen and heard from {@link Requests}, because
 * the toast goes deaf on a window the multi-click has posted to and the box does not. But a toast that does
 * come comes at once, and names the character asked, while the screen watcher's round may reach that
 * window two seconds later with eight characters. So it points the watcher there
 * ({@link Requests#expect}); a toast that never comes leaves the box to the round.
 *
 * <p>The request features already answer what the toast announces — a jump on it would undo a trade
 * accepted in place, or be a second jump — so it {@linkplain #claims claims} it from the auto-focus.
 */
public final class RequestToasts {

    /** The box is drawn within a second of its toast; past this, the round is the better bet. */
    static final Duration BOX_FOLLOWS_TOAST = Duration.ofSeconds(2);

    private final WindowManager windows;
    private final Requests requests;

    public RequestToasts(WindowManager windows, NotificationManager notifications, Requests requests) {
        this.windows = windows;
        this.requests = requests;

        notifications.register(this::onNotification);
    }

    /** Runs on a virtual thread, one per notification, from {@link NotificationManager}. */
    void onNotification(Notification notification) {
        if (claims(notification)) {
            windows.windowOf(GameWindow.nameIn(notification.title()))
                    .ifPresent(receiver -> requests.expect(receiver, BOX_FOLLOWS_TOAST));
        }
    }

    /** Whether a toast is a request — pure, so the auto-focus can consult it. */
    public static boolean claims(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title) || notification.message() == null) {
            return false;
        }
        return Request.in(notification.message()).isPresent();
    }
}
