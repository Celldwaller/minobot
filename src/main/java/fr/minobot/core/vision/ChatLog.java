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
 * <p>Positions are the stage's (see {@link GameStage}), measured on a 2560x1369 window. It remembers the
 * lines it read, so each window's chat is read by a reader of its own.
 */
public final class ChatLog {

    /** The chat's background. */
    private static final int BEIGE = 0xD5CFAA;

    /** How many lines are read, from the bottom up: the six the chat shows at its smallest. */
    static final int LINES = 6;

    /**
     * Left of the text, and right of the longest line the fight log writes. The filters' coloured boxes sit
     * further right on the top line.
     */
    private static final double LEFT = 17.7;
    private static final double RIGHT = 322.0;

    /** Under the last line, and the pitch from one line to the next. */
    private static final double BOTTOM = 528.0;
    private static final double PITCH = 12.18;

    /** A row of the beige band under the last line, clear of any letter; checked at these columns. */
    private static final double CHECK_ROW = 530.0;
    private static final double[] CHECK_COLUMNS = {LEFT, 170.0, RIGHT - 1};

    /** The OCR finds nothing in text that touches its image's edge. */
    private static final int MARGIN = 20;

    /** Enough to hold every line on screen twice over: a line scrolled up must still be known. */
    private static final int REMEMBERED = 4 * LINES;

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
     * The chat's last {@value #LINES} lines, oldest first; a line with nothing on it is blank. Empty when
     * there is no chat to read, or the OCR failed on a line — a list with a hole in it would say a line was
     * new that was not.
     */
    public Optional<List<String>> lines(Frame frame) {
        final var stage = GameStage.of(frame);
        final var top = stage.y(BOTTOM - LINES * PITCH);
        return Region.of(frame, stage.x(LEFT), top, stage.x(RIGHT), stage.y(CHECK_ROW) + 1)
                .filter(box -> isUp(box, stage))
                .flatMap(box -> read(box, stage));
    }

    private Optional<List<String>> read(Region box, GameStage stage) {
        final var lines = new ArrayList<String>(LINES);
        for (var index = LINES - 1; index >= 0; index--) {
            final var pixels = box.crop(stage.x(LEFT), stage.y(BOTTOM - (index + 1) * PITCH),
                    stage.x(RIGHT), stage.y(BOTTOM - index * PITCH));
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

    private static boolean isUp(Region box, GameStage stage) {
        final var y = stage.y(CHECK_ROW);
        for (final var column : CHECK_COLUMNS) {
            if (!box.is(stage.x(column), y, BEIGE)) {
                return false;
            }
        }
        return true;
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
