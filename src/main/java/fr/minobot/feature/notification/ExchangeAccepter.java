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

/**
 * Accepts a trade one of the player's own characters asks of another — the feature the overlay's
 * <em>Auto-accept trades</em> switch drives.
 *
 * <p>When character A opens an exchange with character B, it is <em>B</em> that the game asks — in a box
 * on B's screen, {@code Alpha te propose de faire un échange} — and the question
 * <strong>names the one who asked</strong>. That name is the whole trick: the exchange is
 * <em>internal</em> when it is the name of one of the player's own windows. If it is a stranger's, the
 * request is left exactly as it was — the request auto-focus takes the player to the receiving window and
 * does nothing else, which is what a real trade offer should do. It hears of the request from
 * {@link Requests}, which makes one request of a box read look after look.
 *
 * <p><strong>The player did not ask to change windows, so none does:</strong> the accept key is pressed
 * in the receiver's window in the background ({@link BackgroundKeys}), wherever the screen is.
 */
public final class ExchangeAccepter {

    private static final Logger log = LoggerFactory.getLogger(ExchangeAccepter.class);

    /**
     * The key that accepts an exchange in Dofus Retro — {@code ENTER}. Dictated by the game, so it is a
     * constant here and not a setting. Pressed only for a request the game actually made: an ENTER into a
     * window with no offer on screen opens the chat, the very trap the invitation relay guards against.
     */
    private static final int ACCEPT_KEY = Win32.VK_RETURN;
    private static final int ACCEPT_SCAN_CODE = Win32.SCAN_RETURN;

    private final WindowManager windows;
    private final BackgroundKeys keys;
    private final Settings settings;
    private final ActivityLog activity;

    public ExchangeAccepter(WindowApi api, WindowManager windows, Requests requests, Settings settings,
                            ActivityLog activity) {
        this.windows = windows;
        this.keys = new BackgroundKeys(api);
        this.settings = settings;
        this.activity = activity;

        requests.onRequest(this::onRequest);
    }

    /** Runs on a virtual thread, one per request, from {@link Requests}. */
    void onRequest(GameWindow receiver, Request request) {
        // Not a trade, or a stranger's: the auto-focus takes it, and that is all it should do.
        if (claims(receiver, request)) {
            accept(receiver, request.asker());
        }
    }

    /**
     * Whether this will answer the request in place — read by {@link RequestFocuser} so it does not also
     * jump to the receiver. A pure function of the request, the switch and the windows on screen, so both
     * features decide the same without coordinating.
     */
    public boolean claims(GameWindow receiver, Request request) {
        return settings.get().autoAcceptTrade()
                && request.kind() == Request.Kind.TRADE
                && anotherOfOurs(receiver, request.asker()).isPresent();
    }

    /**
     * The asker's window, when they are one of the player's characters and not the receiver themselves.
     * Matched by the whole name ({@link WindowManager#windowOf}): a stranger named {@code SuperAlpha} is
     * not our {@code Alpha}, and their trade is a stranger's.
     */
    private Optional<GameWindow> anotherOfOurs(GameWindow receiver, String asker) {
        return windows.windowOf(asker).filter(window -> window.hwnd() != receiver.hwnd());
    }

    private void accept(GameWindow receiver, String asker) {
        if (!keys.press(receiver.hwnd(), ACCEPT_KEY, ACCEPT_SCAN_CODE)) {
            log.warn("Could not press ENTER in '{}': its trade was not accepted.", receiver.name());
            return;
        }
        log.info("Accepted the trade on '{}'.", receiver.name());
        activity.record(Text.of("activity.trade_accepted", receiver.name()), Text.of("activity.from", asker));
    }
}
