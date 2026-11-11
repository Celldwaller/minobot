package fr.minobot.feature.notification;

import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The turn auto-focus: whose turn calls the player, and whose moves nothing. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TurnFocuserTest {

    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withForeground(1);
    }

    @Test
    @DisplayName("a character's turn takes the player to them")
    void focusesTheCharacter() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.turnFocuser().onTurn("Bravo");

        assertThat(api.foregroundWindow()).isEqualTo(2);
        assertThat(features.activity().recent()).singleElement()
                .satisfies(entry -> assertThat(entry.what()).isEqualTo("Switched to Bravo"));
    }

    @Test
    @DisplayName("a monster's turn moves nothing")
    void ignoresAMonster() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.turnFocuser().onTurn("Le Ouassingue");

        assertThat(api.focusedWindows()).isEmpty();
    }

    @Test
    @DisplayName("the turn of the character in front moves nothing, and notes nothing")
    void leavesTheCharacterInFront() {
        final var api = desktop();
        final var features = new Features(api, Map.of());

        features.turnFocuser().onTurn("Alpha");

        assertThat(api.focusedWindows()).isEmpty();
        assertThat(features.activity().recent()).isEmpty();
    }

    @Test
    @DisplayName("it stands aside while a sequence holds the foreground")
    void yieldsToASequence() {
        final var api = desktop();
        final var features = new Features(api, Map.of());
        final var focuser = features.turnFocuser();

        try (final var _ = features.focus().takeOver()) {
            focuser.onTurn("Bravo");
        }

        assertThat(api.focusedWindows()).isEmpty();
    }
}
