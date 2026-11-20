package fr.minobot.ui.marks;

import fr.minobot.ui.MarksActions;
import fr.minobot.ui.MarksContent;
import fr.minobot.ui.MarksContent.Guide;
import fr.minobot.ui.MarksContent.Kind;
import fr.minobot.ui.MarksContent.Mark;
import fr.minobot.ui.MarksView;
import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Emphasis;
import fr.minobot.ui.utils.Emphasis.Word;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;
import fr.minobot.win32.Rect;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;

/**
 * The spell marks, in Swing: one window over the whole of the game, see-through but for the marks.
 *
 * <p><strong>The game must stay playable under it.</strong> A window drawn with per-pixel alpha lets the
 * mouse through wherever a pixel is fully transparent, so it is only the marks' own strokes the game does
 * not get a click on. Where the desktop cannot draw a see-through window — a remote session, no
 * compositor — the window would be an opaque sheet over the game, and the marks are not drawn at all.
 *
 * <p>Like the banner it never takes the foreground ({@code setFocusableWindowState(false)}, and no more),
 * and it hands every call to the event dispatch thread. Its one click — the button in the hint — is routed
 * by hand, as the banner's is.
 */
public final class SwingMarks implements MarksView {

    /** How far below the game's top edge the hint hangs: under the auto-pass banner, which hangs above. */
    private static final int HINT_TOP = 64;
    private static final int HINT_PAD_X = 14;
    private static final int HINT_PAD_Y = 7;

    private static final int BUTTON_PAD_X = 11;
    private static final int BUTTON_PAD_Y = 3;

    private static final int LABEL_PAD_X = 7;
    private static final int LABEL_PAD_Y = 3;

    /** The setup's card: wide enough for a step on one line, at the panel's scale. */
    private static final int GUIDE_WIDTH = 460;
    private static final int GUIDE_PAD = 18;
    private static final int STEP_GAP = 6;

    /** A slot crossed out is dimmed under its cross, so it reads as spent at a glance. */
    private static final Color SPENT = new Color(6, 5, 5, 120);

    private final MarksActions actions;

    /** Touched on the event dispatch thread only. Built on first show: there may never be one. */
    private JWindow window;
    private Marks marks;

    /** Read from the controller's thread, so it cannot live inside Swing. */
    private volatile boolean visible;

    public SwingMarks(MarksActions actions) {
        this.actions = actions;
    }

