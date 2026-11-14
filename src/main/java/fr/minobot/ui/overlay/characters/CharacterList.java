package fr.minobot.ui.overlay.characters;

import fr.minobot.i18n.Words;
import fr.minobot.ui.CharacterEntry;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.components.tables.SmartTable;
import fr.minobot.ui.overlay.characters.clazz.ClassIcons;
import fr.minobot.ui.utils.Scale;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import java.awt.Point;
import java.util.List;
import java.util.function.Consumer;

/**
 * The characters, on tiles of their own, dragged into the order the cycler follows. It owns the list
 * model and decides what a click on a row means; the scrollable, reorderable shell it lives in — and
 * the raw mouse handling that shell needs — is a reusable {@link SmartTable}.
 *
 * <p>Every click is routed by hand — a drag reorders, a click on the class cell or a double-click anywhere
 * on the row opens the picker, a click on a disconnected row's forget cross drops it — because the window
 * this list lives on never takes the focus, and Swing's own drag-and-drop and selection machinery expects
 * a window that does. The {@link SmartTable} does the routing; this class supplies the meaning.
 */
public final class CharacterList {

    /** The height of one character's tile — room for a 22-pixel class icon and its breathing space. */
    private static final int ROW_HEIGHT = 32;

    private final OverlayActions actions;
    private final ClassIcons classIcons;

    /** Opens the class picker for a character — the orchestrator owns the picker's open/closed state. */
    private final Consumer<String> openPicker;

    private final DefaultListModel<CharacterEntry> model = new DefaultListModel<>();

    /** Rebuilt each lay; read by the click routing for the cell sizes it measures against. */
    private Scale scale;

    /** The shell the rows live in, kept so the orchestrator can shrink it as the game leaves less room. */
    private SmartTable<CharacterEntry> table;

    public CharacterList(OverlayActions actions, ClassIcons classIcons, Consumer<String> openPicker) {
        this.actions = actions;
        this.classIcons = classIcons;
        this.openPicker = openPicker;
    }

    /**
     * @param words      the player's language, for what a row says besides a name
     * @param innerWidth the card's content width in pixels
     */
    public JComponent build(Scale scale, Words words, int innerWidth) {
        this.scale = scale;
        table = new SmartTable<>(model, scale, innerWidth, ROW_HEIGHT,
                new CharacterRow(scale, words, classIcons), this::reorder, this::onRowClick);
        return table;
    }

    /** Fills the list with the characters to show. Called after the card is assembled, as the panel does. */
    public void fill(OverlayContent content) {
        model.clear();
        content.characters().forEach(model::addElement);
    }

    /** The natural row height, in pixels, so the orchestrator can floor a shrunk list at one row. */
    public int rowHeight() {
        return table.rowHeight();
    }

    /** How far down the list is scrolled — zero before the first build, when there is no table yet. */
    public int scrollOffset() {
        return table == null ? 0 : table.scrollOffset();
    }

    /** Puts the list back where it was being read, after a rebuild has laid the new table out. */
    public void scrollTo(int offset) {
        if (table != null) {
            table.scrollTo(offset);
        }
    }

    /** Sets the list's height as the orchestrator shrinks it to the room the game leaves the cards. */
    public void resizeTo(int height) {
        table.resizeTo(height);
    }

    /** The settled order, reported to the cycler as character names — the table's job was only to move rows. */
    private void reorder(List<CharacterEntry> ordered) {
        actions.reorder(ordered.stream().map(entry -> entry.character().name()).toList());
    }

    /**
     * A plain click is not a reorder, and what it means is read off the cell it landed in.
     *
     * <p>The <strong>status</strong> cell is settled first and ends the routing whatever it decides: it
     * is the only cell that can <em>remove</em> the row under the pointer, and a second click there must
     * not be taken for a double-click on whichever row moved up into its place.
     *
     * <p>Everywhere else, a click asks for the picker — on the <strong>class</strong> cell, which says
     * {@code Pick a class} until one is chosen, or <strong>anywhere on the row twice</strong>. The
     * double-click is the way back: once a class is pinned its cell is a quiet grey word, and a player
     * who mis-clicked a class has no invitation left to aim at. Opening the picker changes nothing on its
     * own, so the single click that opens it before the double one lands is harmless to repeat.
     */
    private void onRowClick(JList<CharacterEntry> list, int index, Point point, int clicks) {
        if (index < 0 || index >= model.size()) {
            return;
        }
        final var entry = model.get(index);
        if (entry.character().name().equals(OverlayContent.LOGGING_IN)) {
            return; // a not-yet-logged-in window has no character to forget or to give a class to
        }

        final var cell = list.getCellBounds(index, index);
        if (cell == null) {
            return;
        }

        final var columns = new RowColumns(scale, cell.width);
        final var x = point.x - cell.x;

        if (columns.inStatus(x)) {
            // The cross is drawn on disconnected characters only; a connected row carries a dot there,
            // which is a state and not a control.
            if (!entry.connected()) {
                actions.forget(entry.character().name());
            }
            return;
        }

        if (clicks > 1 || columns.inClass(x)) {
            openPicker.accept(entry.character().name());
        }
    }
}
