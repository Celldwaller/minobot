package fr.minobot.feature.notification;

import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Request;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Which toasts are requests, made of whom, by whom. */
class RequestToastsTest {

    private final Features features = new Features(new FakeWindowApi()
            .withWindow(1, "Alpha - Dofus Retro v1.48")
            .withWindow(2, "Bravo - Dofus Retro v1.48"), Map.of());
    private final List<String> heard = new CopyOnWriteArrayList<>();

    RequestToastsTest() {
        features.requests().onRequest((receiver, request) -> heard.add(describe(receiver, request)));
    }

    private static String describe(GameWindow receiver, Request request) {
        return receiver.name() + " <- " + request.kind() + " from " + request.asker();
    }

    @Test
    @DisplayName("an invitation toast is an invitation made of the character in its title")
    void reportsAnInvitation() {
        final var toast = new Notification("Bravo - Dofus Retro v1.48", "Alpha t'invite à rejoindre son groupe.\nAcceptes-tu ?");

        features.requestToasts().onNotification(toast);

        assertThat(heard).containsExactly("Bravo <- GROUP_INVITE from Alpha");
        assertThat(features.requestToasts().claims(toast)).as("the auto-focus leaves it to the request features").isTrue();
    }

    @Test
    @DisplayName("a trade toast names its asker, even a stranger")
    void reportsATrade() {
        features.requestToasts().onNotification(
                new Notification("Bravo - Dofus Retro", "Zoro te propose de faire un échange."));

        assertThat(heard).containsExactly("Bravo <- TRADE from Zoro");
    }

    @Test
    @DisplayName("a game toast that asks nothing — an attack, a turn — is not a request")
    void ignoresOtherToasts() {
        final var attack = new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !");

        features.requestToasts().onNotification(attack);
        features.requestToasts().onNotification(new Notification("Bravo - Dofus Retro", "C'est à \"Bravo\" de jouer"));

        assertThat(heard).isEmpty();
        assertThat(features.requestToasts().claims(attack)).isFalse();
    }

    @Test
    @DisplayName("another application's toast is not ours, whatever it says")
    void ignoresForeignToasts() {
        final var slack = new Notification("Slack", "Alpha t'invite à rejoindre son groupe.");

        features.requestToasts().onNotification(slack);

        assertThat(heard).isEmpty();
        assertThat(features.requestToasts().claims(slack)).isFalse();
    }
}
