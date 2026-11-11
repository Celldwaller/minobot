package fr.minobot.win32;

import java.util.Optional;

/**
 * Reads the text drawn in an image — the one door to an OCR engine.
 *
 * <p>Like {@link WindowApi}, it is an interface so the code that decides <em>what</em> to read, and what
 * the text means, runs in a test with no engine behind it. The real implementation is {@link WindowsOcr}.
 */
public interface TextReader {

    /**
     * The text in the image, lines joined by spaces — blank when there is none.
     *
     * @return empty when the engine failed on this image, which is a reason to read nothing into it
     */
    Optional<String> read(Capture image);
}
