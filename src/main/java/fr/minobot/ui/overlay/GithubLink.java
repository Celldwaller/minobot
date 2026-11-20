package fr.minobot.ui.overlay;

import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;

/**
 * The project's page, at the foot of the sheet: the GitHub mark and the page's address, the way a site
 * links its socials in its footer.
 *
 * <p>Quiet until pointed at — there to be found, like a caption — since it is about Minobot and not about
 * the game. The mark is the shipped {@code github.png} used as a <strong>stencil</strong>: its
 * own dark grey would vanish on the sheet, so only its shape is kept and it is filled with the theme's.
 */
public final class GithubLink {

    private static final Logger log = LoggerFactory.getLogger(GithubLink.class);

    private static final URI REPOSITORY = URI.create("https://github.com/Celldwaller/minobot");

    private static final String MARK_RESOURCE = "/github.png";

    /** The mark's side: the height of the small text beside it, as an icon in a footer is. */
    private static final int MARK = 14;

    /**
     * The shipped file is 1024 pixels wide, and a bilinear draw straight from there down to fourteen skips
     * most of it and comes out ragged; it is smoothed down once, to this, and drawn from here.
     */
    private static final int STENCIL = 128;

    /** The stencil, or {@code null} when there is no {@code github.png}: the address then stands alone. */
    private final BufferedImage stencil = loadStencil();

    /** Run before the browser opens: the panel is always on top, and would cover the page it opened. */
    private final Runnable beforeOpening;

    public GithubLink(Runnable beforeOpening) {
        this.beforeOpening = beforeOpening;
    }

    public JComponent build(Scale scale) {
        return new Link(scale);
    }

    private final class Link extends JComponent {

        /** The address without its scheme, as a footer shows a social handle: the place, not a protocol. */
        private static final String NAME = REPOSITORY.getHost() + REPOSITORY.getPath();

        private final Scale scale;
        private boolean hovered;

        private Link(Scale scale) {
            this.scale = scale;
            setFont(scale.font(Fonts.MEDIUM, Metrics.SMALL));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            final var size = new Dimension(markWidth() + getFontMetrics(getFont()).stringWidth(NAME),
                    scale.px(Metrics.ROW));
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (contains(event.getPoint())) {
                        open();
                    }
                }
            });
        }

        /** The mark and the gap after it, or nothing at all when there is no mark to draw. */
        private int markWidth() {
            return stencil == null ? 0 : scale.px(MARK) + scale.px(Metrics.GAP - 2);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var color = hovered ? Theme.TEXT_SOFT : Theme.FAINT;
            final var canvas = Draw.smooth(graphics);

            if (stencil != null) {
                final var side = scale.px(MARK);
                canvas.drawImage(tinted(side, color), 0, (getHeight() - side) / 2, null);
            }

            final var fm = canvas.getFontMetrics(getFont());
            canvas.setFont(getFont());
            canvas.setColor(color);
            canvas.drawString(NAME, markWidth(), (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            canvas.dispose();
        }
    }

    /** The stencil at a side, filled with one colour where the mark is opaque and clear everywhere else. */
    private BufferedImage tinted(int side, Color color) {
        final var image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        final var canvas = image.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        canvas.drawImage(stencil, 0, 0, side, side, null);
        canvas.setComposite(AlphaComposite.SrcIn);
        canvas.setColor(color);
        canvas.fillRect(0, 0, side, side);
        canvas.dispose();
        return image;
    }

    private void open() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log.warn("This desktop cannot open a browser: {}", REPOSITORY);
            return;
        }
        beforeOpening.run();
        // The browser can take its time to come up, and the caller is Swing's thread.
        Thread.ofVirtual().name("overlay-open-github").start(() -> {
            try {
                Desktop.getDesktop().browse(REPOSITORY);
            } catch (IOException | UnsupportedOperationException e) {
                log.warn("Could not open {}: {}", REPOSITORY, e.getMessage());
            }
        });
    }

    /** Read once, off the classpath like the logo; absent or unreadable, the name stands alone. */
    private static BufferedImage loadStencil() {
        final var resource = GithubLink.class.getResource(MARK_RESOURCE);
        if (resource == null) {
            log.debug("No {} on the classpath: the GitHub link shows its address alone.", MARK_RESOURCE);
            return null;
        }

        try {
            final var full = ImageIO.read(resource);
            if (full == null) {
                log.warn("Could not decode the GitHub mark {}.", MARK_RESOURCE);
                return null;
            }
            final var small = new BufferedImage(STENCIL, STENCIL, BufferedImage.TYPE_INT_ARGB);
            final var canvas = small.createGraphics();
            canvas.drawImage(full.getScaledInstance(STENCIL, STENCIL, Image.SCALE_SMOOTH), 0, 0, null);
            canvas.dispose();
            return small;
        } catch (IOException e) {
            log.warn("Could not read the GitHub mark {}: {}", MARK_RESOURCE, e.getMessage());
            return null;
        }
    }
}
