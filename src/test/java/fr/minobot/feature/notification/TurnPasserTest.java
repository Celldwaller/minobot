package fr.minobot.feature.notification;

import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn-passer: which turns it ends, and which are none of its business.
 *
 * <p>Focusing a background window makes the real {@link fr.minobot.core.FocusManager} sleep through its
 * ALT dance, so the timeout is generous — the same shape as the invitation relay's tests.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TurnPasserTest {

    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withForeground(1);
    }

    /** The feature reads its switch live, so a test turns it on the way the overlay does. */
    private static final Map<String, Object> ON = Map.of("auto_pass_turn", true);

    @Test
    @DisplayName("brings the character up and ends its turn")
    void passesTheTurn() {
        final var api = desktop();
        final var features = new Features(api, ON);

        features.turnPasser().onTurn("Bravo");

        assertThat(api.foregroundWindow()).isEqualTo(2);
        assertThat(features.input().actions())
                .as("the game's end-turn key, F1, once Bravo is up")
                .contains("key:F1");
    }

    @Test
    @DisplayName("passes even the character in front — the switch means the player has stepped away")
    void passesTheForegroundCharacterToo() {
        final var api = desktop().withForeground(2); // the player is sitting on Bravo
        final var features = new Features(api, ON);

        features.turnPasser().onTurn("Bravo");

        assertThat(features.input().actions())
                .as("Bravo already holds the screen, so its turn is ended without a refocus")
                .containsExactly("key:F1");
    }

    @Test
    @DisplayName("does nothing while the switch is off — which is where it starts")
    void doesNothingWhenOff() {
        final var api = desktop();
        final var features = new Features(api, Map.of()); // the default: off

        features.turnPasser().onTurn("Bravo");

        assertThat(features.input().actions()).isEmpty();
        assertThat(api.focusedWindows()).isEmpty();
    }

    @Test
    @DisplayName("the overlay can flip it on mid-fight, and the very next turn is passed")
    void readsTheSwitchLive() {
        final var api = desktop();
        final var features = new Features(api, Map.of());
        final var turnPasser = features.turnPasser();

        features.settings().update(config -> config.withAutoPassTurn(true));
        turnPasser.onTurn("Bravo");

        assertThat(features.input().actions()).contains("key:F1");
        assertThat(api.foregroundWindow()).isEqualTo(2);
    }

    @Test
    @DisplayName("a monster's turn, read off the banner, is nobody's to pass")
    void ignoresAMonster() {
        final var api = desktop();
        final var features = new Features(api, ON);

        features.turnPasser().onTurn("Le Ouassingue");

        assertThat(features.input().actions()).isEmpty();
        assertThat(api.focusedWindows()).isEmpty();
    }

    @Test
    @DisplayName("a fighter whose name only contains a character's is not that character")
    void matchesTheWholeName() {
        final var api = desktop();
        final var features = new Features(api, ON);

        features.turnPasser().onTurn("Bra");

        assertThat(features.input().actions()).isEmpty();
    }

    @Test
    @DisplayName("a turn seen as a toast and again on the banner is passed once")
    void passesATurnSeenTwiceOnce() {
        final var api = desktop();
        final var features = new Features(api, ON);
        features.turnPasser();

        features.turns().report("Bravo");
        features.turns().report("Bravo");

        assertThat(features.input().actions()).containsOnlyOnce("key:F1");
    }
}
