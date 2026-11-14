package fr.minobot.ui.overlay.console;

import fr.minobot.core.domain.Whisper;
import fr.minobot.i18n.Words;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.Hint;
import fr.minobot.ui.components.labels.SectionHeader;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The private messages the player's characters have received, newest first — the whisper cards after
 * they have faded.
 *
 * <p>Each is a small card of its own rather than a line, because a whisper is two things to read (who,
 * and what they said) where an activity line is one. And each is <strong>clickable</strong>: it names a
 * character, so it can offer the same jump the toast offered before it went — which is the whole reason
 * whispers are kept past their ten seconds at all.
 *
 * <p>{@code Clear} empties the list. There is nothing on disk behind it: these are read and then in the
 * way.
 */
public final class WhisperList {

    /** The card's own width, at scale 1: a name, an arrow, another name, and a line of chat under them. */
    public static final int WIDTH = 236;

    /** How many are shown. Beyond this the older ones are simply out of sight. */
    private static final int VISIBLE = 6;

    /** The room inside one whisper card. */
    private static final int PADDING = 9;

    /** Minutes are enough here: a whisper is answered in the minute it arrived, or it is a conversation. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * What stands between the sender and the character they reached.
     *
     * <p>A chevron and not an arrow: the panel is set in Barlow, whose latin cut carries {@code U+2000}
     * to {@code U+206F} and therefore this, but <strong>not</strong> {@code →} ({@code U+2192}) — which
     * Swing would draw as the missing-glyph box. Every character the panel draws has to be one the
     * shipped typeface actually has.
     */
    private static final String TO = " › ";

    private final OverlayActions actions;

    public WhisperList(OverlayActions actions) {
        this.actions = actions;
    }

    /**
     * @param height the column's whole height, whatever it holds — as many whispers are shown as fit in
     *               it, up to {@link #VISIBLE}
     */
    public JComponent build(Scale scale, Words words, List<Whisper> whispers, int height) {
        final var column = Card.plainColumn().pinnedTo(scale.px(WIDTH), height);
        final var header = new SectionHeader(scale, words.get("whispers.title"), null,
                clearButton(scale, words, whispers.isEmpty()));
        column.add(header);

        if (whispers.isEmpty()) {
            column.add(new Hint(scale, words.get("whispers.empty")));
            return column;
        }

        final var hint = new Hint(scale, words.get("whispers.hint"));
        final var step = scale.px(WhisperCard.HEIGHT) + scale.px(4);
        final var room = height - header.getPreferredSize().height - scale.px(4) - hint.getPreferredSize().height;
        final var fits = Math.max(1, Math.min(VISIBLE, room / step));
        for (final var whisper : whispers.stream().limit(fits).toList()) {
            column.add(new WhisperCard(scale, whisper, () -> actions.openWhisper(whisper.id())));
            column.add(Box.createVerticalStrut(scale.px(4)));
        }
        column.add(Box.createVerticalStrut(scale.px(4)));
        column.add(hint);
        return column;
    }

    /** The way out of a full list. Offered only when there is something to clear. */
    private JComponent clearButton(Scale scale, Words words, boolean empty) {
        final var clear = new JLabel(empty ? "" : words.get("whispers.clear"));
        clear.setForeground(Theme.GHOST);
        clear.setFont(scale.font(Fonts.MEDIUM, Metrics.SMALL));
        if (empty) {
            return clear;
        }

        clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clear.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                actions.clearWhispers();
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                clear.setForeground(Theme.TEXT_SOFT);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                clear.setForeground(Theme.GHOST);
            }
        });
        return clear;
    }

    /** One whisper: who wrote to whom and when, and the line itself under them. */
    private static final class WhisperCard extends JPanel {

        /** Two lines of text plus the padding above, between and below them. */
        private static final int HEIGHT = 44;

        private final Scale scale;
        private final Whisper whisper;

        private boolean hovered;

        private WhisperCard(Scale scale, Whisper whisper, Runnable onOpen) {
            this.scale = scale;
            this.whisper = whisper;

            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(0, scale.px(HEIGHT)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.px(HEIGHT)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    onOpen.run();
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
            final var padding = scale.px(PADDING);

            canvas.setColor(hovered ? Theme.HOVER : Theme.RAISED);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            canvas.setColor(hovered ? Theme.EDGE_STRONG : Theme.EDGE);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);

            canvas.setFont(scale.font(Fonts.MONO, Metrics.HEADING));
            final var clock = canvas.getFontMetrics();
            final var time = CLOCK.format(whisper.at().atZone(ZoneId.systemDefault()));
            final var timeWidth = clock.stringWidth(time);
            canvas.setColor(Theme.GHOST);
            canvas.drawString(time, getWidth() - padding - timeWidth, padding + clock.getAscent());

            // Who wrote to whom, in the order it happened: the sender first, the character reached second.
            canvas.setFont(scale.font(Fonts.SEMIBOLD, Metrics.LABEL));
            final var heading = canvas.getFontMetrics();
            canvas.setColor(Theme.TEXT);
            final var who = whisper.sender() + TO + whisper.receiver();
            final var whoRoom = getWidth() - 2 * padding - timeWidth - scale.px(Metrics.GAP);
            canvas.drawString(Draw.elide(heading, who, whoRoom), padding, padding + heading.getAscent());

            canvas.setFont(scale.font(Fonts.REGULAR, Metrics.LABEL));
            final var line = canvas.getFontMetrics();
            canvas.setColor(Theme.MUTED);
            canvas.drawString(Draw.elide(line, "“" + whisper.message() + "”", getWidth() - 2 * padding),
                    padding, getHeight() - padding - line.getDescent());

            canvas.dispose();

            super.paintComponent(graphics);
        }
    }
}
