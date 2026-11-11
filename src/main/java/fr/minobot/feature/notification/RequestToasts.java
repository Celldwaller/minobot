package fr.minobot.feature.notification;

import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Request;

/**
 * Recognises the game's request toasts — {@code Bravo t'invite à rejoindre son groupe.}, {@code Bravo te
 * propose de faire un échange.}, {@code Bravo te défie.} — so the notification auto-focus leaves them
 * alone.
 *
 * <p>The toast reports nothing: a request is read off the screen and heard from {@link Requests}, because
 * the toast goes deaf on a window the multi-click has posted to and the box does not. But the toast is
 * still raised, and the request features already answer it — a jump on the toast would undo a trade
 * accepted in place, or be a second jump.
 */
public final class RequestToasts {

    private RequestToasts() {
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
