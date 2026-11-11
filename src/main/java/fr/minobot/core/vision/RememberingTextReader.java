package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.TextReader;

import java.util.Arrays;
import java.util.Optional;

/**
 * An OCR that does not read the same image twice in a row: handed the very pixels it read last, it
 * answers what it read then.
 *
 * <p>What is read on screen stands still for many looks — a turn banner for a second, a request box
 * until it is answered — and every look crops the same strip again. Comparing a few thousand pixels is
 * far cheaper than a pass through the engine.
 *
 * <p>It remembers <em>one</em> image, so each reader of the screen takes its own: shared between the
 * banner and the request box, the two strips would push each other out at every look. A failed read is
 * not remembered — the engine is asked again next time.
 */
public final class RememberingTextReader implements TextReader {

    private final TextReader engine;

    private Capture lastImage;
    private Optional<String> lastText = Optional.empty();

    public RememberingTextReader(TextReader engine) {
        this.engine = engine;
    }

    @Override
    public synchronized Optional<String> read(Capture image) {
        if (lastImage != null && sameImage(lastImage, image)) {
            return lastText;
        }
        final var text = engine.read(image);
        lastImage = text.isPresent() ? image : null;
        lastText = text;
        return text;
    }

    private static boolean sameImage(Capture a, Capture b) {
        return a.width() == b.width() && a.height() == b.height() && Arrays.equals(a.pixels(), b.pixels());
    }
}
