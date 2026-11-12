package fr.minobot.ui;

import fr.minobot.win32.Rect;

import java.util.List;

/**
 * What the spell marks show over a character's game: a cross on each slot whose spell was cast this fight,
 * and — while the player sets their spells up — a frame round each slot, saying whether it is known, with
 * the name of the spell under the pointer and a line saying what to do.
 *
 * <p>Every rectangle is in the game's client coordinates, measured off the game's own drawing: the view
 * draws it where it is told, and knows nothing of slots.
 *
 * @param hint what the player is asked to do, set apart at the top of the game — blank for nothing
 */
public record MarksContent(double scale, List<Mark> marks, String hint) {

    public MarksContent {
        marks = List.copyOf(marks);
    }

    /** Whether there is nothing to draw. */
    public boolean isEmpty() {
        return marks.isEmpty() && hint.isBlank();
    }

    /** What a slot is marked as. */
    public enum Kind {
        /** Cast this fight: crossed out. */
        USED,
        /** Its spell is known. */
        KNOWN,
        /** Its spell is not known yet. */
        UNKNOWN
    }

    /** @param label written above the slot — blank for nothing */
    public record Mark(Rect slot, Kind kind, String label) {
    }
}
