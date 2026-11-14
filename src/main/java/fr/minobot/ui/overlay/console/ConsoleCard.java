package fr.minobot.ui.overlay.console;

import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.containers.Divider;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Component;

/**
 * The right half of the panel: what the player has <em>set</em>, and what those settings have been
 * <em>doing</em>, separated by one rule.
 *
 * <p>Under the rule, a grid: the Easyconome box over the activity on the left, the whispers down the whole
 * right. Every cell has a fixed size — a switch flipped or a line logged never moves another.
 *
 * <p>The two belong on one card and not on two. A switch and the line proving it fired are the same
 * question asked twice — <em>is auto-pass on?</em> and <em>did it just pass a turn?</em> — and a player
 * who flips a switch looks straight down for the answer. Two cards would put a gutter between the
 * question and its answer; the rule separates them without pretending they are unrelated.
 */
public final class ConsoleCard {

    /**
     * The card's own width: its two columns, the rule between them and the padding around them. A number
     * of its own here would be a number to keep in step with four others, and it would lose.
     *
     * <p><strong>Summed after scaling, never before.</strong> {@code px()} rounds, so
     * {@code px(a + b)} is not always {@code px(a) + px(b)} — and a card pinned to the scaled total comes
     * out a pixel short of the parts laid out inside it. A pinned column cannot give that pixel back, so
     * the one at the right edge is pushed past the card and clipped there. It costs whatever sits last
     * on that edge, which here is the whispers' {@code Clear}.
     */
    public static int width(Scale scale) {
        return scale.px(ActivityList.WIDTH) + 2 * scale.px(Metrics.GUTTER) + scale.px(1)
                + scale.px(WhisperList.WIDTH) + 2 * scale.px(Metrics.PADDING);
    }

    private final Switches switches;
    private final EconomeBox econome;
    private final ActivityList activity = new ActivityList();
    private final WhisperList whispers;

    public ConsoleCard(OverlayActions actions) {
        this.switches = new Switches(actions);
        this.econome = new EconomeBox(actions);
        this.whispers = new WhisperList(actions);
    }

    /** @param keybinds the drawer's own switch, which rides at the end of the row of state pills */
    public JPanel build(Scale scale, OverlayContent content, JComponent keybinds) {
        final var padding = scale.px(Metrics.PADDING);

        final var card = Card.column(scale, Theme.SURFACE).pinnedTo(width(scale));
        card.setBorder(new EmptyBorder(padding, padding, padding, padding));

        card.add(switches.build(scale, content, keybinds));
        card.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 2)));
        card.add(Divider.horizontal(scale));
        card.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 4)));
        card.add(record(scale, content));
        return card;
    }

    /**
     * The Easyconome box over what was done, and down the right what was said to the player while it was
     * — as tall as the two on the left.
     */
    private JComponent record(Scale scale, OverlayContent content) {
        final var width = scale.px(ActivityList.WIDTH);
        final var left = Card.plainColumn().pinnedTo(width);
        left.add(econome.build(scale, content, width));
        left.add(Box.createVerticalStrut(scale.px(Metrics.GUTTER)));
        left.add(activity.build(scale, content.words(), content.activity()));

        final var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(left);
        row.add(Box.createHorizontalStrut(scale.px(Metrics.GUTTER)));
        row.add(Divider.vertical(scale));
        row.add(Box.createHorizontalStrut(scale.px(Metrics.GUTTER)));
        row.add(whispers.build(scale, content.words(), content.whispers(), left.getPreferredSize().height));
        return row;
    }
}
