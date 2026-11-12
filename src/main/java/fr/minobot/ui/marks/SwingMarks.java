package fr.minobot.ui.marks;

import fr.minobot.ui.MarksContent;
import fr.minobot.ui.MarksContent.Kind;
import fr.minobot.ui.MarksContent.Mark;
import fr.minobot.ui.MarksView;
import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;
import fr.minobot.win32.Rect;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.util.List;

/**
 * The spell marks, in Swing: one window over the whole of the game, see-through but for the marks.
 *
 * <p><strong>The game must stay playable under it.</strong> A window drawn with per-pixel alpha lets the
 * mouse through wherever a pixel is fully transparent, so it is only the marks' own strokes the game does
 * not get a click on. Where the desktop cannot draw a see-through window — a remote session, no
 * compositor — the window would be an opaque sheet over the game, and the marks are not drawn at all.
 *
 * <p>Like the banner it never takes the foreground ({@code setFocusableWindowState(false)}, and no more),
 * and it hands every call to the event dispatch thread.
 */
public final class SwingMarks implements MarksView {

    /** How far below the game's top edge the hint hangs: under the auto-pass banner, which hangs above. */
    private static final int HINT_TOP = 64;
    private static final int HINT_PAD_X = 14;
    private static final int HINT_PAD_Y = 7;

    private static final int LABEL_PAD_X = 7;
    private static final int LABEL_PAD_Y = 3;

    /** A slot crossed out is dimmed under its cross, so it reads as spent at a glance. */
    private static final Color SPENT = new Color(6, 5, 5, 120);

    /** Touched on the event dispatch thread only. Built on first show: there may never be one. */
    private JWindow window;
    private Marks marks;

    /** Read from the controller's thread, so it cannot live inside Swing. */
    private volatile boolean visible;

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

        private MarksContent content = new MarksContent(1, List.of(), "");

        private Marks() {
            setOpaque(false);
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
            if (!content.hint().isBlank()) {
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

        private void paintHint(Graphics2D canvas, Scale scale, String hint) {
            canvas.setFont(scale.font(Fonts.MEDIUM, Metrics.BODY));
            final var metrics = canvas.getFontMetrics();
            final var width = metrics.stringWidth(hint) + 2 * scale.px(HINT_PAD_X);
            final var height = metrics.getAscent() + metrics.getDescent() + 2 * scale.px(HINT_PAD_Y);
            final var x = (getWidth() - width) / 2;
            final var y = scale.px(HINT_TOP);
            canvas.setColor(Theme.BACKGROUND);
            canvas.fillRoundRect(x, y, width, height, height, height);
            canvas.setColor(Theme.ACCENT_EDGE);
            canvas.setStroke(new BasicStroke(1));
            canvas.drawRoundRect(x, y, width - 1, height - 1, height, height);
            canvas.setColor(Theme.TEXT_SOFT);
            canvas.drawString(hint, x + scale.px(HINT_PAD_X), y + scale.px(HINT_PAD_Y) + metrics.getAscent());
        }
    }
}
