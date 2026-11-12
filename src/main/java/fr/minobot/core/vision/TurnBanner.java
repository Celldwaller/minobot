package fr.minobot.core.vision;

import fr.minobot.win32.Frame;
import fr.minobot.win32.TextReader;

import java.util.Optional;

/**
 * Reads, off a capture of any game window, the name of the fighter whose turn has just begun.
 *
 * <p>At the start of every turn the game slides a banner into the top-left corner of the fight — the
 * fighter's illustration, their name on a beige strip, their level below — and holds it about a second.
 * It is drawn in <em>every</em> fighter's window, whoever is watching, so one window is enough to follow
 * the whole fight; and it is the screen, so it does not go deaf the way the game's toasts do after a
 * posted click.
 *
 * <p>The strip is checked before it is read: its beige must stand at both ends, which it only does once
 * the banner has finished sliding in — a banner caught mid-slide is blurred, and reading it would be
 * guessing. The name is then handed to the OCR with a beige margin round it, because the engine finds
 * nothing in text that touches the edge of its image.
 *
 * <p>Positions are the stage's (see {@link GameStage}), measured on a 2560x1369 window.
 */
public final class TurnBanner {

    /** The strip's background, between its dark top and bottom rules. */
    private static final int STRIP_BEIGE = 0xD5CFAA;

    /** The name strip, in stage coordinates: the beige band between the two rules. */
    private static final double STRIP_LEFT = 149.7;
    private static final double STRIP_RIGHT = 314.9;
    private static final double STRIP_TOP = 38.2;
    private static final double STRIP_BOTTOM = 52.4;

    /**
     * Where the strip's beige is checked: its top row, above the letters, at both ends and in between.
     * A long name never reaches the right end; the left end is clear of the first letter. All are inside
     * the strip, which is all of the frame that is read.
     */
    private static final double CHECK_ROW = 39.0;
    private static final double[] CHECK_COLUMNS = {151.7, 232.0, 313.4};

    /** The OCR finds nothing in text that touches its image's edge; this much beige round it is enough. */
    private static final int MARGIN = 20;

    private final TextReader reader;

    public TurnBanner(TextReader reader) {
        this.reader = reader;
    }

    /** The fighter's name on the banner — empty when there is no banner up, or nothing legible on it. */
    public Optional<String> fighter(Frame frame) {
        final var stage = GameStage.of(frame);
        return Region.of(frame, stage.x(STRIP_LEFT), stage.y(STRIP_TOP), stage.x(STRIP_RIGHT), stage.y(STRIP_BOTTOM))
                .filter(strip -> isUp(strip, stage))
                .flatMap(strip -> reader.read(strip.pixels().padded(MARGIN, STRIP_BEIGE)))
                .map(String::strip)
                .filter(name -> !name.isEmpty());
    }

    private static boolean isUp(Region strip, GameStage stage) {
        final var y = stage.y(CHECK_ROW);
        for (final var column : CHECK_COLUMNS) {
            if (!strip.is(stage.x(column), y, STRIP_BEIGE)) {
                return false;
            }
        }
        return true;
    }
}
