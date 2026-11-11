package fr.minobot.core.vision;

import fr.minobot.app.TestConfigs;
import fr.minobot.core.WindowManager;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static fr.minobot.core.vision.BannerCaptures.blank;
import static org.assertj.core.api.Assertions.assertThat;

/** Which window each look takes, and what it hands the readers. */
class ScreenWatcherTest {

    private final List<String> read = new CopyOnWriteArrayList<>();
    private final ScreenReader recorder = (window, _, _) -> read.add(window.name());

    private ScreenWatcher watching(FakeWindowApi api, ScreenReader... readers) {
        final var windows = new WindowManager(api, TestConfigs.settings(Map.of()));
        return new ScreenWatcher(api, windows, List.of(readers));
    }

    private static FakeWindowApi threeCharacters() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withWindow(3, "Delta - Dofus Retro v1.48")
                .showing(1, blank(742, 556))
                .showing(2, blank(742, 556))
                .showing(3, blank(742, 556));
    }

    @Test
    @DisplayName("each look takes the next character's window, round and round")
    void looksAtEveryWindowInTurn() {
        final var watcher = watching(threeCharacters(), recorder);

        for (var look = 0; look < 4; look++) {
            watcher.look();
        }

        assertThat(read).containsExactly("Alpha", "Bravo", "Delta", "Alpha");
    }

    @Test
    @DisplayName("one capture a look, read by every reader")
    void handsOneCaptureToEveryReader() {
        final var others = new CopyOnWriteArrayList<String>();
        final var watcher = watching(threeCharacters(), recorder, (window, _, _) -> others.add(window.name()));

        watcher.look();

        assertThat(read).containsExactly("Alpha");
        assertThat(others).containsExactly("Alpha");
    }

    @Test
    @DisplayName("the readers are told when the capture was taken, to time what follows from it")
    void tellsWhenTheCaptureWasTaken() {
        final var seen = new CopyOnWriteArrayList<Long>();
        final var watcher = watching(threeCharacters(), (_, _, seenAt) -> seen.add(seenAt));

        final var before = System.nanoTime();
        watcher.look();
        final var after = System.nanoTime();

        assertThat(seen).singleElement().satisfies(seenAt -> assertThat(seenAt).isBetween(before, after));
    }

    @Test
    @DisplayName("a window that cannot be captured — minimized — gives its look to the next")
    void skipsAWindowWithoutACapture() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .showing(2, blank(742, 556));
        final var watcher = watching(api, recorder);

        watcher.look();
        watcher.look();

        assertThat(read).containsExactly("Bravo", "Bravo");
    }

    @Test
    @DisplayName("a window with no character in it yet — the login screen — is not looked at")
    void skipsTheLoginScreen() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .runningAs(1, "C:\\Games\\Dofus Retro.exe")
                .showing(1, blank(742, 556))
                .showing(2, blank(742, 556));
        final var watcher = watching(api, recorder);

        watcher.look();
        watcher.look();

        assertThat(read).containsExactly("Bravo", "Bravo");
    }

    @Test
    void looksAtNothingWithNoGame() {
        watching(new FakeWindowApi(), recorder).look();

        assertThat(read).isEmpty();
    }
}
