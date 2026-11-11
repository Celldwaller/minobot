package fr.minobot.feature.notification;

import fr.minobot.core.domain.Notification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which toasts are requests — claimed from the auto-focus, since the box on screen answers them. */
class RequestToastsTest {

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
