package fr.minobot.ui.components.labels;

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
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/**
 * A key combination, drawn the way a key looks: one small monospaced tile per key, with a faint
 * {@code +} between them — {@code shift} {@code +} {@code X1}.
 *
 * <p>It is <strong>one tile per key and not one per combination</strong> because that is what the player
 * has to do: {@code shift+X1} is two presses, and a single chip reading "shift+X1" is a sentence about
 * two presses rather than a picture of them. The last tile is the key itself and is drawn brightest; the
 * modifiers before it are held down, not pressed, and are drawn as the quieter thing they are.
 *
 * <p>The combination is split on {@code +}, which is exactly how the configuration writes one — so this
 * knows nothing of features or hotkeys, only of a string that names keys.
 */
public final class KeyChip {

    /** How a combination separates its keys, in the configuration and therefore here. */
    private static final String SEPARATOR = "+";

    /** What a feature nobody bound offers instead of a chip, by key: there is no key to picture yet. */
    private static final String UNBOUND = "keybinds.unbound";

    /** The room a tile keeps left and right of its key. */
    private static final int PAD_X = 7;
    private static final int PAD_Y = 3;

    /** The chips as a row, drawn and no more — a badge in a heading, a key nobody may click. */
    public static JComponent of(Scale scale, Words words, String combination) {
        return row(scale, words, combination);
    }

    /**
     * The same row, but the player may click it — how a key is rebound.
     *
     * <p>The listener is put on the row rather than on each tile: a combination is rebound as a whole,
     * and a player who aimed at {@code shift} rather than at {@code X1} meant the same thing.
     */
    public static JComponent clickable(Scale scale, Words words, String combination, Runnable onClick) {
        final var row = row(scale, words, combination);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                onClick.run();
            }
        });
        return row;
    }

    private static JPanel row(Scale scale, Words words, String combination) {
        final var row = new JPanel(new FlowLayout(FlowLayout.RIGHT, scale.px(3), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.RIGHT_ALIGNMENT);

        if (combination.isBlank()) {
            row.add(unbound(scale, words.get(UNBOUND)));
            return row;
        }

        final var keys = combination.split("\\" + SEPARATOR);
        for (var index = 0; index < keys.length; index++) {
            if (index > 0) {
                row.add(plus(scale));
            }
            // The last key is the one pressed; whatever comes before it is held down.
            row.add(new Tile(scale, said(words, keys[index]), index == keys.length - 1));
        }
        return row;
    }

    /**
     * A key as the player knows it. The configuration's names are Windows' — {@code x1} is what Windows
     * calls the first side button of a mouse, and nobody else does — so the mouse buttons are phrased in
     * the player's language; a key that is its own word, {@code F8} or {@code shift}, is drawn as written.
     */
    private static String said(Words words, String key) {
        return words.find("key." + key.toLowerCase(Locale.ROOT)).orElse(key);
    }

    /** The invitation to bind a key: a dashed outline, the shape of a chip that is not there yet. */
    private static JLabel unbound(Scale scale, String invitation) {
        final var label = new JLabel(invitation) {
            @Override
            protected void paintComponent(Graphics graphics) {
                final var canvas = Draw.smooth(graphics);
                final var dash = scale.px(3);
                canvas.setColor(Theme.EDGE_STRONG);
                canvas.setStroke(new BasicStroke(Math.max(1, scale.px(1)), BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 1f, new float[]{dash, dash}, 0f));
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                        scale.px(Metrics.RADIUS_CHIP), scale.px(Metrics.RADIUS_CHIP));
                canvas.dispose();

                super.paintComponent(graphics);
            }
        };
        label.setForeground(Theme.DIM);
        label.setFont(scale.font(Fonts.MEDIUM, Metrics.SMALL));
        label.setBorder(new EmptyBorder(scale.px(PAD_Y), scale.px(PAD_X), scale.px(PAD_Y), scale.px(PAD_X)));
        return label;
    }

    private static JLabel plus(Scale scale) {
        final var label = new JLabel(SEPARATOR);
        label.setForeground(Theme.GHOST);
        label.setFont(scale.font(Fonts.REGULAR, Metrics.HEADING));
        return label;
    }

    /** One key: a tight monospaced tile, bright when it is the key pressed, quiet when it is held. */
    private static final class Tile extends JLabel {

        private final Scale scale;

        private Tile(Scale scale, String key, boolean pressed) {
            super(key);
            this.scale = scale;
            setForeground(pressed ? Theme.TEXT : Theme.MUTED);
            setFont(scale.font(Fonts.MONO, Metrics.SMALL));
            setBorder(new EmptyBorder(scale.px(PAD_Y), scale.px(PAD_X), scale.px(PAD_Y), scale.px(PAD_X)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var radius = scale.px(Metrics.RADIUS_CHIP);
            canvas.setColor(Theme.RAISED);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            canvas.setColor(Theme.EDGE);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            canvas.dispose();

            super.paintComponent(graphics);
        }
    }

    private KeyChip() {
    }
}
