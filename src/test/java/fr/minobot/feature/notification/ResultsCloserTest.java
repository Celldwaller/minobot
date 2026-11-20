package fr.minobot.feature.notification;

import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.FakeWindowApi.PostedMessage;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The end-of-fight screen closer: which windows it presses ESCAPE in, and how. */
class ResultsCloserTest {

    /** Bravo leads the order, though the player sits on Charlie. */
    private Features team(FakeWindowApi api) {
        final var features = new Features(api, Map.of());
        features.settings().update(config -> config.withCharacterOrder(List.of("Bravo", "Alpha", "Charlie")));
        return features;
    }

    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withWindow(3, "Charlie - Dofus Retro v1.48")
                .withForeground(3);
    }

    /** The windows ESCAPE was released in. */
    private static List<Long> escaped(FakeWindowApi api) {
        return api.postedMessages().stream()
                .filter(message -> message.message() == Win32.WM_KEYUP && message.wparam() == Win32.VK_ESCAPE)
                .map(PostedMessage::hwnd)
                .toList();
    }

    /** What one window was told, in order. */
    private static List<PostedMessage> toldTo(FakeWindowApi api, long hwnd) {
        return api.postedMessages().stream().filter(message -> message.hwnd() == hwnd).toList();
    }

    @Test
    @DisplayName("presses ESCAPE in every window but the main character's, without taking the screen")
    void keepsTheMainCharacters() {
        final var api = desktop();
        final var features = team(api);

        features.resultsCloser().closeResults();

        assertThat(escaped(api)).containsExactlyInAnyOrder(1L, 3L);
        assertThat(api.focusedWindows()).isEmpty();
        assertThat(api.foregroundWindow()).isEqualTo(3);
        assertThat(features.activity().recent()).hasSize(1);
    }

    @Test
    @DisplayName("the key goes in between an activation sent and taken back, the key posted")
    void pressesWithinAnActivation() {
        final var api = desktop();

        team(api).resultsCloser().closeResults();

        assertThat(toldTo(api, 1)).containsExactly(
                new PostedMessage(1, Win32.WM_ACTIVATE, Win32.WA_ACTIVE, 0, true),
                new PostedMessage(1, Win32.WM_KEYDOWN, Win32.VK_ESCAPE, Win32.keyLParam(0x01, false), false),
                new PostedMessage(1, Win32.WM_KEYUP, Win32.VK_ESCAPE, Win32.keyLParam(0x01, true), false),
                new PostedMessage(1, Win32.WM_ACTIVATE, Win32.WA_INACTIVE, 0, true));
    }

    @Test
    @DisplayName("keeps the window on screen instead when the main character's is minimized, and leaves that one be")
    void keepsTheForegroundWhenTheMainIsMinimized() {
        final var api = desktop().minimize(2);

        team(api).resultsCloser().closeResults();

        assertThat(escaped(api)).containsExactly(1L);
        assertThat(toldTo(api, 2)).isEmpty();
    }

    @Test
    @DisplayName("a window that cannot be told it is active gets no key")
    void needsTheActivation() {
        final var api = desktop();
        final var features = team(api);
        api.close(1);

        features.resultsCloser().closeResults();

        assertThat(escaped(api)).containsExactly(3L);
    }

    @Test
    @DisplayName("does nothing with the main character alone")
    void aloneIsNothingToClose() {
        final var api = new FakeWindowApi().withWindow(2, "Bravo - Dofus Retro v1.48").withForeground(2);
        final var features = team(api);

        features.resultsCloser().closeResults();

        assertThat(api.postedMessages()).isEmpty();
        assertThat(features.activity().recent()).isEmpty();
    }
}
