package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.Frame;
import fr.minobot.win32.TextReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads, off a capture of a game window, the last lines of its chat — where the fight log is written:
 * {@code Foxtrot lance Flèche d'Expiation.}, {@code Foxtrot attaque avec Sceptre du Minotot.}.
 *
 * <p>The chat's lines are anchored to its bottom at a fixed pitch, the newest last: each line is cut out
 * on its own and handed to the OCR alone, which reads it exactly where the whole box read as one image
 * comes back with its lines out of order. A line is read once: the same pixels again — the same line, or
 * the same line scrolled one line up — answer what was read then, so a look with nothing new costs a
 * comparison of pixels and no OCR at all, and a look with one new line costs one line's OCR (3 to 12 ms,
 * measured).
 *
 * <p>The box is checked before it is read: the beige band under its last line must be there, which it is
 * not while the chat is folded away or something covers it.
 *
 * <p>The chat is where the window's {@link Hud} puts it. Positions are the stage's (see {@link GameStage}),
 * measured on a 2560x1369 window. It remembers the lines it read, so each window's chat is read by a reader
 * of its own.
 */
public final class ChatLog {

    /** The chat's background. */
    private static final int BEIGE = 0xD5CFAA;

    /**
     * The classic chat, under the map: six lines at its smallest. Its right edge is the longest line the
     * fight log writes; the filters' coloured boxes sit further right on its top line.
     */
    static final Pane CLASSIC = new Pane(17.7, 322.0, 528.0, 12.18, 530.0, 6);

    /**
     * The 16:9 chat's upper pane, left of the stage, where the fight log is written: a smaller type, and
     * twenty-four lines of which the last ten are read — a spell that hits every fighter writes a line for
     * each. Its right edge is left of its scroll bar.
     */
    static final Pane WIDE = new Pane(-147.2, -10.8, 217.69, 7.7166, 219.3, 10);

    /** The OCR finds nothing in text that touches its image's edge. */
    private static final int MARGIN = 20;

    /** Enough to hold every line on screen twice over: a line scrolled up must still be known. */
    private static final int REMEMBERED = 4 * WIDE.lines();

    private final TextReader reader;

    /** A line's pixels and what they read, least recently used first. Only the reading thread touches it. */
    private final Map<Line, String> read = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Line, String> eldest) {
            return size() > REMEMBERED;
        }
    };

    public ChatLog(TextReader reader) {
        this.reader = reader;
    }

    /**
     * The chat's last lines, oldest first; a line with nothing on it is blank. Empty when there is no chat
     * to read, or the OCR failed on a line — a list with a hole in it would say a line was new that was not.
     */
    public Optional<List<String>> lines(Frame frame) {
        final var pane = Hud.of(frame) == Hud.WIDE ? WIDE : CLASSIC;
        final var stage = GameStage.of(frame);
        final var left = stage.x(pane.left());
        if (left < 0) {
            return Optional.empty();
        }
        return Region.of(frame, left, stage.y(pane.top()), stage.x(pane.right()), stage.y(pane.checkRow()) + 1)
                .filter(box -> pane.isUp(box, stage))
                .flatMap(box -> read(box, stage, pane));
    }

    private Optional<List<String>> read(Region box, GameStage stage, Pane pane) {
        final var lines = new ArrayList<String>(pane.lines());
        for (var index = pane.lines() - 1; index >= 0; index--) {
            final var pixels = box.crop(stage.x(pane.left()), stage.y(pane.bottom() - (index + 1) * pane.pitch()),
                    stage.x(pane.right()), stage.y(pane.bottom() - index * pane.pitch()));
            final var text = text(pixels);
            if (text.isEmpty()) {
                return Optional.empty();
            }
            lines.add(text.get());
        }
        return Optional.of(lines);
    }

    private Optional<String> text(Capture pixels) {
        final var line = new Line(pixels);
        final var known = read.get(line);
        if (known != null) {
            return Optional.of(known);
        }
        if (line.isBlank()) {
            return Optional.of("");
        }
        final var text = reader.read(pixels.padded(MARGIN, BEIGE)).map(String::strip);
        text.ifPresent(it -> read.put(line, it));
        return text;
    }

    /**
     * Where a chat's lines are, in stage coordinates: left of the text and right of it, under the last line
     * and from one line to the next, a row of the beige under the last line — checked at both ends and in
     * the middle — and how many lines are read.
     */
    record Pane(double left, double right, double bottom, double pitch, double checkRow, int lines) {

        double top() {
            return bottom - lines * pitch;
        }

        boolean isUp(Region box, GameStage stage) {
            final var y = stage.y(checkRow);
            for (final var column : new double[]{left, (left + right) / 2, right - 1}) {
                if (!box.is(stage.x(column), y, BEIGE)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A line's pixels, compared whole: a {@link Capture} is compared by identity. */
    private record Line(int width, int[] pixels, int hash) {

        Line(Capture capture) {
            this(capture.width(), capture.pixels(), Arrays.hashCode(capture.pixels()));
        }

        boolean isBlank() {
            for (final var pixel : pixels) {
                if (pixel != BEIGE) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Line line && line.width == width && line.hash == hash
                    && Arrays.equals(line.pixels, pixels);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
