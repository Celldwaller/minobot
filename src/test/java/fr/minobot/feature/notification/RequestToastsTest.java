package fr.minobot.feature.notification;

import fr.minobot.core.domain.Notification;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Which toasts are requests: a hint of where to look, claimed from the auto-focus. */
class RequestToastsTest {

    private final Features features = new Features(new FakeWindowApi()
            .withWindow(1, "Alpha - Dofus Retro v1.48")
            .withWindow(2, "Bravo - Dofus Retro v1.48"), Map.of());
    private final List<String> heard = new CopyOnWriteArrayList<>();

    RequestToastsTest() {
        features.requests().onRequest((receiver, request) -> heard.add(receiver.name()));
    }

    @Test
    @DisplayName("a request toast points the screen at the character it names, and reports nothing")
    void pointsTheScreenAtTheReceiver() {
        features.requestToasts().onNotification(
                new Notification("Bravo - Dofus Retro v1.48", "Zoro te propose de faire un échange."));

        assertThat(features.requests().awaited()).isEqualTo(2);
        assertThat(heard).as("only the box on screen is a request").isEmpty();
    }

    @Test
    @DisplayName("a toast that is not a request points the screen nowhere")
    void leavesTheScreenAloneOtherwise() {
        features.requestToasts().onNotification(new Notification("Bravo - Dofus Retro v1.48", "Vous êtes attaqué !"));
        features.requestToasts().onNotification(new Notification("Slack", "Zoro te défie."));

        assertThat(features.requests().awaited()).isEqualTo(Win32.NULL_HANDLE);
    }

    @Test
    @DisplayName("an invitation, a trade and a duel toast are claimed")
    void claimsRequests() {
        assertThat(RequestToasts.claims(new Notification("Bravo - Dofus Retro v1.48",
                "Alpha t'invite à rejoindre son groupe.\nAcceptes-tu ?"))).isTrue();
        assertThat(RequestToasts.claims(new Notification("Bravo - Dofus Retro",
                "Zoro te propose de faire un échange."))).isTrue();
        assertThat(RequestToasts.claims(new Notification("Bravo - Dofus Retro", "Zoro te défie."))).isTrue();
    }

    @Test
    @DisplayName("a game toast that asks nothing — an attack, a turn — is left to the auto-focus")
    void leavesOtherToasts() {
        assertThat(RequestToasts.claims(new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !"))).isFalse();
        assertThat(RequestToasts.claims(new Notification("Bravo - Dofus Retro", "C'est à \"Bravo\" de jouer")))
                .isFalse();
    }

    @Test
    @DisplayName("another application's toast is not ours, whatever it says")
    void leavesForeignToasts() {
        assertThat(RequestToasts.claims(new Notification("Slack", "Alpha t'invite à rejoindre son groupe.")))
                .isFalse();
    }
}
