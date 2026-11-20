package fr.minobot.ui.overlay.console;

import fr.minobot.i18n.Words;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.PrimaryButton;
import fr.minobot.ui.components.buttons.SecondaryButton;
import fr.minobot.ui.components.buttons.StatePill;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.Caption;
import fr.minobot.ui.components.labels.Hint;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * The <em>Easyconome</em> help, framed on its own under its name: the challenge, started and stopped by
 * one button, and the setup that teaches the slots, with its reset.
 *
 * <p>Framed because they go together and with nothing else on the card: the switches above act on the
 * game, these only draw over it. The challenge is a button rather than a switch because stopping it is an
 * act — the casts heard so far are forgotten — and the button says so. The setup's reset is drawn only
 * while the setup is on: it is the one action kept on disk, and out of reach so during a fight.
 *
 * <p>Down the right, what to do next — above all that the panel must be closed, since it covers the very
 * bar the setup is taught on and the crosses are drawn over.
 */
public final class EconomeBox {

    private final OverlayActions actions;

    public EconomeBox(OverlayActions actions) {
        this.actions = actions;
    }

    /** @param width the column it heads — the box keeps it whichever way its switches are set */
    public JComponent build(Scale scale, OverlayContent content, int width) {
        final var words = content.words();
        final var gap = scale.px(Metrics.GAP);

        final var title = new Caption(scale, words.get("econome.switch"));
        title.setBorder(new EmptyBorder(0, gap, 0, 0));

        final var challenge = row(scale, challenge(scale, words, content.econome()));
        final var reset = new SecondaryButton(scale, words.get("econome.reset_setup"));
        reset.addActionListener(_ -> actions.resetSpellSetup());
        final var setup = row(scale, StatePill.of(scale, words, words.get("econome.setup"), content.spellSetup(),
                actions::toggleSpellSetup));
        // Its gap rides inside the row, so a hidden reset takes its gap with it.
        final var resetting = row(scale, reset);
        resetting.setBorder(new EmptyBorder(gap, 0, 0, 0));
        resetting.setMaximumSize(new Dimension(Integer.MAX_VALUE, resetting.getPreferredSize().height));

        final var controls = Card.plainColumn();
        controls.add(title);
        controls.add(Box.createVerticalStrut(gap));
        controls.add(challenge);
        controls.add(Box.createVerticalStrut(gap));
        controls.add(setup);
        controls.add(resetting);
        // Up top when the steps beside them run taller: the rows stay together under the title.
        controls.add(Box.createVerticalGlue());
        controls.pinnedTo(controls.getPreferredSize().width);

        // The hint takes whatever room the controls leave, in the player's language and at their scale.
        final var room = width - controls.getPreferredSize().width - 2 * gap;
        final var hints = steps(scale, words, hintFor(content), room);

        // As tall as the tallest it can be — reset shown, longest steps — so flipping a switch never moves
        // the activity under it.
        var tallest = controls.getPreferredSize().height;
        for (final var key : HINTS) {
            tallest = Math.max(tallest, steps(scale, words, key, room).getPreferredSize().height);
        }
        resetting.setVisible(content.spellSetup());

        final var box = new Card(scale, Theme.ROW_QUIET, Theme.EDGE, Metrics.RADIUS);
        box.setLayout(new BoxLayout(box, BoxLayout.X_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        final var padding = scale.px(Metrics.PADDING) - scale.px(Metrics.GAP / 2);
        box.setBorder(new EmptyBorder(padding, 0, padding, gap));
        box.add(controls);
        box.add(Box.createHorizontalStrut(gap));
        box.add(hints);
        box.add(Box.createHorizontalGlue());
        return box.pinnedTo(width, tallest + 2 * padding);
    }

    /**
     * Starts the challenge, or stops it in the ember once it runs — as wide either way, so the flip does
     * not move what is beside it.
     */
    private JComponent challenge(Scale scale, Words words, boolean running) {
        final var start = new SecondaryButton(scale, words.get("econome.start"));
        final var stop = new PrimaryButton(scale, words.get("econome.stop"));
        final JButton shown = running ? stop : start;
        shown.setPreferredSize(new Dimension(
                Math.max(start.getPreferredSize().width, stop.getPreferredSize().width),
                shown.getPreferredSize().height));
        shown.addActionListener(_ -> actions.toggleEconome(!running));
        return shown;
    }

    /**
     * What to do next, for the state the switches are in. The setup's comes first: it is the one that
     * cannot be done with the panel up — the panel covers the very bar the player has to click.
     */
    private static final String[] HINTS = {"econome.hint.off", "econome.hint.setup", "econome.hint.on"};

    private static String hintFor(OverlayContent content) {
        if (content.spellSetup()) {
            return "econome.hint.setup";
        }
        return content.econome() ? "econome.hint.on" : "econome.hint.off";
    }

    /**
     * The hint's steps, numbered, each wrapped under its own text rather than under its number — so the
     * numbers stand in a column of their own and the steps read down it.
     */
    private static JComponent steps(Scale scale, Words words, String key, int width) {
        // A | between two steps; a \n inside one is a line of its own within the step.
        final var steps = words.get(key).split("\\|");
        // Held to their own height and between two glues: the column is as tall as the controls beside it,
        // and the steps sit together in its middle rather than spread down it.
        final var list = Card.plainColumn();
        list.add(Box.createVerticalGlue());
        final var indent = new Hint(scale, steps.length + ".").getPreferredSize().width + scale.px(Metrics.GAP / 2);
        for (var i = 0; i < steps.length; i++) {
            if (i > 0) {
                list.add(Box.createVerticalStrut(scale.px(Metrics.GAP / 2)));
            }
            final var number = new Hint(scale, (i + 1) + ".");
            number.setVerticalAlignment(SwingConstants.TOP);
            number.setPreferredSize(new Dimension(indent, number.getPreferredSize().height));

            final var text = Card.plainColumn();
            Hint.wrapped(scale, steps[i], width - indent).forEach(text::add);

            final var step = new JPanel(new BorderLayout());
            step.setOpaque(false);
            step.setAlignmentX(Component.LEFT_ALIGNMENT);
            step.add(number, BorderLayout.WEST);
            step.add(text, BorderLayout.CENTER);
            step.setMaximumSize(new Dimension(Integer.MAX_VALUE, step.getPreferredSize().height));
            list.add(step);
        }
        list.add(Box.createVerticalGlue());
        return list;
    }

    private static JPanel row(Scale scale, JComponent... controls) {
        final var row = new JPanel(new FlowLayout(FlowLayout.LEFT, scale.px(Metrics.GAP), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final var control : controls) {
            row.add(control);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }
}
