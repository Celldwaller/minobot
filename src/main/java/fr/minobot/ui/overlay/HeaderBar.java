package fr.minobot.ui.overlay;

import fr.minobot.app.Feature;
import fr.minobot.i18n.Language;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.Segmented;
import fr.minobot.ui.components.labels.KeyChip;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

/**
 * The line that opens the panel: the application's mark on the left, the language selector and the key
 * that opens and closes the panel on the right, and the close cross beyond that (pinned there by the
 * orchestrator, which owns the one way out).
 *
 * <p>It is a <strong>line</strong>, not a banner. The logo used to have a tall band of its own at the top
 * of the card, which said the application's name to a player who had already opened the application —
 * space spent on the one thing they were certain of. Shrunk to a line, it still names the surface, and
 * the room goes to what the panel is actually for.
 *
 * <p>The hotkey rides here rather than in the drawer because it is the only keybind that is <em>about the
 * panel itself</em>: a player who has just found the panel with the mouse learns, in the same glance,
 * how to open it without one. It is drawn, not clickable — it is rebound in the drawer, with the rest.
 */
public final class HeaderBar {

    private static final Logger log = LoggerFactory.getLogger(HeaderBar.class);

    /** The application's logo, on the classpath so it rides inside the jar. Absent is not an error. */
    private static final String LOGO_RESOURCE = "/logo.png";

    /** The mark's side — a line's height, not a banner's. */
    private static final int MARK = 30;

    /** The wordmark's letters, spread wider than a heading's: it stands in for a picture, not a label. */
    private static final double WORDMARK_TRACKING = 0.18;

    /** The logo, read once; {@code null} when there is no {@code logo.png} to fall back from. */
    private final BufferedImage logo = loadLogo();

    private final OverlayActions actions;

    public HeaderBar(OverlayActions actions) {
        this.actions = actions;
    }

    /** @param trailing the close cross, added last so it sits at the far right of the line */
    public JComponent build(Scale scale, OverlayContent content, JComponent trailing) {
        final var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.px(MARK)));

        if (logo != null) {
            row.add(new Mark(scale));
            row.add(Box.createHorizontalStrut(scale.px(Metrics.GAP + 4)));
        }
        row.add(wordmark(scale));
        row.add(Box.createHorizontalGlue());
        row.add(languages(scale, content.language()));
        row.add(Box.createHorizontalStrut(scale.px(Metrics.GAP + 4)));
        row.add(KeyChip.of(scale, content.words(), content.hotkeys().getOrDefault(Feature.OVERLAY, "")));
        row.add(Box.createHorizontalStrut(scale.px(Metrics.GAP)));
        row.add(trailing);
        return row;
    }

    /**
     * The language selector. In the header because it is about the whole panel and not one card of it,
     * and labelled by codes, which read the same in every language: a player who picked the wrong one
     * still finds the way back.
     */
    private JComponent languages(Scale scale, Language current) {
        final var languages = Language.values();
        final var selector = Segmented.of(scale,
                Arrays.stream(languages).map(Language::name).toList(),
                current.ordinal(),
                index -> actions.changeLanguage(languages[index]));
        // The row is a BoxLayout, which would stretch the track to the line's height and past its labels.
        selector.setMaximumSize(selector.getPreferredSize());
        return selector;
    }

    /** The name, spread out — and the whole of the mark when no {@code logo.png} was shipped. */
    private static JLabel wordmark(Scale scale) {
        final var wordmark = new JLabel("MINOBOT");
        wordmark.setForeground(Theme.MUTED);
        wordmark.setFont(scale.tracked(scale.font(Fonts.CONDENSED, Metrics.WORDMARK), WORDMARK_TRACKING));
        return wordmark;
    }

    /** The logo, drawn to fit its square with its proportions kept, on the sheet rather than in a box. */
    private final class Mark extends JComponent {

        private Mark(Scale scale) {
            final var side = scale.px(MARK);
            setPreferredSize(new Dimension(side, side));
            setMaximumSize(new Dimension(side, side));
            setMinimumSize(new Dimension(side, side));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var fit = Math.min(getWidth() / (double) logo.getWidth(),
                    getHeight() / (double) logo.getHeight());
            final var width = (int) Math.round(logo.getWidth() * fit);
            final var height = (int) Math.round(logo.getHeight() * fit);

            final var canvas = Draw.smooth(graphics);
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.drawImage(logo, (getWidth() - width) / 2, (getHeight() - height) / 2,
                    width, height, null);
            canvas.dispose();
        }
    }

    /**
     * The logo, or {@code null} if none was shipped.
     *
     * <p>Read once, off the classpath so a packaged {@code Minobot.exe} finds it where a loose file in
     * {@code assets/} would be lost. Its absence is not a failure — the wordmark stands alone — so a
     * missing file is a debug line, and a corrupt one does not bring the panel down.
     */
    private static BufferedImage loadLogo() {
        final var resource = HeaderBar.class.getResource(LOGO_RESOURCE);
        if (resource == null) {
            log.debug("No {} on the classpath: the header shows its wordmark alone.", LOGO_RESOURCE);
            return null;
        }

        try {
            return ImageIO.read(resource);
        } catch (IOException e) {
            log.warn("Could not read the overlay logo {}: {}", LOGO_RESOURCE, e.getMessage());
            return null;
        }
    }
}
