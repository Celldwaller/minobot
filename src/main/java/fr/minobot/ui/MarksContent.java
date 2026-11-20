package fr.minobot.ui;

import fr.minobot.win32.Rect;

import java.util.List;
import java.util.Optional;

/**
 * What the spell marks show over a character's game: a cross on each slot whose spell was cast this fight,
 * and — while the player sets their spells up — a frame round each slot, saying whether it is known, with
 * the name of the spell under the pointer and a card saying what to do.
 *
 * <p>Every rectangle is in the game's client coordinates, measured off the game's own drawing: the view
 * draws it where it is told, and knows nothing of slots.
 *
 * @param hint  a line set apart at the top of the game, out of the setup — blank for nothing
 * @param guide the setup's card at the top of the game, while the setup is on
 */
public record MarksContent(double scale, List<Mark> marks, String hint, Optional<Guide> guide) {

    public MarksContent {
        marks = List.copyOf(marks);
    }

    /** Whether there is nothing to draw. */
    public boolean isEmpty() {
        return marks.isEmpty() && hint.isBlank() && guide.isEmpty();
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

    /**
     * The setup's card: whose bar is being taught, the steps to teach it — their words between {@code **}
     * standing out — and where the player is in them.
     *
     * @param title    what is going on
     * @param subtitle the character and the layout they teach
     * @param progress how far along they are — blank while the bar is not on screen
     * @param steps    what to do, in order
     * @param status   what Minobot is waiting for right now — blank for nothing
     * @param note     why one might press the button — blank with it
     * @param action   the button, which {@link MarksActions#act} answers — blank for none
     */
    public record Guide(String title, String subtitle, String progress, List<String> steps, String status,
                        String note, String action) {

        public Guide {
            steps = List.copyOf(steps);
        }
    }
}
