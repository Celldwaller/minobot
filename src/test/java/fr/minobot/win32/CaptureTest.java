package fr.minobot.win32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureTest {

    @Test
    @DisplayName("pixels are read row by row from the top-left")
    void readsPixelsRowMajor() {
        final var capture = new Capture(3, 2, new int[]{
                0x000001, 0x000002, 0x000003,
                0x000004, 0x000005, 0x000006});

        assertThat(capture.rgb(0, 0)).isEqualTo(0x000001);
        assertThat(capture.rgb(2, 0)).isEqualTo(0x000003);
        assertThat(capture.rgb(0, 1)).as("the second row starts one width in").isEqualTo(0x000004);
        assertThat(capture.rgb(2, 1)).isEqualTo(0x000006);
    }

    @Test
    @DisplayName("a pixel outside the capture is an error, not the neighbouring row's")
    void refusesPixelsOutsideTheCapture() {
        final var capture = new Capture(3, 2, new int[6]);

        assertThatThrownBy(() -> capture.rgb(3, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> capture.rgb(0, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> capture.rgb(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void refusesASizeItsPixelsDoNotFill() {
        assertThatThrownBy(() -> new Capture(3, 2, new int[5])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cropsARegion() {
        final var capture = new Capture(3, 2, new int[]{
                1, 2, 3,
                4, 5, 6});

        final var cropped = capture.crop(1, 0, 3, 2);

        assertThat(cropped.width()).isEqualTo(2);
        assertThat(cropped.pixels()).containsExactly(2, 3, 5, 6);
    }

    @Test
    void refusesACropOutsideTheCapture() {
        assertThatThrownBy(() -> new Capture(3, 2, new int[6]).crop(1, 0, 4, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("padding frames the picture on every side and leaves it where it was, shifted by the margin")
    void padsAllRound() {
        final var padded = new Capture(2, 1, new int[]{1, 2}).padded(1, 9);

        assertThat(padded.width()).isEqualTo(4);
        assertThat(padded.height()).isEqualTo(3);
        assertThat(padded.pixels()).containsExactly(
                9, 9, 9, 9,
                9, 1, 2, 9,
                9, 9, 9, 9);
    }

    @Test
    @DisplayName("an all-black capture is what an unpainted window looks like")
    void tellsAnUnpaintedCaptureApart() {
        assertThat(new Capture(2, 2, new int[4]).isUniform()).isTrue();
        assertThat(new Capture(2, 2, new int[]{0, 0, 0, 0xFFFFFF}).isUniform()).isFalse();
    }
}
