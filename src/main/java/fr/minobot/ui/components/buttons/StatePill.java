package fr.minobot.ui.components.buttons;

import fr.minobot.i18n.Words;
import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A switch that carries its own name: <em>Auto-pass turns</em> and, right beside it, {@code ON} or
 * {@code OFF}. One click flips it.
 *
 * <p>The name and the state sit on the <strong>same pill</strong> on purpose. A row of labels down one
 * side and a column of switches down the other makes the player pair them up by eye every time they
 * look; a pill says one thing, and the whole of it is the click target. And the state is said
 * <strong>twice</strong> — in a word and in a colour — because a control that quietly ends every combat
 * turn or accepts a trade has to be unmistakable, and a colour alone is not a word.
 *
 * <p>It is a panel with a mouse listener rather than a {@code JButton}: a button's preferred size comes
 * from its own text through its UI delegate, and this one's comes from the two labels laid out inside it,
 * which a button would ignore and draw too small for.
 */
public final class StatePill {

    /**
     * @param words    the player's language, for the {@code ON}/{@code OFF} the pill says itself
     * @param label    what the switch does, in the player's words
     * @param on       which way it is set right now
     * @param onToggle handed the state the player has just asked for
     */
    public static JComponent of(Scale scale, Words words, String label, boolean on, Consumer<Boolean> onToggle) {
        final var onWord = words.get("switch.on");
        final var offWord = words.get("switch.off");

        final var name = new JLabel(label);
        name.setForeground(on ? Theme.TEXT : Theme.FAINT);
        name.setFont(scale.font(Fonts.SEMIBOLD, Metrics.LABEL));

        final var state = new JLabel(on ? onWord : offWord);
        state.setForeground(on ? Theme.ACCENT_HOVER : Theme.DIM);
        state.setFont(scale.tracked(scale.font(Fonts.CONDENSED, Metrics.HEADING), Metrics.BADGE_TRACKING));
        state.setBorder(new EmptyBorder(0, scale.px(Metrics.GAP - 1), 0, 0));
        holdWidest(state, onWord, offWord);

        final var pill = new Pill(scale, on, onToggle);
        pill.add(name, BorderLayout.WEST);
        pill.add(state, BorderLayout.EAST);
        return pill;
    }

    /**
     * Sizes the state for the wider of its two words, so a flip does not resize the pill and push
     * everything beside it along the row.
     */
    private static void holdWidest(JLabel state, String onWord, String offWord) {
        final var metrics = state.getFontMetrics(state.getFont());
        final var insets = state.getInsets();
        final var natural = state.getPreferredSize();
        state.setPreferredSize(new Dimension(
                Math.max(metrics.stringWidth(onWord), metrics.stringWidth(offWord)) + insets.left + insets.right,
                natural.height));
    }

    /** The surface under the two labels: washed in the ember when on, a quiet raised tile when off. */
    private static final class Pill extends JPanel {

        private final Scale scale;
        private final boolean on;

        /** Tracked by hand, there being no button model here to ask. */
        private boolean hovered;

        private Pill(Scale scale, boolean on, Consumer<Boolean> onToggle) {
            super(new BorderLayout());
            this.scale = scale;
            this.on = on;

            setOpaque(false);
            setBorder(new EmptyBorder(scale.px(6), scale.px(Metrics.GAP + 3),
                    scale.px(6), scale.px(Metrics.GAP + 3)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    onToggle.accept(!on);
                }

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
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var radius = scale.px(Metrics.RADIUS_TILE);

            canvas.setColor(on ? Theme.ACCENT_WASH : hovered ? Theme.HOVER : Theme.RAISED);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            canvas.setColor(on ? Theme.ACCENT_EDGE : Theme.EDGE);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            canvas.dispose();

            super.paintComponent(graphics);
        }
    }

    private StatePill() {
    }
}
