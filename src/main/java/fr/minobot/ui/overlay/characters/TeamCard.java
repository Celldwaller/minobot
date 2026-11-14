package fr.minobot.ui.overlay.characters;

import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.components.labels.Hint;
import fr.minobot.ui.components.labels.SectionHeader;
import fr.minobot.ui.overlay.characters.clazz.ClassIcons;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.util.function.Consumer;

/**
 * The team: who the player runs, in the order the cycler walks them, under a heading that counts how many
 * are actually on screen.
 *
 * <p><strong>Nothing here asks to be refreshed.</strong> The card once carried a {@code Reload} button;
 * the panel now re-reads the desktop when it opens and while it is up (see {@code OverlayController}), so
 * the button could only ever be pressed to be told what the card already said.
 *
 * <p>It is the <strong>left half of the panel</strong> and the one part of it the player edits — so it is
 * given a fixed width and the console beside it takes the rest. A team card that grew with the longest
 * character name would move the console every time somebody logged in.
 *
 * <p>The card owns the heading and the footer; the rows and what a click on one means are
 * {@link CharacterList}'s. The orchestrator shrinks the list through this card, because the list is the
 * one part of the whole panel that can be short and still be usable.
 */
public final class TeamCard {

    /** The card's own width, at scale 1: as wide as a name, a class and a status need, and no wider. */
    public static final int WIDTH = 340;

    private final CharacterList characters;

    public TeamCard(OverlayActions actions, ClassIcons classIcons, Consumer<String> openPicker) {
        this.characters = new CharacterList(actions, classIcons, openPicker);
    }

    /** The card, at the scale of the moment. Fill it with {@link #fill} once the panel is assembled. */
    public JPanel build(Scale scale, OverlayContent content) {
        final var padding = scale.px(Metrics.PADDING);
        final var innerWidth = scale.px(WIDTH) - 2 * padding;

        final var card = Card.column(scale, Theme.SURFACE).pinnedTo(scale.px(WIDTH));
        card.setBorder(new EmptyBorder(padding, padding, padding, padding));

        final var words = content.words();
        card.add(new SectionHeader(scale, words.get("team.title"), online(content), null));
        card.add(characters.build(scale, words, innerWidth));
        card.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 2)));
        card.add(new Hint(scale, words.get("team.hint")));
        return card;
    }

    /** Fills the rows. Called after the card is assembled, as the panel does. */
    public void fill(OverlayContent content) {
        characters.fill(content);
    }

    public int rowHeight() {
        return characters.rowHeight();
    }

    /** How far down the list is scrolled, read before a rebuild throws the new table's bar back to zero. */
    public int scrollOffset() {
        return characters.scrollOffset();
    }

    /** Puts the list back where it was being read, once the rebuilt table has been laid out. */
    public void scrollTo(int offset) {
        characters.scrollTo(offset);
    }

    public void resizeTo(int height) {
        characters.resizeTo(height);
    }

    /** {@code 4 online of 8} — the one number the player checks before pressing anything. */
    private static String online(OverlayContent content) {
        return content.words().get("team.online", content.online(), content.characters().size());
    }
}
