package fr.minobot.feature.notification;

import fr.minobot.core.domain.Request;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The request auto-focus: who is asked something, and when the player is left where they are. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RequestFocuserTest {

    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withForeground(1);
    }

    private static final Request INVITATION = new Request(Request.Kind.GROUP_INVITE, "Zoro");

    @Test
    @DisplayName("an invitation takes the player to the character invited")
    void focusesTheCharacterAsked() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.requestFocuser().onRequest(features.window("Bravo"), INVITATION);

        assertThat(api.foregroundWindow()).isEqualTo(2);
        assertThat(features.activity().recent()).singleElement().satisfies(entry -> {
            assertThat(entry.what()).isEqualTo("Switched to Bravo");
            assertThat(entry.detail()).isEqualTo("group invite from Zoro");
        });
    }

    @Test
    @DisplayName("a duel takes the player to the character challenged")
    void focusesTheCharacterChallenged() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.requestFocuser().onRequest(features.window("Bravo"), new Request(Request.Kind.DUEL, "Zoro"));

        assertThat(api.foregroundWindow()).isEqualTo(2);
        assertThat(features.activity().recent()).singleElement()
                .satisfies(entry -> assertThat(entry.detail()).isEqualTo("duel from Zoro"));
    }

    @Test
    @DisplayName("a request made of the character in front moves nothing, and notes nothing")
    void leavesTheCharacterInFront() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.requestFocuser().onRequest(features.window("Alpha"), INVITATION);

        assertThat(api.focusedWindows()).isEmpty();
        assertThat(features.activity().recent()).isEmpty();
    }

    @Test
    @DisplayName("it stands aside for a request answered in place")
    void standsAsideForARequestAnsweredInPlace() {
        final var api = desktop();
        final var features = new Features(api, Map.of("auto_accept_trade", true));
        final var focuser = features.requestFocuser(features.exchangeAccepter());

        focuser.onRequest(features.window("Bravo"), new Request(Request.Kind.TRADE, "Alpha"));

        assertThat(api.focusedWindows()).isEmpty();
    }

    @Test
    @DisplayName("it stands aside while a sequence holds the foreground")
    void yieldsToASequence() {
        final var api = desktop();
        final var features = new Features(api, Map.of());
        final var focuser = features.requestFocuser();

        try (final var _ = features.focus().takeOver()) {
            focuser.onRequest(features.window("Bravo"), INVITATION);
        }

        assertThat(api.focusedWindows()).isEmpty();
    }
}
