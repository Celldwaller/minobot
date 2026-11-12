package fr.minobot.ui.overlay.console;

import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.SecondaryButton;
import fr.minobot.ui.components.buttons.StatePill;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.FlowLayout;

/**
 * The <em>Easyconome</em> help, framed on its own: on or off, the setup that teaches the slots, and the
 * reset that forgets the fight's casts.
 *
 * <p>Framed because the three go together and with nothing else on the card: the switches above act on
 * the game, these only draw over it. The reset is the one action of the panel that is not a switch — it
 * is there for a fight's end the chat was not read for.
 */
public final class EconomeBox {

    private final OverlayActions actions;

    public EconomeBox(OverlayActions actions) {
        this.actions = actions;
    }

    /** @param width the column it heads — the box keeps it whichever way its switches are set */
    public JComponent build(Scale scale, OverlayContent content, int width) {
        final var reset = new SecondaryButton(scale, "Reset");
        reset.addActionListener(_ -> actions.resetEconome());
        final var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, scale.px(Metrics.GAP), 0));
        controls.setOpaque(false);
        controls.add(StatePill.of(scale, "Easyconome", content.econome(), actions::toggleEconome));
        controls.add(StatePill.of(scale, "Setup", content.spellSetup(), actions::toggleSpellSetup));
        controls.add(reset);

        final var box = Card.column(scale, Theme.ROW_QUIET);
        final var padding = scale.px(Metrics.GAP);
        box.setBorder(new EmptyBorder(padding, 0, padding, 0));
        box.add(controls);
        return box.pinnedTo(width, box.getPreferredSize().height);
    }
}
