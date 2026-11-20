package fr.minobot.feature.notification;

import fr.minobot.core.FightLog;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.FakeWindowApi.PostedMessage;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The end-of-fight screen closer: which screens it presses ESCAPE on, and how. */
class ResultsCloserTest {

    private final AtomicLong now = new AtomicLong();
    private final FightLog fightLog = new FightLog(now::get);

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

    /**
     * The chat's line, read as the fight log reads it: under a line already read, since what a look after
     * a silence finds was written before it.
     */
    private void fightEnds() {
        fightLog.report(List.of("Alpha lance Flèche Magique."));
        fightLog.report(List.of("Alpha lance Flèche Magique.", "Combat terminé (ouvrir l'écran de"));
    }

    /** The windows ESCAPE was released in, in order. */
    private static List<Long> escaped(FakeWindowApi api) {
        return api.postedMessages().stream()
                .filter(message -> message.message() == Win32.WM_KEYUP && message.wparam() == Win32.VK_ESCAPE)
                .map(PostedMessage::hwnd)
                .toList();
    }

    /** The screen up in these windows, as the screen watcher would tell it. */
    private static void seenIn(Features features, ResultsCloser closer, String... names) {
        for (final var name : names) {
            closer.seen(features.window(name));
        }
    }

    @Test
    @DisplayName("presses ESCAPE where the screen is up but in the main character's, without taking the screen")
    void keepsTheMainCharacters() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Alpha", "Bravo", "Charlie");

        assertThat(escaped(api)).containsExactly(1L, 3L);
        assertThat(api.focusedWindows()).isEmpty();
        assertThat(api.foregroundWindow()).isEqualTo(3);
        assertThat(features.activity().recent()).hasSize(2);
    }

    @Test
    @DisplayName("a character with no screen up was not in the fight: ESCAPE would open its menu")
    void leavesTheOthersAlone() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Charlie");

        assertThat(escaped(api)).containsExactly(3L);
        assertThat(api.postedMessages()).noneMatch(message -> message.hwnd() == 1);
    }

    @Test
    @DisplayName("the key goes in between an activation sent and taken back, the key posted")
    void pressesWithinAnActivation() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Alpha");

        assertThat(api.postedMessages()).containsExactly(
                new PostedMessage(1, Win32.WM_ACTIVATE, Win32.WA_ACTIVE, 0, true),
                new PostedMessage(1, Win32.WM_KEYDOWN, Win32.VK_ESCAPE, Win32.keyLParam(Win32.SCAN_ESCAPE, false), false),
                new PostedMessage(1, Win32.WM_KEYUP, Win32.VK_ESCAPE, Win32.keyLParam(Win32.SCAN_ESCAPE, true), false),
                new PostedMessage(1, Win32.WM_ACTIVATE, Win32.WA_INACTIVE, 0, true));
    }

    @Test
    @DisplayName("keeps the window on screen instead when the main character's is minimized")
    void keepsTheForegroundWhenTheMainIsMinimized() {
        final var api = desktop().minimize(2);
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Alpha", "Charlie");

        assertThat(escaped(api)).containsExactly(1L);
    }

    @Test
    @DisplayName("presses each window once: the screen closed, ESCAPE opens the menu")
    void pressesOnce() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Alpha", "Alpha", "Charlie", "Alpha");

        assertThat(escaped(api)).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("looks for nothing before a fight ends, nor long after: a screen opened again is the player's")
    void sweepsOnlyAfterTheFight() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        seenIn(features, closer, "Alpha");
        assertThat(closer.sweeping()).isFalse();

        fightEnds();
        assertThat(closer.sweeping()).isTrue();
        now.addAndGet(ResultsCloser.WAIT.toNanos());
        seenIn(features, closer, "Alpha");

        assertThat(closer.sweeping()).isFalse();
        assertThat(api.postedMessages()).isEmpty();
    }

    @Test
    @DisplayName("the next fight's screens are closed again")
    void sweepsEachFight() {
        final var api = desktop();
        final var features = team(api);
        final var closer = features.resultsCloser(fightLog, now::get);

        fightEnds();
        seenIn(features, closer, "Alpha");
        now.addAndGet(ResultsCloser.WAIT.toNanos());
        fightEnds();
        seenIn(features, closer, "Alpha");

        assertThat(escaped(api)).containsExactly(1L, 1L);
    }
}
