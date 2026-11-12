package fr.minobot.ui.overlay.console;

import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.components.buttons.StatePill;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * The switches that act on the game for the player — <em>Auto-pass turns</em> and
 * <em>Auto-accept trades</em> — and the two that draw over it: <em>Économe</em>, which crosses out the
 * spells each character cast this fight, and <em>Spell setup</em>, which frames the slots to teach them.
 *
 * <p>They are the panel's <em>states</em> rather than keys — a {@link StatePill} each, saying what they
 * do and which way they are set on one control, because a switch that quietly ends every combat turn or
 * accepts a trade has to be unmistakable.
 *
 * <p>They open the console because they are the only things on that card the player <em>sets</em>;
 * everything below the rule is the record of what those settings have been doing.
 */
public final class Switches {

    private final OverlayActions actions;

    public Switches(OverlayActions actions) {
        this.actions = actions;
    }

    /** @param trailing pinned to the right of the row — the keybinds drawer's own switch */
    public JComponent build(Scale scale, OverlayContent content, JComponent trailing) {
        final var row = new JPanel(new FlowLayout(FlowLayout.LEFT, scale.px(Metrics.GAP), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.px(Metrics.ROW + Metrics.GAP)));

        row.add(StatePill.of(scale, "Auto-pass turns", content.autoPassTurn(),
                actions::toggleAutoPassTurn));
        row.add(StatePill.of(scale, "Auto-accept trades", content.autoAcceptTrade(),
                actions::toggleAutoAcceptTrade));
        row.add(StatePill.of(scale, "Économe", content.econome(), actions::toggleEconome));
        row.add(StatePill.of(scale, "Spell setup", content.spellSetup(), actions::toggleSpellSetup));
        row.add(Box.createHorizontalStrut(scale.px(Metrics.GAP)));
        row.add(trailing);
        return row;
    }
}
