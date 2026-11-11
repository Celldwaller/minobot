package fr.minobot.feature.notification;

import fr.minobot.core.NotificationManager;
import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Request;

import java.util.Optional;

/**
 * Turns the game's request toasts — {@code Bravo t'invite à rejoindre son groupe.}, {@code Bravo te
 * propose de faire un échange.} — into requests reported to {@link Requests}, where the box read off the
 * screen reports too.
 *
 * <p>It answers nothing itself: the features that answer a request listen to {@link Requests}, so a
 * request seen both as a toast and on screen is answered once. That is also why it
 * {@linkplain #claims claims} the toast from the notification auto-focus.
 */
public final class RequestToasts {

    private final WindowManager windows;
    private final Requests requests;

    public RequestToasts(WindowManager windows, NotificationManager notifications, Requests requests) {
        this.windows = windows;
        this.requests = requests;

        notifications.register(this::onNotification);
    }

    /** Runs on a virtual thread, one per notification, from {@link NotificationManager}. */
    void onNotification(Notification notification) {
        request(notification).ifPresent(request ->
                windows.windowOf(GameWindow.nameIn(notification.title()))
                        .ifPresent(receiver -> requests.report(receiver, request)));
    }

    /** Whether a toast is a request — pure, so the auto-focus can consult it. */
    public boolean claims(Notification notification) {
        return request(notification).isPresent();
    }

    private static Optional<Request> request(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title) || notification.message() == null) {
            return Optional.empty();
        }
        return Request.in(notification.message());
    }
}
