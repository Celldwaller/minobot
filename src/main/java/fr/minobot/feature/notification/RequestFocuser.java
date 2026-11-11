package fr.minobot.feature.notification;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Request;

import java.util.function.BiPredicate;

/**
 * Takes the player to the character something is asked of — the auto-focus, for a group invitation or an
 * exchange.
 *
 * <p>Like the turn auto-focus, it hears of requests from {@link Requests}, from the toast or from the box
 * on the screen, so a window gone deaf to toasts still calls the player. It yields like every auto-focus
 * ({@link Summoner}) — never from under the invitation relay, which holds the foreground while it accepts
 * its own invitations — and it stands aside for a request another feature answers in place, as the
 * exchange accepter does for a trade between two of the player's characters.
 */
public final class RequestFocuser {

    private final Summoner summoner;

    /** Whether a request is answered in place elsewhere, in which case this does not move the player. */
    private final BiPredicate<GameWindow, Request> answeredInPlace;

    public RequestFocuser(WindowManager windows, FocusManager focus, Requests requests, ActivityLog activity,
                          BiPredicate<GameWindow, Request> answeredInPlace) {
        this.summoner = new Summoner(windows, focus, activity);
        this.answeredInPlace = answeredInPlace;

        requests.onRequest(this::onRequest);
    }

    void onRequest(GameWindow receiver, Request request) {
        if (!answeredInPlace.test(receiver, request)) {
            summoner.summon(receiver, reason(request));
        }
    }

    private static String reason(Request request) {
        return switch (request.kind()) {
            case GROUP_INVITE -> "group invite from " + request.asker();
            case TRADE -> "trade from " + request.asker();
        };
    }
}
