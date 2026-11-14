package fr.minobot.ui.toast;

import fr.minobot.i18n.Language;
import fr.minobot.i18n.Words;
import fr.minobot.ui.Theme;
import fr.minobot.ui.ToastActions;
import fr.minobot.ui.ToastContent;
import fr.minobot.ui.ToastView;
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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The whisper stack, in Swing. The only class in the stack that knows Swing exists — the twin of
 * {@code SwingOverlay}, and held to the same discipline. It shares the overlay's design system
 * ({@link Scale} for every size, {@link Draw} for the smoothing and the close cross), so the two surfaces
 * read at the same size on the same monitor and drift nowhere apart.
 *
 * <p><strong>It must never take the foreground.</strong> Like the panel, it would otherwise land between
 * two keystrokes of the invitation relay. Hence {@code setFocusableWindowState(false)} — a window that
 * receives the mouse and never the activation — and hence, too, that <strong>nothing here is typed</strong>:
 * a whisper is read, gone to, or dismissed, all with the mouse. <strong>Do not add {@code WS_EX_NOACTIVATE}
 * to make sure</strong>: Swing already does this one, exactly as it does for the panel.
 *
 * <p>Unlike the panel, the window is <strong>not</strong> the whole game: it is sized to the stack alone
 * and pinned to the left edge of the game, centred top to bottom, so it blocks only a narrow band. The
 * cards are laid from a fixed base with the newest at the bottom, closest to the eye, and every click is
 * routed by hand — a card opens its whisper, its cross dismisses it — rather than through Swing's button
 * or drag machinery, which expects the focus this window does not have.
 *
 * <p>Two threads meet here as they do in the panel: the follow loop calls {@link #show}, {@link #moveTo}
 * and {@link #hide} from a virtual thread, and every one of them hands its work to the event dispatch
 * thread, where Swing lives.
 */
public final class SwingToastStack implements ToastView {

    /** One card, at scale 1: wide enough for a line of chat, tall enough for the three it carries. */
    private static final int CARD_WIDTH = 225;
    private static final int CARD_HEIGHT = 70;

    /** The breathing room the window keeps around the stack, so a card's rounded corner is not clipped. */
    private static final int MARGIN = 10;

    /** The padding inside a card, and the radius of its corners — tighter than the panel's own. */
    private static final int PADDING = 12;
    private static final int RADIUS = 10;

    /** The stripe down a card's left edge — the game's own toast wears one. */
    private static final int STRIPE = 3;

    /** The close cross in a card's corner: the mouse's way to take one down early. */
    private static final int CLOSE_SIZE = 14;

    /** What the header says a card is, around the name of the character it reached — a key. */
    private static final String HEADER = "toast.header";

    private final ToastActions actions;

    /** Touched on the event dispatch thread only. Built on first show: there may never be one. */
    private JWindow window;
    private Stack stack;

    /** What every size on the cards was computed with, and where the stack was last hung. */
    private Scale scale;
    private Rect anchor;

    /** The frame's words, in the language of the last draw. Touched on the event dispatch thread only. */
    private Words words = Words.in(Language.FR);

    /** Read from the follow thread, so it cannot live inside Swing. */
    private volatile boolean visible;

    public SwingToastStack(ToastActions actions) {
        this.actions = actions;
    }

    @Override
    public void show(ToastContent content, Rect anchor) {
        visible = true;
        SwingUtilities.invokeLater(() -> draw(content, anchor));
    }

    /**
     * Called many times a second while the player drags the window, so it does no more than it must: the
     * cards are already drawn, and only where the stack hangs has changed.
     */
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

    private void draw(ToastContent content, Rect anchor) {
        if (window == null) {
            build();
        }

        this.scale = new Scale(content.scale());
        this.words = content.words();
        this.anchor = anchor;
        stack.setCards(content.cards());

        final var size = stackSize(content.cards().size());
        window.setSize(size);
        stack.setPreferredSize(size);

        place();
        window.setVisible(true);
        window.revalidate();
        window.repaint();
    }

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);

        // The whole point: the stack receives the mouse, and never the activation.
        window.setFocusableWindowState(false);

        translucentIfSupported();

        stack = new Stack();
        window.setContentPane(stack);
    }

    /**
     * A translucent background needs the desktop to support per-pixel alpha. It normally does — but a
     * remote session or a disabled compositor does not, and there the stack is simply drawn on a black
     * band rather than not at all.
     */
    private void translucentIfSupported() {
        final var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            window.setBackground(new Color(0, 0, 0, 0));
        }
    }

    /** The box the whole stack needs: the cards, the gaps between them, and the margin around them. */
    private Dimension stackSize(int count) {
        final var height = count <= 0 ? 0
                : count * px(CARD_HEIGHT) + (count - 1) * px(Metrics.GAP) + 2 * px(MARGIN);
        return new Dimension(px(CARD_WIDTH) + 2 * px(MARGIN), height);
    }

    /** Pins the window to the left edge of the game, centred top to bottom. */
    private void place() {
        if (anchor == null) {
            return;
        }
        final var top = anchor.top() + (anchor.height() - window.getHeight()) / 2;
        window.setLocation(anchor.left(), top);
    }

    private int px(int natural) {
        return scale.px(natural);
    }

    // ------------------------------------------------------------------ the cards

    /**
     * The stack itself: it paints every card top to bottom — oldest first, so the newest sits at the
     * bottom base — and remembers where each landed, so a click can be told which card, and whether its
     * cross, it hit. Painting and hit-testing read the same placement list, so they can never disagree.
     */
    private final class Stack extends JPanel {

        private List<ToastContent.Card> cards = List.of();

        /** Where each card and its cross were last painted, for the mouse to test against. */
        private final List<Placed> placed = new ArrayList<>();

        /** The id whose cross the pointer is over, so it can be lit; {@code null} for none. */
        private String hoveredClose;

        private Stack() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final var mouse = new Clicks();
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void setCards(List<ToastContent.Card> cards) {
            this.cards = List.copyOf(cards);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            placed.clear();

            final var canvas = Draw.smooth(graphics);
            final var x = px(MARGIN);
            final var width = px(CARD_WIDTH);
            final var height = px(CARD_HEIGHT);

            var y = px(MARGIN);
            for (final var card : cards) {
                final var bounds = new Rectangle(x, y, width, height);
                final var close = new Rectangle(
                        bounds.x + bounds.width - px(PADDING) - px(CLOSE_SIZE),
                        bounds.y + px(PADDING) - px(2),
                        px(CLOSE_SIZE), px(CLOSE_SIZE));
                paintCard(canvas, card, bounds, close);
                placed.add(new Placed(card.id(), bounds, close));
                y += height + px(Metrics.GAP);
            }
            canvas.dispose();
        }

        private void paintCard(Graphics2D canvas, ToastContent.Card card, Rectangle bounds, Rectangle close) {
            canvas.setColor(Theme.SURFACE);
            canvas.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, px(RADIUS), px(RADIUS));
            canvas.setColor(Theme.EDGE);
            canvas.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1,
                    px(RADIUS), px(RADIUS));

            // The stripe, clipped to the card's rounded left edge. Grey, not amber: the amber is spent
            // once, on the header, and a second use of it would stop the header meaning anything.
            final var clip = canvas.getClip();
            canvas.setClip(new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height,
                    px(RADIUS), px(RADIUS)));
            canvas.setColor(Theme.DIM);
            canvas.fillRect(bounds.x, bounds.y, px(STRIPE), bounds.height);
            canvas.setClip(clip);

            final var textLeft = bounds.x + px(PADDING) + px(STRIPE);
            final var textRight = close.x - px(Metrics.GAP);
            final var bodyWidth = bounds.x + bounds.width - px(PADDING) - textLeft;

            // Header: which of the player's characters was whispered. The one amber on the surface —
            // it is what says "somebody is talking to you" before a word of the card is read.
            canvas.setColor(Theme.WHISPER);
            canvas.setFont(scale.tracked(scale.font(Fonts.CONDENSED, Metrics.HEADING),
                    Metrics.BADGE_TRACKING));
            var y = bounds.y + px(PADDING) + canvas.getFontMetrics().getAscent();
            canvas.drawString(Draw.elide(canvas.getFontMetrics(),
                            words.get(HEADER, card.receiver()).toUpperCase(Locale.ROOT),
                            textRight - textLeft),
                    textLeft, y);

            // Sender: who it is from.
            canvas.setColor(Theme.TEXT);
            canvas.setFont(scale.font(Fonts.SEMIBOLD, Metrics.BODY));
            y += px(Metrics.GAP) + canvas.getFontMetrics().getAscent();
            canvas.drawString(Draw.elide(canvas.getFontMetrics(), card.sender(), bodyWidth), textLeft, y);

            // Message: the line they sent, cut to one with an ellipsis if it will not fit.
            canvas.setColor(Theme.MUTED);
            canvas.setFont(scale.font(Fonts.REGULAR, Metrics.BODY));
            y += px(4) + canvas.getFontMetrics().getAscent();
            canvas.drawString(Draw.elide(canvas.getFontMetrics(), "“" + card.message() + "”", bodyWidth),
                    textLeft, y);

            paintClose(canvas, close, card.id().equals(hoveredClose));
        }

        private void paintClose(Graphics2D canvas, Rectangle close, boolean hovered) {
            Draw.cross(canvas, close.x, close.y, close.width,
                    hovered ? Theme.TEXT : Theme.GHOST, Math.max(1, px(2)));
        }

        /** Which card a point fell in, or {@code null} — read by every mouse event. */
        private Placed at(Point point) {
            return placed.stream().filter(card -> card.bounds().contains(point)).findFirst().orElse(null);
        }

        private final class Clicks extends MouseAdapter {

            @Override
            public void mouseReleased(MouseEvent event) {
                final var card = at(event.getPoint());
                if (card == null) {
                    return;
                }
                if (card.close().contains(event.getPoint())) {
                    actions.dismiss(card.id());
                } else {
                    actions.open(card.id());
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                final var card = at(event.getPoint());
                final var over = card != null && card.close().contains(event.getPoint()) ? card.id() : null;
                if (!Objects.equals(over, hoveredClose)) {
                    hoveredClose = over;
                    repaint();
                }
            }
        }
    }

    /** Where a card and its close cross were painted, so a click can be matched back to a card id. */
    private record Placed(String id, Rectangle bounds, Rectangle close) {
    }
}
