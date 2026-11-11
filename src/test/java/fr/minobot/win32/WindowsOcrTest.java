package fr.minobot.win32;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the live Windows OCR engine on the game's turn banner: a strip cut from a real capture (a
 * monster's — a character's name is the player's, and stays out of the repository) and a name drawn on
 * the strip's beige the way the game writes one.
 *
 * <p>Like {@link User32Test}, this is what stands between a wrong vtable slot and a crash: a WinRT call
 * through the wrong slot does not fail to compile, it calls another method.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsOcrTest {

    private static final int STRIP_BEIGE = 0xD5CFAA;

    private static WindowsOcr ocr;

    @BeforeAll
    static void startTheEngine() {
        ocr = WindowsOcr.start().orElse(null);
    }

    @AfterAll
    static void stopTheEngine() {
        if (ocr != null) {
            ocr.close();
        }
    }

    @Test
    @DisplayName("a banner's name strip, framed in beige, reads back as the fighter's name")
    void readsTheNameOnABanner() throws IOException {
        assumeTrue(ocr != null, "no OCR language installed for this user");

        assertThat(ocr.read(strip("banner-ouassingue.png").padded(20, STRIP_BEIGE)))
                .hasValueSatisfying(text -> assertThat(text.strip()).isEqualTo("Le Ouassingue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alpha", "Bravo"})
    @DisplayName("a one-word character name on the strip's beige reads back whole")
    void readsACharacterName(String name) {
        assumeTrue(ocr != null, "no OCR language installed for this user");

        assertThat(ocr.read(written(name).padded(20, STRIP_BEIGE)))
                .hasValueSatisfying(text -> assertThat(text.strip()).isEqualTo(name));
    }

    @Test
    @DisplayName("an image with no text in it reads as blank, not as a failure")
    void readsNothingInABlankImage() {
        assumeTrue(ocr != null, "no OCR language installed for this user");

        final var blank = new Capture(200, 80, new int[200 * 80]);

        assertThat(ocr.read(blank)).hasValueSatisfying(text -> assertThat(text).isBlank());
    }

    @Test
    @DisplayName("the engine survives many reads in a row — every COM object released, none double-released")
    void readsRepeatedly() throws IOException {
        assumeTrue(ocr != null, "no OCR language installed for this user");
        final var image = strip("banner-ouassingue.png").padded(20, STRIP_BEIGE);

        for (var i = 0; i < 50; i++) {
            assertThat(ocr.read(image)).hasValueSatisfying(text -> assertThat(text.strip()).isEqualTo("Le Ouassingue"));
        }
    }

    private static Capture strip(String fixture) throws IOException {
        try (final var in = WindowsOcrTest.class.getResourceAsStream("/vision/" + fixture)) {
            return capture(ImageIO.read(in));
        }
    }

    /** A name in dark bold on a strip the size of the real one (407x35 on a 2560x1369 window). */
    private static Capture written(String name) {
        final var image = new BufferedImage(407, 35, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(STRIP_BEIGE));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(0x3A2E1E));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        graphics.drawString(name, 11, 27);
        graphics.dispose();
        return capture(image);
    }

    private static Capture capture(BufferedImage image) {
        final var pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] &= 0xFFFFFF;
        }
        return new Capture(image.getWidth(), image.getHeight(), pixels);
    }
}
