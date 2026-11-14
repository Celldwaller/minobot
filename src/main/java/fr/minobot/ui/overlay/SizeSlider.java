package fr.minobot.ui.overlay;

import fr.minobot.app.Config;
import fr.minobot.i18n.Words;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.FlatSlider;
import fr.minobot.ui.components.labels.Caption;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;

/**
 * How big the panel is drawn — the one control that changes the panel it lives on.
 *
 * <p>Which is why it lands only when the player <em>lets go</em>: a scale that took effect on every pixel
 * of the drag would rebuild the card, and the slider under the mouse with it, several times a second. The
 * percentage beside it follows the thumb, so the drag is not silent for all that. The thumb reads the
 * scale the panel is <em>drawn</em> at — capped to what the game window holds — not what the player asked
 * for, so it never points off the edge of a control that could bring the panel back.
 *
 * <p>It sits at the foot of the sheet, narrow and quiet. It is set once and then never touched again, so
 * it earns a line rather than a card — but it does earn the line: it is the only way back from a panel
 * drawn too large or too small to work with.
 */
public final class SizeSlider {

    /** The track's own width: enough to aim with, not enough to read as a main control. */
    private static final int TRACK_WIDTH = 180;

    private final OverlayActions actions;

    public SizeSlider(OverlayActions actions) {
        this.actions = actions;
    }

    public JPanel build(Scale scale, Words words) {
        final var percent = new JLabel(percentage(scale.factor()) + "%");
        percent.setForeground(Theme.GHOST);
        percent.setFont(scale.font(Fonts.MONO, Metrics.SMALL));

        final var slider = new JSlider(
                percentage(Config.MIN_OVERLAY_SCALE), percentage(Config.MAX_OVERLAY_SCALE),
                percentage(scale.factor()));
        slider.setUI(new FlatSlider(slider, scale));
        slider.setOpaque(false);
        slider.setFocusable(false); // it could not take the focus anyway; do not draw as if it could
        slider.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        slider.setPreferredSize(new Dimension(scale.px(TRACK_WIDTH), scale.px(Metrics.ROW)));
        slider.addChangeListener(_ -> {
            percent.setText(slider.getValue() + "%");
            if (!slider.getValueIsAdjusting()) {
                actions.rescale(slider.getValue() / 100.0);
            }
        });

        final var row = new JPanel(new BorderLayout(scale.px(Metrics.GAP), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(
                scale.px(TRACK_WIDTH) + scale.px(3 * Metrics.PADDING), scale.px(Metrics.ROW)));
        row.add(new Caption(scale, words.get("size.caption")), BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(percent, BorderLayout.EAST);
        return row;
    }

    /** The scale as the player reads it, and as the slider carries it: 150 rather than 1.5. */
    private static int percentage(double scale) {
        return (int) Math.round(scale * 100);
    }
}
