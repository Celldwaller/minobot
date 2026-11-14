package fr.minobot.ui.overlay.characters.clazz;

import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.Sex;
import fr.minobot.i18n.Words;
import fr.minobot.ui.CharacterEntry;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.Segmented;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.SectionHeader;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The class picker: a modal over the whole panel, the twelve classes six to a row over a scrim that dims
 * everything and catches every click — so a mis-click closes the picker and touches nothing else.
 *
 * <p>Six to a row and not four, so the twelve make <strong>two rows</strong>: a player who knows which
 * class they want finds it in one glance down two lines rather than three, and the picker stays wider
 * than it is tall, which is the shape of the panel it opens over.
 *
 * <p>Nothing here is typed: a class is picked with the mouse, on a window that cannot take the focus,
 * which is why it is an in-overlay grid and not a {@code JPopupMenu} (a popup expects the focus too). The
 * orchestrator owns whether the picker is open; this only draws it and reports the choice.
 */
public final class ClassPicker {

    /** The twelve, six to a row. */
    private static final int COLUMNS = 6;
    private static final int TILE = 58;
    private static final int TILE_ICON = 26;

    private final OverlayActions actions;
    private final ClassIcons classIcons;

    /** The scrim was clicked: dismiss the picker, changing nothing on the character. */
    private final Runnable dismiss;

    /** A class was chosen: pin it and close — the orchestrator clears the picker, the action persists it. */
    private final BiConsumer<String, DofusClass> chooseClass;

    private Scale scale;

    public ClassPicker(OverlayActions actions, ClassIcons classIcons,
                       Runnable dismiss, BiConsumer<String, DofusClass> chooseClass) {
        this.actions = actions;
        this.classIcons = classIcons;
        this.dismiss = dismiss;
        this.chooseClass = chooseClass;
    }

    /**
     * The picker over the whole panel: a darkened backdrop with the grid of classes centred on it. A
     * click on the backdrop — anywhere but the grid — closes it; the game is not touched, so a mis-click
     * costs nothing but the picker.
     */
    public JComponent build(Scale scale, String character, OverlayContent content) {
        this.scale = scale;

        final var scrim = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                final var canvas = (Graphics2D) graphics.create();
                canvas.setColor(Theme.BACKDROP);
                canvas.fillRect(0, 0, getWidth(), getHeight());
                canvas.dispose();
                super.paintComponent(graphics);
            }
        };
        scrim.setOpaque(false);
        scrim.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dismiss.run();
            }
        });

        scrim.add(grid(character, content));
        return scrim;
    }

    /**
     * The card the picker rests on the scrim: a heading naming the character, the sex toggle, and the
     * twelve classes drawn in the chosen sex.
     */
    private JComponent grid(String character, OverlayContent content) {
        final var sex = resolve(character, content).sexOrDefault();
        final var padding = scale.px(Metrics.PADDING);

        // Its own strong edge: it stands on top of the panel, not on it, and must read as lifted off.
        final var card = Card.column(scale, Theme.SURFACE, Theme.EDGE_STRONG);
        card.setBorder(new EmptyBorder(padding, padding, padding, padding));
        card.add(new SectionHeader(scale, content.words().get("picker.title"), character,
                sexToggle(content.words(), character, sex)));

        final var grid = new JPanel(new GridLayout(0, COLUMNS, scale.px(Metrics.GAP - 2),
                scale.px(Metrics.GAP - 2)));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final var clazz : DofusClass.values()) {
            grid.add(classTile(character, clazz, sex));
        }
        card.add(grid);
        return card;
    }

    /**
     * The male/female toggle, next to the picker's heading. Picking a sex records it at once and leaves
     * the picker open — so the class tiles below redraw in that sex, and the class the player then chooses
     * is theirs in it. The sex already set is the lit one.
     */
    private JComponent sexToggle(Words words, String character, Sex current) {
        final var sexes = Sex.values();
        return Segmented.of(scale,
                List.of(words.get(sexes[0].key()), words.get(sexes[1].key())),
                current.ordinal(),
                index -> actions.assignSex(character, sexes[index]));
    }

    /**
     * One class on the picker: its icon over its name, and a click that pins it to the character and
     * closes the picker. The icon is drawn in the sex the toggle currently shows, which is also the sex
     * the class is pinned in.
     */
    private JButton classTile(String character, DofusClass clazz, Sex sex) {
        final var tile = new JButton() {
            @Override
            protected void paintComponent(Graphics graphics) {
                final var canvas = Draw.smooth(graphics);
                final var radius = scale.px(Metrics.RADIUS_TILE);
                final var hovered = getModel().isRollover();

                canvas.setColor(hovered ? Theme.HOVER : Theme.RAISED);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                canvas.setColor(hovered ? Theme.EDGE_STRONG : Theme.EDGE);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);

                final var size = scale.px(TILE_ICON);
                classIcons.paint(scale, canvas, clazz, sex, (getWidth() - size) / 2,
                        scale.px(Metrics.GAP - 1), size);

                canvas.setColor(hovered ? Theme.TEXT : Theme.MUTED);
                canvas.setFont(scale.font(Fonts.MEDIUM, Metrics.TINY));
                final var fm = canvas.getFontMetrics();
                canvas.drawString(clazz.label(), (getWidth() - fm.stringWidth(clazz.label())) / 2,
                        getHeight() - scale.px(Metrics.GAP - 1));
                canvas.dispose();
            }
        };

        tile.setPreferredSize(new Dimension(scale.px(TILE), scale.px(TILE)));
        tile.setFocusable(false); // it could not take the focus anyway; do not draw as if it could
        tile.setContentAreaFilled(false);
        tile.setBorderPainted(false);
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tile.addActionListener(_ -> chooseClass.accept(character, clazz));
        return tile;
    }

    /**
     * The character the panel currently holds under a name — the one the picker is open for, looked up so
     * the toggle and the tiles can read its sex. A name off the panel (a stale click racing a disconnect)
     * resolves to a fresh, unpinned character, drawn as male, which the controller then refuses to persist.
     */
    private static Character resolve(String name, OverlayContent content) {
        return content.characters().stream()
                .map(CharacterEntry::character)
                .filter(character -> character.name().equals(name))
                .findFirst()
                .orElse(new Character(name));
    }
}
