package fr.minobot.ui.banner;

import fr.minobot.i18n.Language;
import fr.minobot.ui.BannerActions;
import fr.minobot.ui.BannerContent;
import fr.minobot.ui.BannerView;
import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;
import fr.minobot.win32.Rect;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The auto-pass banner, in Swing. The only class in the banner that knows Swing exists — a sibling of
 * {@code SwingOverlay} and {@code SwingToastStack}, held to the same discipline. It shares the design
 * system ({@link Scale} for every size, {@link Draw} for the smoothing, {@link Theme} for the palette),
 * so the three surfaces read at the same size on the same monitor.
 *
 * <p><strong>It is a pill, and the whisper cards are rectangles.</strong> That is not decoration: the two
 * surfaces are on screen at the same time, over the same game, and they mean opposite things. A whisper
 * is an <em>event</em> — it arrived, it is read, it goes — so it is a card at the left edge that stacks
 * and expires. The banner is a <em>state</em> — it is true for as long as it is drawn — so it is one
 * rounded pill at the top centre, in the ember, with a live dot and its own way out. A player must never
 * have to read either one to know which it is.
 *
 * <p><strong>It must never take the foreground.</strong> Like the panel and the whisper stack, it would
 * otherwise land between two keystrokes of the invitation relay or a turn-pass — the very feature it
 * announces. Hence {@code setFocusableWindowState(false)}, and hence nothing here is typed: the banner is
 * read, or turned off with the mouse. <strong>Do not add {@code WS_EX_NOACTIVATE} to make sure</strong>:
 * Swing already does this one.
 *
 * <p>Unlike the panel it is <strong>not</strong> the whole game: it is sized to the one pill and pinned to
 * the top of the game, centred left to right with a little padding below the top edge, so it blocks only a
 * narrow band. Its one click — <em>Turn off</em> — is routed by hand, as the whisper cards' are.
 *
 * <p>The follow loop calls {@link #show}, {@link #moveTo} and {@link #hide} from a virtual thread, and
 * every one of them hands its work to the event dispatch thread, where Swing lives.
 */
public final class SwingBanner implements BannerView {

    /** The room inside the pill, around its contents: {@code PAD_X} at each end, {@code PAD_Y} above and
     *  below. The height is measured from the tallest thing on the row plus {@code PAD_Y} twice, so the
     *  pill always clears its own text rather than being pinned to a guessed constant. */
    private static final int PAD_X = 14;
    private static final int PAD_Y = 6;

    /** The breathing room the window keeps around the pill, so its rounded end is not clipped. */
    private static final int MARGIN = 8;

    /** How far below the game's top edge the pill hangs — padding, not flush to the top. */
    private static final int TOP_PADDING = 18;

    /** The dot that marks the banner as a live state, in the ember — reads "running" at a glance. */
    private static final int DOT = 7;

    /** The room inside the <em>Turn off</em> button, which is a pill of its own inside the pill. */
    private static final int BUTTON_PAD_X = 11;
    private static final int BUTTON_PAD_Y = 4;

    /** What the button says, by key. The one phrase the view owns: it names its own click, not the feature. */
    private static final String TURN_OFF = "banner.turn_off";

    private final BannerActions actions;

    /** Touched on the event dispatch thread only. Built on first show: there may never be one. */
    private JWindow window;
    private Banner banner;

    /** What every size was computed with, and where the banner was last hung. */
    private Scale scale;
    private Rect anchor;

    /** Read from the follow thread, so it cannot live inside Swing. */
    private volatile boolean visible;

    public SwingBanner(BannerActions actions) {
        this.actions = actions;
    }

    @Override
    public void show(BannerContent content, Rect anchor) {
        visible = true;
        SwingUtilities.invokeLater(() -> draw(content, anchor));
    }

    @Override
    public void moveTo(Rect anchor) {
        SwingUtilities.invokeLater(() -> {
            if (window == null || !window.isVisible()) {
                return;
            }
            this.anchor = anchor;
            place();
        });
    }

    @Override
    public void hide() {
        visible = false;
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.setVisible(false);
            }
        });
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    // ------------------------------------------------------------------ the event dispatch thread

    private void draw(BannerContent content, Rect anchor) {
        if (window == null) {
            build();
        }

        this.scale = new Scale(content.scale());
        this.anchor = anchor;
        banner.setContent(content);

        final var size = bannerSize(content);
        window.setSize(size);
        banner.setPreferredSize(size);

        place();
        window.setVisible(true);
        window.revalidate();
        window.repaint();
    }

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);

        // The whole point: the banner receives the mouse, and never the activation.
        window.setFocusableWindowState(false);

        translucentIfSupported();

        banner = new Banner();
        window.setContentPane(banner);
    }

    /**
     * A translucent background needs the desktop to support per-pixel alpha. It normally does — but a
     * remote session or a disabled compositor does not, and there the banner is simply drawn on a black
     * band rather than not at all.
     */
    private void translucentIfSupported() {
        final var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            window.setBackground(new Color(0, 0, 0, 0));
        }
    }

    /** The box the pill needs: the dot, the heading, the message, the button, the padding and the margin. */
    private Dimension bannerSize(BannerContent content) {
        final var heading = banner.getFontMetrics(headingFont());
        final var message = banner.getFontMetrics(messageFont());
        final var button = banner.getFontMetrics(buttonFont());

        final var buttonHeight = button.getAscent() + button.getDescent() + 2 * px(BUTTON_PAD_Y);
        final var rowHeight = Math.max(buttonHeight,
                Math.max(heading.getAscent() + heading.getDescent(),
                        message.getAscent() + message.getDescent()));
        final var pillHeight = rowHeight + 2 * px(PAD_Y);

        final var pillWidth = 2 * px(PAD_X)
                + px(DOT) + px(Metrics.GAP + 4)
                + heading.stringWidth(content.heading()) + px(Metrics.GAP + 4)
                + message.stringWidth(content.message()) + px(Metrics.GAP + 4)
                + button.stringWidth(content.words().get(TURN_OFF)) + 2 * px(BUTTON_PAD_X);

        return new Dimension(pillWidth + 2 * px(MARGIN), pillHeight + 2 * px(MARGIN));
    }

    /** Pins the window to the top of the game, centred left to right, a little below the top edge. */
    private void place() {
        if (anchor == null) {
            return;
        }
        final var left = anchor.left() + (anchor.width() - window.getWidth()) / 2;
        window.setLocation(left, anchor.top() + px(TOP_PADDING) - px(MARGIN));
    }

    private int px(int natural) {
        return scale.px(natural);
    }

    private Font headingFont() {
        return scale.tracked(scale.font(Fonts.CONDENSED, Metrics.SMALL), Metrics.BADGE_TRACKING);
    }

    private Font messageFont() {
        return scale.font(Fonts.MEDIUM, Metrics.LABEL);
    }

    private Font buttonFont() {
        return scale.font(Fonts.SEMIBOLD, Metrics.SMALL);
    }

    // ------------------------------------------------------------------ the pill

    /**
     * The pill itself: it paints the live dot, the heading, the message and the <em>Turn off</em> button,
     * and remembers where the button landed so a click can be told whether it hit it. Painting and
     * hit-testing read the same rectangle, so they can never disagree.
     */
    private final class Banner extends JPanel {

        private BannerContent content = new BannerContent(1, Language.FR, "", "");

        /** Where the button was last painted, for the mouse to test against; {@code null} until drawn. */
        private Rectangle button;

        /** Whether the pointer is over the button, so it can be lit. */
        private boolean buttonHovered;

        private Banner() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            final var mouse = new Clicks();
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void setContent(BannerContent content) {
            this.content = content;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            final var canvas = Draw.smooth(graphics);

            // The pill fills the window less its margin, in both dimensions, so the drawn height tracks
            // the window the same way the width does rather than a constant that could disagree with it.
            final var x = px(MARGIN);
            final var y = px(MARGIN);
            final var width = getWidth() - 2 * px(MARGIN);
            final var height = getHeight() - 2 * px(MARGIN);

            // Fully rounded: a radius of its own height is what makes a pill a pill at any scale.
            canvas.setColor(Theme.ROW);
            canvas.fillRoundRect(x, y, width, height, height, height);
            canvas.setColor(Theme.ACCENT_EDGE);
            canvas.drawRoundRect(x, y, width - 1, height - 1, height, height);

            var cursor = x + px(PAD_X);
            Draw.dot(canvas, cursor, getHeight(), px(DOT), Theme.ACCENT);
            cursor += px(DOT) + px(Metrics.GAP + 4);

            cursor += drawText(canvas, headingFont(), Theme.ACCENT_HOVER, content.heading(), cursor);
            cursor += px(Metrics.GAP + 4);
            cursor += drawText(canvas, messageFont(), Theme.MUTED, content.message(), cursor);
            cursor += px(Metrics.GAP + 4);

            paintButton(canvas, cursor, y, height);
            canvas.dispose();
        }

        /** One run of text, baseline-centred in the pill. @return how wide it was */
        private int drawText(Graphics2D canvas, Font font, Color color, String text, int x) {
            canvas.setFont(font);
            canvas.setColor(color);
            final var metrics = canvas.getFontMetrics();
            canvas.drawString(text, x, baseline(metrics.getAscent(), metrics.getDescent()));
            return metrics.stringWidth(text);
        }

        /** The way out: a filled ember pill inside the outlined one, so it reads as the thing to press. */
        private void paintButton(Graphics2D canvas, int x, int top, int pillHeight) {
            canvas.setFont(buttonFont());
            final var metrics = canvas.getFontMetrics();
            final var turnOff = content.words().get(TURN_OFF);
            final var width = metrics.stringWidth(turnOff) + 2 * px(BUTTON_PAD_X);
            final var height = metrics.getAscent() + metrics.getDescent() + 2 * px(BUTTON_PAD_Y);

            button = new Rectangle(x, top + (pillHeight - height) / 2, width, height);

            canvas.setColor(buttonHovered ? Theme.ACCENT_HOVER : Theme.ACCENT);
            canvas.fillRoundRect(button.x, button.y, button.width, button.height, height, height);

            canvas.setColor(Theme.TEXT);
            canvas.drawString(turnOff, button.x + px(BUTTON_PAD_X),
                    button.y + px(BUTTON_PAD_Y) + metrics.getAscent());
        }

        private int baseline(int ascent, int descent) {
            return (getHeight() + ascent - descent) / 2;
        }

        private final class Clicks extends MouseAdapter {

            @Override
            public void mouseReleased(MouseEvent event) {
                if (button != null && button.contains(event.getPoint())) {
                    actions.turnOff();
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                final var over = button != null && button.contains(event.getPoint());
                if (over != buttonHovered) {
                    buttonHovered = over;
                    setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }
        }
    }
}
