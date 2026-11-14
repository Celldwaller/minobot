package fr.minobot.ui.overlay.keybinds;

import fr.minobot.app.Feature;
import fr.minobot.i18n.Words;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.CloseCross;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.Hint;
import fr.minobot.ui.components.labels.KeyChip;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Every feature's key, in a card of its own beside the panel.
 *
 * <p>They are a list of rows the player edits once and then forgets, and inline they made the panel half
 * as useful again as tall. Folded away, they cost the sheet nothing; unfolded, they cost it no room
 * either — the drawer opens beside it, not inside it. The orchestrator owns whether it is open, and the
 * drawer's own cross folds it back the same way its switch does.
 *
 * <p>A key is <strong>clicked, then pressed</strong>: the row's chips are the target, and the capture
 * that follows waits for a human. Nothing here is typed, because the window this drawer sits on never
 * takes the focus (see {@code ui/CLAUDE.md}).
 */
public final class KeybindsDrawer {

    /**
     * The drawer's own width: enough for the longest feature label and a two-key chip beside it, in either
     * language — a mouse button is named in words, {@code shift} + {@code Bouton latéral 1}, not {@code x1}.
     */
    private static final int WIDTH = 400;

    /** The drawer's close cross — the same square the panel's header wears. */
    private static final int CLOSE_SIZE = 26;

    /** The cross that unbinds — smaller than a close cross: it drops a key, not a surface. */
    private static final int CLEAR_SIZE = 16;

    private final OverlayActions actions;

    /** Redraws the panel after a capture that came to nothing — nothing else would, and the row is
     *  still waiting for a key. */
    private final Runnable redraw;

    /** Folds the drawer away: the cross's job, and the orchestrator's state to change. */
    private final Runnable close;

    private Scale scale;

    /** The player's language, captured at build like the scale: the rows read it as they are made. */
    private Words words;

    public KeybindsDrawer(OverlayActions actions, Runnable redraw, Runnable close) {
        this.actions = actions;
        this.redraw = redraw;
        this.close = close;
    }

