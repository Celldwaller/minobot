package fr.minobot.ui.overlay.characters;

import fr.minobot.core.domain.Character;
import fr.minobot.i18n.Words;
import fr.minobot.ui.CharacterEntry;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.overlay.characters.clazz.ClassIcons;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A character on a tile of their own, so a row can be picked up by eye before it is by hand: the grip it
 * is dragged by, its rank in the cycle, its class icon, its name, the class it is pinned to, and whether
 * its window is open right now.
 *
 * <p><strong>A character on screen wears the ember down its left edge; one who is not, does not.</strong>
 * That stripe is the row's whole state, said before anything is read — the fill, the name, the icon and
 * the index all step back a shade with it, so eight rows sort themselves into "playing" and "not" at a
 * glance and the green dot only confirms it.
 *
 * <p>The row is divided into six columns by {@link RowColumns}, the one layout {@link CharacterList} also
 * reads so its click routing lands on the very cells drawn here. This class draws a cell per column; how
 * wide each column is lives in {@code RowColumns}, so the two never disagree on where a cell begins.
 */
public final class CharacterRow extends DefaultListCellRenderer {

    /** The ember down the left edge of a character who is on screen. */
    private static final int STRIPE = 3;

    /** The status dot at the tail of a connected row. */
    private static final int DOT = 6;

    /** The forget cross at the tail of a disconnected one. */
    private static final int FORGET_SIZE = 12;

    /** The vertical breathing room between one row's tile and the next. */
    private static final int INSET = 2;

    /** How much of a disconnected character's portrait is left — enough to recognise, not to read first. */
    private static final float DIMMED = 0.45f;

    /** Captured at build, and never changing under a row already on screen — the surface rebuilds instead. */
    private final Scale scale;
    private final Words words;
    private final ClassIcons classIcons;

    /** Never {@code getBackground()}: with none of its own, a component answers with its parent's. */
    private boolean highlighted;

    /** The row's character, to draw their name, their rank, their class and their sex from. */
    private Character character = new Character("");

    /** The row's rank in the cycle, drawn as {@code 01} — a mirror of the order, rebuilt at each row. */
    private String index = "";

    /** Whether their game window is open: the stripe, the shade of everything, and the dot all read it. */
    private boolean connected = true;

    public CharacterRow(Scale scale, Words words, ClassIcons classIcons) {
        this.scale = scale;
        this.words = words;
        this.classIcons = classIcons;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int position,
                                                  boolean selected, boolean focused) {
        super.getListCellRendererComponent(list, value, position, selected, focused);

        final var entry = (CharacterEntry) value;
        character = entry.character();
        connected = entry.connected();
        // The number is the row's rank read off the panel — 01 at the top — and no more: it is a
        // mirror of the order, not a name, so a drag that reorders the list renumbers it for free. Two
        // digits so eight rows make one column rather than a ragged edge.
        index = String.format("%02d", position + 1);
        // Every cell is drawn by hand below, so the label paints no text of its own; only the selection
        // state it carries is kept.
        setText("");

        highlighted = selected;
        setOpaque(false);
        return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        final var canvas = Draw.smooth(graphics);
        final var columns = new RowColumns(scale, getWidth());
        final var inset = scale.px(INSET);
        final var height = getHeight() - 2 * inset;
        final var radius = scale.px(Metrics.RADIUS_TILE);

        canvas.setColor(highlighted ? Theme.HOVER : connected ? Theme.ROW : Theme.ROW_QUIET);
        canvas.fillRoundRect(0, inset, getWidth(), height, radius, radius);
        canvas.setColor(connected ? Theme.EDGE_STRONG : Theme.RULE);
        canvas.drawRoundRect(0, inset, getWidth() - 1, height - 1, radius, radius);

        // The stripe, clipped to the tile's rounded left edge so it does not square the corner off.
        //
        // `clip` and not `setClip`: a row is a rubber stamp, painted with the scroll pane's own clip
        // already in force, and `setClip` would *replace* that one instead of narrowing it. The half-row
        // at the foot of a scrolled list then painted its stripe past the bottom of the viewport, over
        // the hint below the list — the one part of the row that escaped, because it is the only one
        // drawn under a clip of its own.
        final var clip = canvas.getClip();
        canvas.clip(new RoundRectangle2D.Float(0, inset, getWidth(), height, radius, radius));
        canvas.setColor(connected ? Theme.ACCENT : Theme.EDGE);
        canvas.fillRect(0, inset, scale.px(STRIPE), height);
        canvas.setClip(clip);

        Draw.grip(canvas, columns.gripX, inset, columns.gripWidth, height,
                connected ? Theme.GHOST : Theme.EDGE_STRONG);
        paintIndex(canvas, columns);

        // The login placeholder is a window with no character yet: it has no name to give a class to, no
        // status of its own and nothing to forget, so the columns to the right of the index stay empty.
        if (character.name().equals(OverlayContent.LOGGING_IN)) {
            paintName(canvas, columns, words.get("character.logging_in"), columns.iconX,
                    columns.classX - columns.iconX);
            canvas.dispose();
            return;
        }

        paintIcon(canvas, columns);
        paintName(canvas, columns, character.name(), columns.nameX, columns.nameWidth);
        paintClass(canvas, columns);
        paintStatus(canvas, columns);

        canvas.dispose();
    }

