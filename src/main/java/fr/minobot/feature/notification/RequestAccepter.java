package fr.minobot.feature.notification;

import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Request;
import fr.minobot.core.input.BackgroundKeys;
import fr.minobot.i18n.Text;
import fr.minobot.win32.Win32;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Accepts a trade or a group invitation one of the player's own characters asks of another — trades while
 * the overlay's <em>Auto-accept trades</em> switch is on, invitations always.
 *
 * <p>When character A opens an exchange with character B, it is <em>B</em> that the game asks — in a box
 * on B's screen, {@code Alpha te propose de faire un échange} — and the question
 * <strong>names the one who asked</strong>. That name is the whole trick: the request is
 * <em>internal</em> when it is the name of one of the player's own windows. If it is a stranger's, the
 * request is left exactly as it was — the request auto-focus takes the player to the receiving window and
 * does nothing else, which is what a real offer should do. It hears of the request from
 * {@link Requests}, which makes one request of a box read look after look.
 *
 * <p>An invitation sent by the invitation relay is the relay's to accept: while it runs, invitations are
 * left to it, or its own ENTER would land in a window whose box is already gone, and open the chat.
 *
 * <p><strong>The player did not ask to change windows, so none does:</strong> the accept key is pressed
 * in the receiver's window in the background ({@link BackgroundKeys}), wherever the screen is.
 */
public final class RequestAccepter {

    private static final Logger log = LoggerFactory.getLogger(RequestAccepter.class);

    /**
     * The key that accepts an exchange or an invitation in Dofus Retro — {@code ENTER}. Dictated by the game, so it is a
     * constant here and not a setting. Pressed only for a request the game actually made: an ENTER into a
     * window with no offer on screen opens the chat, the very trap the invitation relay guards against.
     */
    private static final int ACCEPT_KEY = Win32.VK_RETURN;
    private static final int ACCEPT_SCAN_CODE = Win32.SCAN_RETURN;

    private final WindowManager windows;
    private final BackgroundKeys keys;
    private final Settings settings;
    private final BooleanSupplier relaying;
    private final ActivityLog activity;

    /** @param relaying whether the invitation relay is running, and accepting its invitations itself */
    public RequestAccepter(WindowApi api, WindowManager windows, Requests requests, Settings settings,
                           BooleanSupplier relaying, ActivityLog activity) {
        this.windows = windows;
        this.keys = new BackgroundKeys(api);
        this.settings = settings;
        this.relaying = relaying;
        this.activity = activity;

        requests.onRequest(this::onRequest);
    }

    /** Runs on a virtual thread, one per request, from {@link Requests}. */
    void onRequest(GameWindow receiver, Request request) {
        // A duel, or a stranger's: the auto-focus takes it, and that is all it should do.
        if (claims(receiver, request)) {
            accept(receiver, request);
        }
    }

    /**
     * Whether this will answer the request in place — read by {@link RequestFocuser} so it does not also
     * jump to the receiver. A pure function of the request, the switch, the relay and the windows on
     * screen, so both features decide the same without coordinating.
     */
    public boolean claims(GameWindow receiver, Request request) {
        final var answered = switch (request.kind()) {
            case TRADE -> settings.get().autoAcceptTrade();
            case GROUP_INVITE -> !relaying.getAsBoolean();
            case DUEL -> false;
        };
        return answered && anotherOfOurs(receiver, request.asker()).isPresent();
    }

    /**
     * The asker's window, when they are one of the player's characters and not the receiver themselves.
     * Matched by the whole name ({@link WindowManager#windowOf}): a stranger named {@code SuperAlpha} is
     * not our {@code Alpha}, and their request is a stranger's.
     */
    private Optional<GameWindow> anotherOfOurs(GameWindow receiver, String asker) {
        return windows.windowOf(asker).filter(window -> window.hwnd() != receiver.hwnd());
    }

    private void accept(GameWindow receiver, Request request) {
        if (!keys.press(receiver.hwnd(), ACCEPT_KEY, ACCEPT_SCAN_CODE)) {
            log.warn("Could not press ENTER in '{}': its {} was not accepted.", receiver.name(), request.kind());
            return;
        }
        log.info("Accepted the {} on '{}'.", request.kind(), receiver.name());
        final var what = request.kind() == Request.Kind.TRADE ? "activity.trade_accepted" : "activity.invite_accepted";
        activity.record(Text.of(what, receiver.name()), Text.of("activity.from", request.asker()));
    }
}
