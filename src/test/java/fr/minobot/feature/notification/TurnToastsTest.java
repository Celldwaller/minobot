package fr.minobot.feature.notification;

import fr.minobot.core.domain.Notification;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Which toasts are the game announcing a turn, and whose. */
class TurnToastsTest {

    private final Features features = new Features(new FakeWindowApi(), Map.of());
    private final List<String> heard = new CopyOnWriteArrayList<>();

    TurnToastsTest() {
        features.turns().onTurn(heard::add);
    }

    @Test
    @DisplayName("the turn toast reports the turn of the character in its title")
    void reportsTheTurn() {
        final var toast = new Notification("Bravo - Dofus Retro", "C'est à \"Bravo\" de jouer");

        features.turnToasts().onNotification(toast);

        assertThat(heard).containsExactly("Bravo");
        assertThat(features.turnToasts().claims(toast)).as("the auto-focus leaves it to the turn features").isTrue();
    }

    @Test
    @DisplayName("a game toast that is not a turn — an attack, an invitation — is left to the auto-focus")
    void ignoresToastsThatAreNotTurns() {
        final var attack = new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !");

        features.turnToasts().onNotification(attack);

        assertThat(heard).isEmpty();
        assertThat(features.turnToasts().claims(attack)).isFalse();
    }

    @Test
    @DisplayName("another application's toast is not ours")
    void ignoresForeignToasts() {
        final var slack = new Notification("Slack", "C'est à toi de jouer");

        features.turnToasts().onNotification(slack);

        assertThat(heard).isEmpty();
        assertThat(features.turnToasts().claims(slack)).isFalse();
    }
}
