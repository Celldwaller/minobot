package fr.minobot.win32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The {@code lParam} packing is the likeliest silent bug of the whole port: a wrong pack does not
 * fail, it clicks somewhere else. {@code win32api.MAKELONG} handled the negative coordinates of a
 * click outside the client area for free; here it is hand-written, so it is pinned down here.
 */
class Win32Test {

    /** The low word of the lParam, read back the way Windows reads it: a signed 16-bit x. */
    private static int x(long lparam) {
        return (short) (lparam & 0xFFFF);
    }

    /** The high word of the lParam, read back as a signed 16-bit y. */
    private static int y(long lparam) {
        return (short) ((lparam >> 16) & 0xFFFF);
    }

    @Test
    @DisplayName("packs a positive coordinate pair into the two halves of the lParam")
    void packsPositiveCoordinates() {
        final var lparam = Win32.makeLParam(100, 200);

        assertThat(lparam).isEqualTo(0x00C80064L);
        assertThat(x(lparam)).isEqualTo(100);
        assertThat(y(lparam)).isEqualTo(200);
    }

    @Test
    @DisplayName("a key's press carries its scan code once, its release the two state bits")
    void packsAKey() {
        assertThat(Win32.keyLParam(0x01, false)).isEqualTo(0x00010001L);
        assertThat(Win32.keyLParam(0x01, true)).isEqualTo(0xC0010001L);
    }

    @Test
    void packsTheOrigin() {
        assertThat(Win32.makeLParam(0, 0)).isZero();
    }

    @Test
    @DisplayName("a negative x does not bleed into the y half")
    void negativeXStaysInItsHalf() {
        final var lparam = Win32.makeLParam(-5, 10);

        // The naive ((long) y << 16) | x would sign-extend x over the whole word and destroy y.
        assertThat(lparam).isEqualTo(0x000AFFFBL);
        assertThat(x(lparam)).isEqualTo(-5);
        assertThat(y(lparam)).isEqualTo(10);
    }

    @Test
    @DisplayName("a negative y is truncated to 16 bits, not sign-extended into the upper half")
    void negativeYStaysInItsHalf() {
        final var lparam = Win32.makeLParam(10, -5);

        assertThat(lparam).isEqualTo(0xFFFB000AL);
        assertThat(x(lparam)).isEqualTo(10);
        assertThat(y(lparam)).isEqualTo(-5);
    }

    @Test
    @DisplayName("both coordinates negative — a click above and left of the client area")
    void packsBothNegative() {
        final var lparam = Win32.makeLParam(-1, -1);

        assertThat(lparam).isEqualTo(0xFFFFFFFFL);
        assertThat(x(lparam)).isEqualTo(-1);
        assertThat(y(lparam)).isEqualTo(-1);
    }

    @Test
    @DisplayName("the packed value never carries sign bits above bit 31")
    void packedValueFitsInThirtyTwoBits() {
        for (final var coordinate : new int[]{-32768, -1, 0, 1, 32767}) {
            assertThat(Win32.makeLParam(coordinate, coordinate) >>> 32).as("upper 32 bits must be clear for " + coordinate).isZero();
        }
    }

    @Test
    void roundTripsEveryCornerOfTheCoordinateSpace() {
        final var coordinates = new int[]{-32768, -1000, -1, 0, 1, 1000, 32767};
        for (final var px : coordinates) {
            for (final var py : coordinates) {
                final var lparam = Win32.makeLParam(px, py);
                assertThat(x(lparam)).as("x of (" + px + ", " + py + ")").isEqualTo(px);
                assertThat(y(lparam)).as("y of (" + px + ", " + py + ")").isEqualTo(py);
            }
        }
    }

    @Test
    void mapsFunctionKeysToContiguousVirtualKeyCodes() {
        assertThat(Win32.functionKey(1)).isEqualTo(0x70);
        assertThat(Win32.functionKey(8)).isEqualTo(0x77);
        assertThat(Win32.functionKey(12)).isEqualTo(0x7B);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> Win32.functionKey(0));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> Win32.functionKey(13));
    }
}
