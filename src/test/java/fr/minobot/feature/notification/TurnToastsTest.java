package fr.minobot.feature.notification;

import fr.minobot.core.domain.Notification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which toasts are the game announcing a turn — claimed from the auto-focus, since the screen answers them. */
class TurnToastsTest {

    @Test
    @DisplayName("the turn toast is claimed: the turn auto-focus answers it from the banner")
    void claimsTheTurn() {
        assertThat(TurnToasts.claims(new Notification("Bravo - Dofus Retro", "C'est à \"Bravo\" de jouer")))
                .isTrue();
    }

    @Test
    @DisplayName("a game toast that is not a turn — an attack — is left to the auto-focus")
    void leavesToastsThatAreNotTurns() {
        assertThat(TurnToasts.claims(new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !"))).isFalse();
    }

    @Test
    @DisplayName("another application's toast is not ours")
    void leavesForeignToasts() {
        assertThat(TurnToasts.claims(new Notification("Slack", "C'est à toi de jouer"))).isFalse();
    }
}
