package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.FakeTextReader;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RememberingTextReaderTest {

    private final FakeTextReader engine = new FakeTextReader().reading("C'est à Alpha de jouer");
    private final RememberingTextReader reader = new RememberingTextReader(engine);

    @Test
    void theSamePixelsAreReadOnce() {
        reader.read(image(1));

        assertThat(reader.read(image(1))).contains("C'est à Alpha de jouer");
        assertThat(engine.images()).hasSize(1);
    }

    @Test
    void otherPixelsAreReadAgain() {
        reader.read(image(1));
        engine.reading("C'est à Bravo de jouer");

        assertThat(reader.read(image(2))).contains("C'est à Bravo de jouer");
        assertThat(engine.images()).hasSize(2);
    }

    @Test
    void theSamePixelsInAnotherShapeAreAnotherImage() {
        reader.read(new Capture(2, 1, new int[]{1, 1}));
        reader.read(new Capture(1, 2, new int[]{1, 1}));

        assertThat(engine.images()).hasSize(2);
    }

    @Test
    void aFailedReadIsAskedAgain() {
        engine.failing();
        assertThat(reader.read(image(1))).isEmpty();

        engine.reading("Alpha t'invite à rejoindre son groupe");

        assertThat(reader.read(image(1))).isEqualTo(Optional.of("Alpha t'invite à rejoindre son groupe"));
        assertThat(engine.images()).hasSize(2);
    }

    private static Capture image(int rgb) {
        return new Capture(2, 2, new int[]{rgb, rgb, 0, 0});
    }
}
