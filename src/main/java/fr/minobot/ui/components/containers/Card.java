package fr.minobot.ui.components.containers;

import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.BoxLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * A card: a rounded sheet, drawn rather than bordered, so its corners are not square. The base surface
 * everything on a themed panel rests on, and the column its contents stack in.
 *
 * <p>Three factories cover the shapes the panel needs: {@link #sheet} for the panel's own outermost
 * surface, drawn at the widest radius of the nesting order; {@link #column} for a card resting on it; and
 * {@link #plainColumn} for a stack that carries no surface of its own — a group of rows that sits
 * <em>on</em> a card rather than being one.
 *
 * <p>A column can be <strong>{@link #pinnedTo pinned to a width}</strong>, which is how the panel's
 * halves keep their places: the team is as wide as a name and a class need and no wider, so a long
 * character name never moves the console beside it. The pin holds the <em>width</em> only, and reads the
 * height from the contents at every layout — the character list is resized under its card as the game
 * leaves it less room, and a card that had been pinned to a height would ignore that.
 */
public final class Card extends javax.swing.JPanel {

    private final Scale scale;
    private final Color fill;
    private final Color edge;
    private final int radius;

    /** The width this card is held to, or {@code -1} to take whatever its contents need. */
    private int pinnedWidth = -1;

    /** The same for the height. */
    private int pinnedHeight = -1;

    public Card(Scale scale, Color fill, Color edge, int radius) {
        this.scale = scale;
        this.fill = fill;
        this.edge = edge;
        this.radius = radius;
        setOpaque(false);
    }

    /** The panel's outermost surface: the widest corner, so everything on it reads as resting inside. */
    public static Card sheet(Scale scale, Color fill) {
        return stacking(new Card(scale, fill, Theme.EDGE, Metrics.RADIUS_SHEET));
    }

    /** A rounded card of the given fill, resting on the sheet. */
    public static Card column(Scale scale, Color fill) {
        return stacking(new Card(scale, fill, Theme.EDGE, Metrics.RADIUS));
    }

    /** The same, with an edge of its own — for a card that must be seen apart, like the class picker. */
    public static Card column(Scale scale, Color fill, Color edge) {
        return stacking(new Card(scale, fill, edge, Metrics.RADIUS));
    }

    /** A transparent column — rows grouped together, drawing no surface of their own. */
    public static Card plainColumn() {
        return stacking(new Card(null, null, null, 0));
    }

    /**
     * Holds this card to a width, its height still read from its contents.
     *
     * @param width in pixels — a natural size that has already been through a {@link Scale}
     */
    public Card pinnedTo(int width) {
        this.pinnedWidth = width;
        return this;
    }

    /**
     * Holds this card to a width and a height both — for a box whose contents come and go, and that must
     * not move what is around it when they do.
     *
     * @param width  in pixels, already through a {@link Scale}
     * @param height the same
     */
    public Card pinnedTo(int width, int height) {
        this.pinnedWidth = width;
        this.pinnedHeight = height;
        return this;
    }

    private Dimension pinned(Dimension natural) {
        return new Dimension(pinnedWidth < 0 ? natural.width : pinnedWidth,
                pinnedHeight < 0 ? natural.height : pinnedHeight);
    }

    private static Card stacking(Card card) {
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    @Override
    public Dimension getPreferredSize() {
        return pinned(super.getPreferredSize());
    }

    @Override
    public Dimension getMinimumSize() {
        return pinned(super.getMinimumSize());
    }

    @Override
    public Dimension getMaximumSize() {
        if (pinnedWidth < 0 && pinnedHeight < 0) {
            return super.getMaximumSize();
        }
        return new Dimension(pinnedWidth < 0 ? Integer.MAX_VALUE : pinnedWidth,
                pinnedHeight < 0 ? Integer.MAX_VALUE : pinnedHeight);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (fill != null) {
            final var canvas = Draw.smooth(graphics);
            final var corner = scale.px(radius);

            canvas.setColor(fill);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), corner, corner);
            if (edge != null) {
                canvas.setColor(edge);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, corner, corner);
            }
            canvas.dispose();
        }

        super.paintComponent(graphics);
    }
}