    public JPanel build(Scale scale, OverlayContent content) {
        this.scale = scale;
        this.words = content.words();
        final var padding = scale.px(Metrics.PADDING);

        final var drawer = Card.column(scale, Theme.SURFACE).pinnedTo(scale.px(WIDTH));
        drawer.setBorder(new EmptyBorder(padding, padding, padding, padding));

        drawer.add(title());
        drawer.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 4)));

        final var features = Feature.values();
        for (var index = 0; index < features.length; index++) {
            // Every row but the last carries a rule under it: the list is read down, and a rule between
            // two rows is what keeps a long label and a short chip from reading as one line.
            drawer.add(new HotkeyRow(scale, features[index],
                    content.hotkeys().getOrDefault(features[index], ""),
                    index < features.length - 1));
        }

        drawer.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 4)));
        drawer.add(new Hint(scale, words.get("keybinds.hint.click")));
        drawer.add(new Hint(scale, words.get("keybinds.hint.refused")));
        return drawer;
    }

    /** The drawer's own heading — a title and the cross that folds it back. */
    private JComponent title() {
        final var row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.px(Metrics.ROW + Metrics.GAP)));

        final var name = new JLabel(words.get("keybinds.title"));
        name.setForeground(Theme.TEXT);
        name.setFont(scale.font(Fonts.SEMIBOLD, Metrics.TITLE));

        row.add(name, BorderLayout.WEST);
        row.add(CloseCross.button(scale, close, scale.px(CLOSE_SIZE)), BorderLayout.EAST);
        return row;
    }

    /**
     * Waits for the player to press the key they want — <strong>off the event dispatch thread</strong>.
     *
     * <p>The wait is for a human, so it is seconds long. On the EDT it would freeze the very panel the
     * player is pressing keys at, and Swing would repaint nothing until they were done.
     */
    private void capture(Feature feature, HotkeyRow row) {
        row.awaitKey();

        Thread.ofVirtual().name("overlay-capture").start(() -> {
            final var captured = actions.captureHotkey();

            SwingUtilities.invokeLater(() -> captured.ifPresentOrElse(
                    combination -> actions.rebind(feature, combination),
                    // Nothing was pressed. The configuration did not change, so nothing will redraw the
                    // panel for us — and the row is still waiting for a key.
                    redraw));
        });
    }

    /** One feature: what it is called, the key it answers to, and the cross that takes that key away. */
    private final class HotkeyRow extends JPanel {

        /** The room a row keeps above and below its own line. */
        private static final int PADDING = 7;

        private final Scale scale;
        private final boolean ruled;

        private HotkeyRow(Scale scale, Feature feature, String hotkey, boolean ruled) {
            super(new BorderLayout(scale.px(Metrics.GAP), 0));
            this.scale = scale;
            this.ruled = ruled;

            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(new EmptyBorder(scale.px(PADDING), 0, scale.px(PADDING), 0));

            final var name = new JLabel(words.get(feature.key()));
            name.setForeground(hotkey.isBlank() ? Theme.DIM : Theme.TEXT_SOFT);
            name.setFont(scale.font(Fonts.MEDIUM, Metrics.BODY));

            final var keys = new JPanel();
            keys.setLayout(new BoxLayout(keys, BoxLayout.X_AXIS));
            keys.setOpaque(false);
            keys.add(KeyChip.clickable(scale, words, hotkey, () -> capture(feature, this)));
            keys.add(Box.createHorizontalStrut(scale.px(Metrics.GAP)));
            keys.add(new ClearCross(scale, feature, hotkey));

            add(name, BorderLayout.WEST);
            add(keys, BorderLayout.EAST);

            final var height = scale.px(Metrics.ROW + 2 * PADDING);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            setPreferredSize(new Dimension(0, height));
        }

        /** Replaces the row's chips with the invitation to press. Undone by the redraw that follows. */
        private void awaitKey() {
            removeAll();
            final var waiting = new JLabel(words.get("keybinds.capturing"));
            waiting.setForeground(Theme.ACCENT_HOVER);
            waiting.setFont(scale.font(Fonts.SEMIBOLD, Metrics.BODY));
            add(waiting, BorderLayout.WEST);
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!ruled) {
                return;
            }
            final var canvas = graphics.create();
            canvas.setColor(Theme.RULE);
            canvas.fillRect(0, getHeight() - Math.max(1, scale.px(1)), getWidth(),
                    Math.max(1, scale.px(1)));
            canvas.dispose();
        }
    }

    /**
     * The cross that turns a feature off.
     *
     * <p>A blank hotkey <strong>is</strong> the off switch — which is why there is no {@code *_enabled}
     * flag anywhere in the configuration — so this cross is the whole of "stop doing that". It is drawn
     * only where there is a key to take away: a feature already unbound has nothing for it to clear, and
     * an inert cross beside it would be a control that does nothing.
     */
    private final class ClearCross extends JComponent {

        private final Scale scale;
        private final boolean bound;

        private boolean hovered;

        private ClearCross(Scale scale, Feature feature, String hotkey) {
            this.scale = scale;
            this.bound = !hotkey.isBlank();

            final var square = new Dimension(scale.px(CLEAR_SIZE), scale.px(CLEAR_SIZE));
            setPreferredSize(square);
            setMinimumSize(square);
            setMaximumSize(square);

            if (!bound) {
                return;
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    actions.rebind(feature, "");
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
            if (!bound) {
                return;
            }
            final var canvas = Draw.smooth(graphics);
            Draw.cross(canvas, 0, 0, getWidth(), hovered ? Theme.ACCENT : Theme.GHOST,
                    Math.max(1, scale.px(2)));
            canvas.dispose();
        }
    }
}
