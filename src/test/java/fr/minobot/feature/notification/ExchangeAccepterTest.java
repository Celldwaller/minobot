package fr.minobot.feature.notification;

import fr.minobot.core.domain.Request;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.FakeWindowApi.PostedMessage;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which trades accept themselves, which are left to the player — and how the request auto-focus is kept
 * from fighting the accept.
 *
 * <p>The stranger's trade still brings its receiver up through the real {@link fr.minobot.core.FocusManager},
 * which sleeps through its ALT dance, so the timeout is generous, like the invitation relay's tests.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ExchangeAccepterTest {

    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withForeground(1);
    }

    private static Request tradeFrom(String asker) {
        return new Request(Request.Kind.TRADE, asker);
    }

    private static final Map<String, Object> ON = Map.of("auto_accept_trade", true);

    /** The windows ENTER was released in, in the background. */
    private static List<Long> accepted(FakeWindowApi api) {
        return api.postedMessages().stream()
                .filter(message -> message.message() == Win32.WM_KEYUP && message.wparam() == Win32.VK_RETURN)
                .map(PostedMessage::hwnd)
                .toList();
    }

    @Test
    @DisplayName("a trade between two of our characters is accepted in the background")
    void acceptsAnInternalTradeInPlace() {
        final var api = desktop().withForeground(1); // the player opened the trade from Alpha
        final var features = new Features(api, ON);

        features.exchangeAccepter().onRequest(features.window("Bravo"), tradeFrom("Alpha"));

        assertThat(accepted(api)).as("Bravo accepts with ENTER").containsExactly(2L);
        assertThat(api.postedMessages().getFirst())
                .as("told it is active first, so the game reads the key")
                .isEqualTo(new PostedMessage(2, Win32.WM_ACTIVATE, Win32.WA_ACTIVE, 0, true));
        assertThat(api.focusedWindows()).as("no window took the screen").isEmpty();
        assertThat(features.input().actions()).isEmpty();
        assertThat(api.foregroundWindow()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stranger's trade is left to the auto-focus: it brings the receiver up, nothing more")
    void leavesAStrangersTradeToTheAutoFocus() {
        final var api = desktop();
        final var features = new Features(api, ON);
        features.requestFocuser(features.exchangeAccepter());

        // Zoro is not one of our windows: a real trade offer from another player online.
        features.requests().report(features.window("Bravo"), tradeFrom("Zoro"));

        assertThat(api.foregroundWindow()).as("the receiver is brought up").isEqualTo(2);
        assertThat(accepted(api)).as("but nothing is accepted on a stranger's behalf").isEmpty();
    }

    @Test
    @DisplayName("the auto-focus stands aside for an internal trade, so it is not focused twice")
    void theAutoFocusStandsAsideForAnInternalTrade() {
        final var api = desktop().withForeground(1);
        final var features = new Features(api, ON);
        features.requestFocuser(features.exchangeAccepter());

        features.requests().report(features.window("Bravo"), tradeFrom("Alpha"));

        assertThat(accepted(api)).containsExactly(2L);
        assertThat(api.focusedWindows()).as("the auto-focus took no window up").isEmpty();
        assertThat(api.foregroundWindow()).isEqualTo(1);
    }

    @Test
    @DisplayName("a trade seen as a toast and again on screen is accepted once")
    void acceptsATradeSeenTwiceOnce() {
        final var api = desktop();
        final var features = new Features(api, ON);
        features.exchangeAccepter();

        features.requests().report(features.window("Bravo"), tradeFrom("Alpha"));
        features.requests().report(features.window("Bravo"), tradeFrom("Alpha"));

        assertThat(accepted(api)).containsExactly(2L);
    }

    @Test
    @DisplayName("claims() tells the auto-focus which requests to leave alone")
    void claimsOnlyInternalTrades() {
        final var features = new Features(desktop(), ON);
        final var accepter = features.exchangeAccepter();
        final var bravo = features.window("Bravo");

        assertThat(accepter.claims(bravo, tradeFrom("Alpha"))).as("Alpha is ours: answered in place").isTrue();
        assertThat(accepter.claims(bravo, tradeFrom("Zoro"))).as("Zoro is a stranger: kept by the auto-focus").isFalse();
        assertThat(accepter.claims(bravo, new Request(Request.Kind.GROUP_INVITE, "Alpha")))
                .as("not a trade at all").isFalse();
        assertThat(accepter.claims(bravo, tradeFrom("Bravo"))).as("nobody trades with themselves").isFalse();
    }

    @Test
    @DisplayName("a stranger whose name merely contains one of ours is not treated as internal")
    void matchesTheAskerByTheWholeName() {
        final var features = new Features(desktop(), ON);
        final var accepter = features.exchangeAccepter();

        assertThat(accepter.claims(features.window("Bravo"), tradeFrom("SuperAlpha")))
                .as("SuperAlpha is not our Alpha")
                .isFalse();
        assertThat(accepter.claims(features.window("Bravo"), tradeFrom("Alph")))
                .as("nor is a name cut short")
                .isFalse();
    }

    @Test
    @DisplayName("turned off, it claims nothing and does nothing")
    void doesNothingWhenOff() {
        final var api = desktop();
        final var features = new Features(api, Map.of("auto_accept_trade", false));
        final var accepter = features.exchangeAccepter();

        accepter.onRequest(features.window("Bravo"), tradeFrom("Alpha"));

        assertThat(api.postedMessages()).isEmpty();
        assertThat(api.focusedWindows()).isEmpty();
        assertThat(accepter.claims(features.window("Bravo"), tradeFrom("Alpha")))
                .as("off means the auto-focus keeps every request")
                .isFalse();
    }
}
