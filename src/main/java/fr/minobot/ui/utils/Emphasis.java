package fr.minobot.ui.utils;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text with words that stand out, as the phrases write it — {@code **fermez** l'overlay} — broken into
 * lines that fit. The words between two {@code **} are drawn in a heavier weight by whoever paints them;
 * a {@code \n} starts a line wherever it falls.
 */
public final class Emphasis {

    private static final String STRONG = "**";

    private Emphasis() {
    }

    /** A word, whether it stands out, and whether a space comes before it — none does after a {@code **}. */
    public record Word(String text, boolean strong, boolean spaced) {
    }

    /**
     * The lines of a text no wider than {@code width}. A word longer than the line is not cut: it gets a
     * line of its own.
     *
     * @param width in pixels, already through a {@link Scale}
     */
    public static List<List<Word>> wrap(String text, FontMetrics regular, FontMetrics strong, int width) {
        final var space = regular.stringWidth(" ");
        final var lines = new ArrayList<List<Word>>();
        for (final var paragraph : text.split("\n")) {
            var line = new ArrayList<Word>();
            var used = 0;
            for (final var word : words(paragraph)) {
                final var wide = (word.strong() ? strong : regular).stringWidth(word.text());
                final var gap = word.spaced() ? space : 0;
                if (!line.isEmpty() && word.spaced() && used + gap + wide > width) {
                    lines.add(line);
                    line = new ArrayList<>();
                    used = 0;
                }
                used += (line.isEmpty() ? 0 : gap) + wide;
                line.add(word);
            }
            lines.add(line);
        }
        return lines;
    }

    /** How wide a line is drawn, its first word never preceded by a space. */
    public static int width(List<Word> line, FontMetrics regular, FontMetrics strong) {
        final var space = regular.stringWidth(" ");
        var width = 0;
        for (var i = 0; i < line.size(); i++) {
            final var word = line.get(i);
            width += (i > 0 && word.spaced() ? space : 0) + (word.strong() ? strong : regular).stringWidth(word.text());
        }
        return width;
    }

    private static List<Word> words(String paragraph) {
        final var words = new ArrayList<Word>();
        final var parts = paragraph.split(Pattern.quote(STRONG), -1);
        var spaced = false;
        for (var i = 0; i < parts.length; i++) {
            final var tokens = parts[i].split(" ", -1);
            for (var j = 0; j < tokens.length; j++) {
                if (j > 0) {
                    spaced = true;
                }
                if (!tokens[j].isEmpty()) {
                    words.add(new Word(tokens[j], i % 2 == 1, spaced));
                    spaced = false;
                }
            }
        }
        return words;
    }
}