    @Override
    public void show(MarksContent content, Rect anchor) {
        if (!translucent()) {
            return;
        }
        visible = true;
        SwingUtilities.invokeLater(() -> draw(content, anchor));
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

    private static boolean translucent() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    // ------------------------------------------------------------------ the event dispatch thread

    private void draw(MarksContent content, Rect anchor) {
        if (!visible) {
            return;
        }
        if (window == null) {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setBackground(new Color(0, 0, 0, 0));
            marks = new Marks();
            window.setContentPane(marks);
        }
        marks.content = content;
        window.setBounds(anchor.left(), anchor.top(), anchor.width(), anchor.height());
        window.setVisible(true);
        marks.repaint();
    }

    private final class Marks extends JPanel {

        private MarksContent content = new MarksContent(1, List.of(), "", Optional.empty());

        /** Where the button was last painted, for the mouse to test against; {@code null} while there is none. */
        private Rectangle button;

        private Marks() {
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    if (button != null && button.contains(event.getPoint())) {
                        actions.act();
                    }
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    final var over = button != null && button.contains(event.getPoint());
                    setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            final var canvas = Draw.smooth(graphics);
            final var scale = new Scale(content.scale());
            for (final var mark : content.marks()) {
                paintMark(canvas, mark);
            }
            for (final var mark : content.marks()) {
                if (!mark.label().isBlank()) {
                    paintLabel(canvas, scale, mark);
                }
            }
            button = null;
            if (content.guide().isPresent()) {
                paintGuide(canvas, scale, content.guide().get());
            } else if (!content.hint().isBlank()) {
                paintHint(canvas, scale, content.hint());
            }
            canvas.dispose();
        }

        /** Strokes in proportion to the slot, which is drawn as big as the game is. */
        private void paintMark(Graphics2D canvas, Mark mark) {
            final var slot = mark.slot();
            final var side = Math.min(slot.width(), slot.height());
            final var stroke = Math.max(2, side / 12);
            switch (mark.kind()) {
                case USED -> {
                    canvas.setColor(SPENT);
                    canvas.fillRect(slot.left(), slot.top(), slot.width(), slot.height());
                    final var inset = side / 6;
                    canvas.setColor(Theme.ACCENT);
                    canvas.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    canvas.drawLine(slot.left() + inset, slot.top() + inset, slot.right() - inset, slot.bottom() - inset);
                    canvas.drawLine(slot.left() + inset, slot.bottom() - inset, slot.right() - inset, slot.top() + inset);
                }
                case KNOWN, UNKNOWN -> {
                    final var edge = Math.max(2, side / 20);
                    canvas.setColor(mark.kind() == Kind.KNOWN ? Theme.CONNECTED : Theme.ACCENT);
                    if (mark.kind() == Kind.KNOWN) {
                        canvas.setStroke(new BasicStroke(edge));
                    } else {
                        final float dash = side / 6f;
                        canvas.setStroke(new BasicStroke(edge, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                                new float[]{dash, dash}, 0));
                    }
                    canvas.drawRect(slot.left(), slot.top(), slot.width() - 1, slot.height() - 1);
                }
            }
        }

        /** A small pill above the slot, kept inside the game. */
        private void paintLabel(Graphics2D canvas, Scale scale, Mark mark) {
            canvas.setFont(scale.font(Fonts.SEMIBOLD, Metrics.LABEL));
            final var metrics = canvas.getFontMetrics();
            final var width = metrics.stringWidth(mark.label()) + 2 * scale.px(LABEL_PAD_X);
            final var height = metrics.getAscent() + metrics.getDescent() + 2 * scale.px(LABEL_PAD_Y);
            final var slot = mark.slot();
            final var x = Math.clamp(slot.left() + (slot.width() - width) / 2, 0, Math.max(0, getWidth() - width));
            final var y = Math.max(0, slot.top() - height - scale.px(4));
            canvas.setColor(Theme.BACKGROUND);
            canvas.fillRoundRect(x, y, width, height, height, height);
            canvas.setColor(Theme.TEXT);
            canvas.drawString(mark.label(), x + scale.px(LABEL_PAD_X), y + scale.px(LABEL_PAD_Y) + metrics.getAscent());
        }

        /** A pill under the top of the game. */
        private void paintHint(Graphics2D canvas, Scale scale, String hint) {
            final var font = scale.font(Fonts.MEDIUM, Metrics.BODY);
            final var metrics = canvas.getFontMetrics(font);
            final var width = metrics.stringWidth(hint) + 2 * scale.px(HINT_PAD_X);
            final var height = metrics.getAscent() + metrics.getDescent() + 2 * scale.px(HINT_PAD_Y);
            final var x = (getWidth() - width) / 2;
            final var y = scale.px(HINT_TOP);
            canvas.setColor(Theme.BACKGROUND);
            canvas.fillRoundRect(x, y, width, height, height, height);
            canvas.setColor(Theme.ACCENT_EDGE);
            canvas.setStroke(new BasicStroke(1));
            canvas.drawRoundRect(x, y, width - 1, height - 1, height, height);
            canvas.setFont(font);
            canvas.setColor(Theme.TEXT_SOFT);
            canvas.drawString(hint, x + scale.px(HINT_PAD_X), y + scale.px(HINT_PAD_Y) + metrics.getAscent());
        }

        /**
         * The setup's card under the top of the game: measured once with nothing drawn, so its surface is
         * as tall as what it holds, then drawn over that surface.
         */
        private void paintGuide(Graphics2D canvas, Scale scale, Guide guide) {
            final var width = Math.min(scale.px(GUIDE_WIDTH), getWidth() - 2 * scale.px(Metrics.PADDING));
            final var x = (getWidth() - width) / 2;
            final var y = scale.px(HINT_TOP);
            final var height = layGuide(canvas, scale, guide, x, y, width, false) - y;

            final var corner = scale.px(Metrics.RADIUS);
            canvas.setColor(Theme.BACKGROUND);
            canvas.fillRoundRect(x, y, width, height, corner, corner);
            canvas.setColor(Theme.ACCENT_EDGE);
            canvas.setStroke(new BasicStroke(1));
            canvas.drawRoundRect(x, y, width - 1, height - 1, corner, corner);
            layGuide(canvas, scale, guide, x, y, width, true);
        }

        /** @return the card's bottom edge; nothing is drawn unless {@code draw} */
        private int layGuide(Graphics2D canvas, Scale scale, Guide guide, int x, int top, int width, boolean draw) {
            final var pad = scale.px(GUIDE_PAD);
            final var left = x + pad;
            final var inner = width - 2 * pad;
            var y = top + pad;

            // The title, and how far along the player is across from it.
            final var title = canvas.getFontMetrics(scale.font(Fonts.SEMIBOLD, Metrics.TITLE));
            final var small = canvas.getFontMetrics(scale.font(Fonts.SEMIBOLD, Metrics.SMALL));
            if (draw) {
                canvas.setFont(title.getFont());
                canvas.setColor(Theme.TEXT);
                canvas.drawString(guide.title(), left, y + title.getAscent());
                if (!guide.progress().isBlank()) {
                    canvas.setFont(small.getFont());
                    canvas.setColor(Theme.CONNECTED);
                    canvas.drawString(guide.progress(), left + inner - small.stringWidth(guide.progress()),
                            y + title.getAscent());
                }
            }
            y += title.getHeight();

            final var faint = canvas.getFontMetrics(scale.font(Fonts.REGULAR, Metrics.SMALL));
            if (draw) {
                canvas.setFont(faint.getFont());
                canvas.setColor(Theme.FAINT);
                canvas.drawString(guide.subtitle(), left, y + faint.getAscent());
            }
            y += faint.getHeight() + scale.px(Metrics.GUTTER);

            // The steps, each wrapped under its own text, the numbers in a column of their own.
            final var body = canvas.getFontMetrics(scale.font(Fonts.REGULAR, Metrics.BODY));
            final var strong = canvas.getFontMetrics(scale.font(Fonts.SEMIBOLD, Metrics.BODY));
            final var number = strong.stringWidth(guide.steps().size() + ".") + scale.px(Metrics.GAP);
            for (var i = 0; i < guide.steps().size(); i++) {
                if (i > 0) {
                    y += scale.px(STEP_GAP);
                }
                if (draw) {
                    canvas.setFont(strong.getFont());
                    canvas.setColor(Theme.ACCENT_HOVER);
                    canvas.drawString((i + 1) + ".", left, y + body.getAscent());
                }
                for (final var line : Emphasis.wrap(guide.steps().get(i), body, strong, inner - number)) {
                    if (draw) {
                        words(canvas, line, left + number, y + body.getAscent(), body, strong,
                                Theme.TEXT_SOFT, Theme.TEXT);
                    }
                    y += body.getHeight();
                }
            }

            // What Minobot is waiting for, in the accent: the one thing on the card that changes as they play.
            if (!guide.status().isBlank()) {
                y += scale.px(Metrics.GUTTER);
                final var height = small.getHeight() + 2 * scale.px(BUTTON_PAD_Y);
                if (draw) {
                    canvas.setColor(Theme.ACCENT_WASH);
                    canvas.fillRoundRect(left, y, inner, height, height, height);
                    canvas.setFont(small.getFont());
                    canvas.setColor(Theme.ACCENT_HOVER);
                    canvas.drawString(guide.status(), left + scale.px(BUTTON_PAD_X),
                            y + scale.px(BUTTON_PAD_Y) + small.getAscent());
                }
                y += height;
            }

            // The button, under a rule, with why one would press it.
            if (!guide.action().isBlank()) {
                y += scale.px(Metrics.GUTTER);
                if (draw) {
                    canvas.setColor(Theme.RULE);
                    canvas.fillRect(left, y, inner, Math.max(1, scale.px(1)));
                }
                y += scale.px(Metrics.GUTTER);

                final var buttonWidth = small.stringWidth(guide.action()) + 2 * scale.px(BUTTON_PAD_X);
                final var buttonHeight = small.getHeight() + 2 * scale.px(BUTTON_PAD_Y);
                final var note = Emphasis.wrap(guide.note(), faint, faint,
                        inner - buttonWidth - scale.px(Metrics.GUTTER));
                final var rowHeight = Math.max(buttonHeight, note.size() * faint.getHeight());
                var noteY = y + (rowHeight - note.size() * faint.getHeight()) / 2;
                for (final var line : note) {
                    if (draw) {
                        words(canvas, line, left, noteY + faint.getAscent(), faint, faint, Theme.FAINT, Theme.FAINT);
                    }
                    noteY += faint.getHeight();
                }
                if (draw) {
                    button = new Rectangle(left + inner - buttonWidth, y + (rowHeight - buttonHeight) / 2,
                            buttonWidth, buttonHeight);
                    canvas.setColor(Theme.ACCENT);
                    canvas.fillRoundRect(button.x, button.y, button.width, button.height, buttonHeight, buttonHeight);
                    canvas.setFont(small.getFont());
                    canvas.setColor(Theme.TEXT);
                    canvas.drawString(guide.action(), button.x + scale.px(BUTTON_PAD_X),
                            button.y + scale.px(BUTTON_PAD_Y) + small.getAscent());
                }
                y += rowHeight;
            }
            return y + pad;
        }

        /** A line of words one after the other, those that stand out in their own weight and colour. */
        private static void words(Graphics2D canvas, List<Word> line, int x, int baseline, FontMetrics regular,
                                  FontMetrics strong, Color color, Color strongColor) {
            final var space = regular.stringWidth(" ");
            var at = x;
            for (var i = 0; i < line.size(); i++) {
                final var word = line.get(i);
                at += i > 0 && word.spaced() ? space : 0;
                final var metrics = word.strong() ? strong : regular;
                canvas.setFont(metrics.getFont());
                canvas.setColor(word.strong() ? strongColor : color);
                canvas.drawString(word.text(), at, baseline);
                at += metrics.stringWidth(word.text());
            }
        }
    }
}
