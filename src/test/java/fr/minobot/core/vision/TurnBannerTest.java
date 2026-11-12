package fr.minobot.core.vision;

import fr.minobot.win32.FakeTextReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.BEIGE;
import static fr.minobot.core.vision.BannerCaptures.INK;
import static fr.minobot.core.vision.BannerCaptures.banner;
import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/** Paints the turn banner where the game draws it ({@link BannerCaptures}) and checks what is handed to the OCR and what comes back. */
class TurnBannerTest {

    @Test
    @DisplayName("a banner up is read, and the name comes back trimmed")
    void readsTheFighterOnTheBanner() {
        final var reader = new FakeTextReader().reading(" Alpha \n");

        assertThat(new TurnBanner(reader).fighter(banner())).contains("Alpha");
    }

    @Test
    @DisplayName("only the strip goes to the OCR, framed in beige so the letters do not touch the edge")
    void handsTheOcrThePaddedStrip() {
        final var reader = new FakeTextReader().reading("Alpha");

        new TurnBanner(reader).fighter(banner());

        assertThat(reader.images()).singleElement().satisfies(image -> {
            assertThat(image.width()).isEqualTo((1142 - 735) + 2 * 20);
            assertThat(image.height()).isEqualTo((129 - 94) + 2 * 20);
            assertThat(image.rgb(0, 0)).as("the margin").isEqualTo(BEIGE);
            assertThat(image.rgb(20 + 755 - 735, 20 + 104 - 94)).as("the first letter, past the margin").isEqualTo(INK);
        });
    }

    @Test
    @DisplayName("of a frame, only the strip is read, once: a region is a copy off the GPU")
    void readsOneRegionOffTheFrame() {
        final var frame = RecordingFrame.of(banner());

        assertThat(new TurnBanner(new FakeTextReader().reading("Alpha")).fighter(frame)).contains("Alpha");
        assertThat(frame.regions).singleElement().satisfies(region -> {
            assertThat(region.width()).isEqualTo(1142 - 735);
            assertThat(region.height()).isEqualTo(129 - 94);
        });
    }

    @Test
    @DisplayName("a frame whose strip cannot be read names nobody, and the OCR is not asked")
    void readsNothingOfAnUnreadableFrame() {
        final var reader = new FakeTextReader().reading("Alpha");

        assertThat(new TurnBanner(reader).fighter(RecordingFrame.unreadable(banner()))).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("no banner, no OCR: the engine is not asked to read the map")
    void readsNothingWithoutABanner() {
        final var reader = new FakeTextReader().reading("anything");

        assertThat(new TurnBanner(reader).fighter(blank(2560, 1369))).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("a banner still sliding in — beige at one end only — is not read")
    void waitsForTheBannerToSettle() {
        final var reader = new FakeTextReader().reading("Alpha");
        final var sliding = banner();
        paint(sliding, 1100, 94, 1143, 130, 0x8FB16D);

        assertThat(new TurnBanner(reader).fighter(sliding)).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("an empty strip — the banner fading out — names nobody")
    void readsNobodyOnABlankStrip() {
        assertThat(new TurnBanner(new FakeTextReader().reading("  ")).fighter(banner())).isEmpty();
    }

    @Test
    void readsNobodyWhenTheOcrFails() {
        assertThat(new TurnBanner(new FakeTextReader().failing()).fighter(banner())).isEmpty();
    }

    @Test
    @DisplayName("in a smaller window the strip is found where the stage puts it")
    void scalesWithTheWindow() {
        final var reader = new FakeTextReader().reading("Bravo");
        final var stage = GameStage.of(blank(1920, 1017));
        final var capture = blank(1920, 1017);
        paint(capture, stage.x(149.7), stage.y(38.2), stage.x(314.9), stage.y(52.4), BEIGE);

        assertThat(new TurnBanner(reader).fighter(capture)).contains("Bravo");
    }
}
