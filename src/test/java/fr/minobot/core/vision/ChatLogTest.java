package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.TextReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static fr.minobot.core.vision.BannerCaptures.BEIGE;
import static fr.minobot.core.vision.BannerCaptures.INK;
import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paints the chat where the game draws it on a 2560x1369 window — its beige from x 410 to 1160, its last
 * line from y 1270 to 1300 and five above it every 30 — with a block of ink for each line's text, and reads
 * it with an OCR that tells the lines apart by where their ink begins.
 */
class ChatLogTest {

    /** The OCR: a line whose ink begins {@code 10 * n} pixels in reads {@code "line n"}. */
    private final List<Capture> read = new ArrayList<>();
    private final TextReader reader = image -> {
        read.add(image);
        for (var x = 0; x < image.width(); x++) {
            if (image.rgb(x, image.height() / 2) == INK) {
                return Optional.of(" line " + (x - 20) / 10 + " ");
            }
        }
        return Optional.of("");
    };

    /** A chat, its lines oldest first: {@code n} is the line's ink offset, a negative one a blank line. */
    private static Capture chat(int... lines) {
        final var capture = blank(2560, 1369);
        paint(capture, 410, 1115, 1160, 1320, BEIGE);
        for (var index = 0; index < lines.length; index++) {
            if (lines[index] >= 0) {
                final var top = 1270 - 30 * (lines.length - 1 - index);
                final var left = 410 + 10 * lines[index];
                paint(capture, left, top + 8, left + 120, top + 22, INK);
            }
        }
        return capture;
    }

    @Test
    @DisplayName("the last six lines come back oldest first, each read on its own")
    void readsTheLastLines() {
        assertThat(new ChatLog(reader).lines(chat(1, 2, 3, 4, 5, 6)))
                .contains(List.of("line 1", "line 2", "line 3", "line 4", "line 5", "line 6"));
        assertThat(read).hasSize(6).allSatisfy(line -> {
            assertThat(line.height()).isEqualTo(30 + 2 * 20);
            assertThat(line.rgb(0, 0)).as("the margin").isEqualTo(BEIGE);
        });
    }

    @Test
    @DisplayName("a blank line is blank, and the OCR is not asked")
    void blankLinesAreNotRead() {
        assertThat(new ChatLog(reader).lines(chat(-1, -1, -1, -1, 2, 3)))
                .contains(List.of("", "", "", "", "line 2", "line 3"));
        assertThat(read).hasSize(2);
    }

    @Test
    @DisplayName("a line already read is not read again, even scrolled up a line")
    void readsEachLineOnce() {
        final var chat = new ChatLog(reader);

        chat.lines(chat(1, 2, 3, 4, 5, 6));
        chat.lines(chat(1, 2, 3, 4, 5, 6));
        assertThat(read).hasSize(6);

        assertThat(chat.lines(chat(2, 3, 4, 5, 6, 7)))
                .contains(List.of("line 2", "line 3", "line 4", "line 5", "line 6", "line 7"));
        assertThat(read).hasSize(7);
    }

    @Test
    @DisplayName("of a frame, the layout's border is read, then the chat, once")
    void readsOneRegionOffTheFrame() {
        final var frame = RecordingFrame.of(chat(1, 2, 3, 4, 5, 6));

        assertThat(new ChatLog(reader).lines(frame)).isPresent();
        assertThat(frame.regions).hasSize(2).last().satisfies(region -> {
            assertThat(region.left()).isEqualTo(410);
            assertThat(region.right()).isEqualTo(1159);
            assertThat(region.top()).isEqualTo(1120);
            assertThat(region.bottom()).isEqualTo(1306);
        });
    }

    @Test
    @DisplayName("no chat, no OCR")
    void readsNothingWithoutAChat() {
        assertThat(new ChatLog(reader).lines(blank(2560, 1369))).isEmpty();
        assertThat(read).isEmpty();
    }

    @Test
    @DisplayName("a line the OCR fails on fails the whole read: a hole would make a line look new")
    void aFailedLineFailsTheRead() {
        final TextReader failing = _ -> Optional.empty();

        assertThat(new ChatLog(failing).lines(chat(1, 2, 3, 4, 5, 6))).isEmpty();
    }

    /**
     * A 16:9 window: the column's border, and its chat's upper pane — beige from x 0 to 347 — its last line
     * from y 517 to 536 and nine above it every 19.
     */
    private static Capture wideChat(int... lines) {
        final var capture = HudTest.wide();
        paint(capture, 0, 300, 348, 545, BEIGE);
        for (var index = 0; index < lines.length; index++) {
            final var top = 517 - 19 * (lines.length - 1 - index);
            final var left = 4 + 10 * lines[index];
            paint(capture, left, top + 3, left + 100, top + 15, INK);
        }
        return capture;
    }

    @Test
    @DisplayName("in the 16:9 layout, the last ten lines of the column's upper pane are read")
    void readsTheWideChat() {
        assertThat(new ChatLog(reader).lines(wideChat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
                .contains(List.of("line 1", "line 2", "line 3", "line 4", "line 5",
                        "line 6", "line 7", "line 8", "line 9", "line 10"));
        assertThat(read).allSatisfy(line -> assertThat(line.height()).isEqualTo(19 + 2 * 20));
    }

    @Test
    @DisplayName("in the 16:9 layout, the classic chat's place is not read")
    void ignoresTheClassicPlaceInTheWideLayout() {
        final var capture = chat(1, 2, 3, 4, 5, 6);
        paint(capture, 360, 0, 366, 1369, HudTest.BORDER);

        assertThat(new ChatLog(reader).lines(capture)).isEmpty();
    }
}