    /**
     * The class icon — or, until a class is pinned, the empty frame where one will go.
     *
     * <p>The frame is dashed and holds a {@code +}: the row keeps its shape either way, so a list of
     * eight does not gain a ragged column the day one character is configured and another is not.
     */
    private void paintIcon(Graphics2D canvas, RowColumns columns) {
        final var size = columns.iconWidth;
        final var top = (getHeight() - size) / 2;

        final var clazz = character.clazz();
        if (clazz != null) {
            // A character who is not on screen has their portrait faded with everything else on the row:
            // the icon is the loudest thing on it, and left at full strength it would be the one part of
            // a sleeping row still shouting.
            final var faded = (Graphics2D) canvas.create();
            if (!connected) {
                faded.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DIMMED));
            }
            classIcons.paint(scale, faded, clazz, character.sexOrDefault(), columns.iconX, top, size);
            faded.dispose();
            return;
        }

        final var dash = scale.px(3);
        final var radius = scale.px(Metrics.RADIUS_CHIP);
        canvas.setColor(Theme.EDGE_STRONG);
        canvas.setStroke(new BasicStroke(Math.max(1, scale.px(1)), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1f, new float[]{dash, dash}, 0f));
        canvas.drawRoundRect(columns.iconX, top, size - 1, size - 1, radius, radius);

        canvas.setFont(scale.font(Fonts.MEDIUM, Metrics.BODY));
        canvas.setColor(Theme.FAINT);
        final var fm = canvas.getFontMetrics();
        canvas.drawString("+", columns.iconX + (size - fm.stringWidth("+")) / 2,
                baseline(fm.getAscent(), fm.getDescent()));
    }

    /** The rank, monospaced so eight of them line up, in the ember while the character is on screen. */
    private void paintIndex(Graphics2D canvas, RowColumns columns) {
        final var font = scale.font(connected ? Fonts.MONO_BOLD : Fonts.MONO, Metrics.SMALL);
        canvas.setFont(font);
        canvas.setColor(connected ? Theme.ACCENT_HOVER : Theme.GHOST);
        final var fm = canvas.getFontMetrics();
        canvas.drawString(index, columns.indexX, baseline(fm.getAscent(), fm.getDescent()));
    }

    /**
     * The name, elided if too long so it stays inside its cell instead of running into the class beside
     * it. A disconnected character is dimmed — still legible, plainly not playing right now.
     */
    private void paintName(Graphics2D canvas, RowColumns columns, String name, int x, int width) {
        final var font = scale.font(connected ? Fonts.SEMIBOLD : Fonts.MEDIUM, Metrics.BODY);
        canvas.setFont(font);
        canvas.setColor(connected ? Theme.TEXT : Theme.FAINT);
        final var fm = canvas.getFontMetrics();
        canvas.drawString(Draw.elide(fm, name, width), x,
                baseline(fm.getAscent(), fm.getDescent()));
    }

    /**
     * The class in its column: its name once one is pinned, and the ember invitation to pick one until
     * then. The invitation is the only ember on an otherwise finished row, which is what makes an
     * unconfigured character findable in a list of eight.
     */
    private void paintClass(Graphics2D canvas, RowColumns columns) {
        final var clazz = character.clazz();
        final var font = scale.font(clazz == null ? Fonts.SEMIBOLD : Fonts.MEDIUM, Metrics.SMALL);
        canvas.setFont(font);
        canvas.setColor(clazz == null ? Theme.ACCENT_HOVER : connected ? Theme.FAINT : Theme.GHOST);

        final var fm = canvas.getFontMetrics();
        final var text = clazz == null ? words.get("character.no_class") : clazz.label();
        canvas.drawString(Draw.elide(fm, text, columns.classWidth), columns.classX,
                baseline(fm.getAscent(), fm.getDescent()));
    }

    /**
     * The tail of the row: a green dot for a character on screen, a forget cross for one who is not.
     *
     * <p>The two never both appear, and that is the point — the cross is offered exactly where it can do
     * no harm. A connected character would come straight back from the list they were dropped from, so
     * there is nothing to forget while the dot is lit.
     */
    private void paintStatus(Graphics2D canvas, RowColumns columns) {
        if (connected) {
            final var size = scale.px(DOT);
            Draw.dot(canvas, columns.statusX + (columns.statusWidth - size) / 2, getHeight(), size,
                    Theme.CONNECTED);
            return;
        }

        final var size = scale.px(FORGET_SIZE);
        Draw.cross(canvas, columns.statusX + (columns.statusWidth - size) / 2,
                (getHeight() - size) / 2, size, Theme.GHOST, Math.max(1, scale.px(2)));
    }

    /** The baseline that centres a line of text in the row, whatever the typeface's own metrics. */
    private int baseline(int ascent, int descent) {
        return (getHeight() + ascent - descent) / 2;
    }
}
