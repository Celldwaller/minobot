package fr.minobot.ui.components.labels;

import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JLabel;
import java.awt.Component;
import java.util.ArrayList;
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
     * whatever they leave, in whichever language and at whichever size. A word longer than the line is
     * not cut: it gets a line of its own.
     *
     * @param width in pixels, already through a {@link Scale}
     */
    public static List<Hint> wrapped(Scale scale, String text, int width) {
        final var metrics = new Hint(scale, "").getFontMetrics(scale.font(Fonts.REGULAR, Metrics.SMALL));
        final var lines = new ArrayList<Hint>();
        var line = "";
        for (final var word : text.split(" ")) {
            final var longer = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && metrics.stringWidth(longer) > width) {
                lines.add(new Hint(scale, line));
                line = word;
            } else {
                line = longer;
            }
        }
        lines.add(new Hint(scale, line));
        return lines;
    }
}
