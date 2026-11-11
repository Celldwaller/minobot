package fr.minobot.core.vision;

import fr.minobot.app.TestConfigs;
import fr.minobot.core.FightTurns;
import fr.minobot.core.WindowManager;
import fr.minobot.win32.FakeTextReader;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static fr.minobot.core.vision.BannerCaptures.banner;
import static fr.minobot.core.vision.BannerCaptures.blank;
import static org.assertj.core.api.Assertions.assertThat;

/** Which window is looked at, and what a look reports. */
class TurnWatcherTest {

    private final FakeTextReader reader = new FakeTextReader().reading("Le Ouassingue");
    private final FightTurns turns = new FightTurns(Runnable::run, System::nanoTime);
    private final List<String> heard = new CopyOnWriteArrayList<>();

    TurnWatcherTest() {
        turns.onTurn(heard::add);
    }

    private TurnWatcher watching(FakeWindowApi api) {
        final var windows = new WindowManager(api, TestConfigs.settings(Map.of()));
        return new TurnWatcher(api, windows, new TurnBanner(reader), turns);
    }

    @Test
    @DisplayName("the banner up in the window in front reports whose turn it is")
    void reportsTheFighterOnTheBanner() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withForeground(1)
                .showing(1, banner());

        watching(api).look();

        assertThat(heard).containsExactly("Le Ouassingue");
    }

    @Test
    @DisplayName("no banner up, nothing reported — and the OCR is not asked")
    void reportsNothingWithoutABanner() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withForeground(1)
                .showing(1, blank(2560, 1369));

        watching(api).look();

        assertThat(heard).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("out of the game, a character's window is looked at all the same")
    void looksAtACharacterWindowOutOfTheGame() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Dofus Retro v1.48")
                .withWindow(2, "Bravo - Dofus Retro v1.48")
                .withWindow(9, "Notepad")
                .withForeground(9)
                .showing(1, banner())
                .showing(2, banner());

        watching(api).look();

        assertThat(heard).as("Bravo's window, not the login screen's").containsExactly("Le Ouassingue");
        assertThat(reader.images()).hasSize(1);
    }

    @Test
    @DisplayName("a window that cannot be captured — minimized — reports nothing")
    void reportsNothingWithoutACapture() {
        final var api = new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus Retro v1.48")
                .withForeground(1);

        watching(api).look();

        assertThat(heard).isEmpty();
    }
}
