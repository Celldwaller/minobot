package fr.minobot.ui.overlay.console;

import fr.minobot.core.domain.Activity;
import fr.minobot.i18n.Words;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.Hint;
import fr.minobot.ui.components.labels.SectionHeader;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What Minobot just did: one line per act, newest at the top.
 *
 * <p>Three columns — <strong>when</strong>, <strong>what</strong>, <strong>about whom</strong> — and the
 * first is monospaced so the times make a column the eye runs down rather than a ragged left edge. The
 * detail is right-aligned against the card, so the acts read as a list on their own and the details are
 * there for the one line that surprises.
 *
 * <p>It is read-only. Everything on it already happened, and there is nothing here to undo — the panel
 * that let a player un-pass a turn would be lying about what it can do.
 */
public final class ActivityList {

    /** The column's own width, at scale 1: a time, a sentence about a character, and a short detail. */
    public static final int WIDTH = 372;

    /** The height of one line: tight, because six of them are read as a block and not as six rows. */
    private static final int LINE_HEIGHT = 22;

    /** The time column's width, sized for {@code 00:00:00} in the monospaced face. */
    private static final int TIME_WIDTH = 58;

    /** How wide the detail may grow before it starts eliding rather than squeezing the act. */
    private static final int DETAIL_WIDTH = 92;

    /** How many lines the card shows. Beyond this the older ones are simply out of sight. */
    private static final int VISIBLE = 8;

    /** Wall-clock, to the second: the acts come seconds apart, so minutes alone would not tell them apart. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** @param words the player's language — an entry is put into words here, not when it was written */
    public JComponent build(Scale scale, Words words, List<Activity> entries) {
        final var header = new SectionHeader(scale, words.get("activity.title"), words.get("activity.note"), null);
        final var column = Card.plainColumn().pinnedTo(scale.px(WIDTH),
                header.getPreferredSize().height + VISIBLE * scale.px(LINE_HEIGHT));
        column.add(header);

        if (entries.isEmpty()) {
            // Never a blank, which reads as broken.
            column.add(new Hint(scale, words.get("activity.empty")));
            return column;
        }

        for (final var entry : entries.stream().limit(VISIBLE).toList()) {
            column.add(new Line(scale, entry.at(), words.get(entry.what()), words.get(entry.detail())));
        }
        return column;
    }

    /** One act: its time, what it was, and the circumstance behind it. */
    private static final class Line extends JPanel {

        private final Scale scale;
        private final Instant at;
        private final String what;
        private final String detail;

        private Line(Scale scale, Instant at, String what, String detail) {
            this.scale = scale;
            this.at = at;
            this.what = what;
            this.detail = detail;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(0, scale.px(LINE_HEIGHT)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.px(LINE_HEIGHT)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var gap = scale.px(Metrics.GAP + 2);
            final var time = scale.px(TIME_WIDTH);
            final var detailRoom = scale.px(DETAIL_WIDTH);

            canvas.setFont(scale.font(Fonts.MONO, Metrics.SMALL));
            canvas.setColor(Theme.GHOST);
            final var mono = canvas.getFontMetrics();
            canvas.drawString(CLOCK.format(at.atZone(ZoneId.systemDefault())), 0,
                    baseline(mono.getAscent(), mono.getDescent()));

            final var detailWidth = detail.isBlank() ? 0 : detailRoom + gap;
            final var whatX = time + gap;
            final var whatRoom = getWidth() - whatX - detailWidth;

            canvas.setFont(scale.font(Fonts.MEDIUM, Metrics.LABEL));
            canvas.setColor(Theme.TEXT_SOFT);
            final var text = canvas.getFontMetrics();
            canvas.drawString(Draw.elide(text, what, whatRoom), whatX,
                    baseline(text.getAscent(), text.getDescent()));

            if (!detail.isBlank()) {
                paintDetail(canvas, detailRoom);
            }
            canvas.dispose();
        }

        /** The circumstance, right-aligned against the card's edge so the details line up too. */
        private void paintDetail(Graphics2D canvas, int room) {
            canvas.setFont(scale.font(Fonts.MEDIUM, Metrics.SMALL));
            canvas.setColor(Theme.DIM);
            final var metrics = canvas.getFontMetrics();
            final var elided = Draw.elide(metrics, detail, room);
            canvas.drawString(elided, getWidth() - metrics.stringWidth(elided),
                    baseline(metrics.getAscent(), metrics.getDescent()));
        }

        private int baseline(int ascent, int descent) {
            return (getHeight() + ascent - descent) / 2;
        }
    }
}
