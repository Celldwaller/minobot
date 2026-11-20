package fr.minobot.ui.components.labels;

import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Emphasis;
import fr.minobot.ui.utils.Emphasis.Word;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.util.List;

/**
 * Secondary text under a control — what a drag does, what a click on a whisper does, what a key may not
 * be. There to be found once and then never read again, which is why it is the quiet weight and not the
 * bold one.
 */
public final class Hint extends JLabel {

    public Hint(Scale scale, String text) {
        super(text);
        this.setForeground(Theme.FAINT);
        this.setFont(scale.font(Fonts.REGULAR, Metrics.SMALL));
        this.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /**
     * A hint broken into lines no wider than {@code width} — for a hint beside controls, whose room is
     * whatever they leave, in whichever language and at whichever size — its {@link Emphasis} words
     * standing out: the one thing in the hint to do.
     *
     * @param width in pixels, already through a {@link Scale}
     */
    public static List<JComponent> wrapped(Scale scale, String text, int width) {
        final var regular = scale.font(Fonts.REGULAR, Metrics.SMALL);
        final var strong = scale.font(Fonts.SEMIBOLD, Metrics.SMALL);
        final var probe = new Hint(scale, "");
        return Emphasis.wrap(text, probe.getFontMetrics(regular), probe.getFontMetrics(strong), width).stream()
                .<JComponent>map(line -> new Line(regular, strong, line))
                .toList();
    }

    /** One line of a wrapped hint, its words drawn one after the other in their own weight. */
    private static final class Line extends JComponent {

        private final Font regular;
        private final Font strong;
        private final List<Word> words;

        private Line(Font regular, Font strong, List<Word> words) {
            this.regular = regular;
            this.strong = strong;
            this.words = List.copyOf(words);
            setAlignmentX(Component.LEFT_ALIGNMENT);

            final var size = new Dimension(
                    Emphasis.width(this.words, getFontMetrics(regular), getFontMetrics(strong)),
                    getFontMetrics(regular).getHeight());
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var space = getFontMetrics(regular).stringWidth(" ");
            final var baseline = getFontMetrics(regular).getAscent();
            var x = 0;
            for (var i = 0; i < words.size(); i++) {
                final var word = words.get(i);
                x += i > 0 && word.spaced() ? space : 0;
                canvas.setFont(word.strong() ? strong : regular);
                canvas.setColor(word.strong() ? Theme.TEXT_SOFT : Theme.FAINT);
                canvas.drawString(word.text(), x, baseline);
                x += canvas.getFontMetrics().stringWidth(word.text());
            }
            canvas.dispose();
        }
    }
}
