package fr.minobot.win32;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** An OCR engine that reads whatever a test told it to, and remembers every image it was handed. */
public final class FakeTextReader implements TextReader {

    private final List<Capture> read = new CopyOnWriteArrayList<>();
    private volatile Optional<String> text = Optional.of("");

    /** What every read answers from now on. */
    public FakeTextReader reading(String text) {
        this.text = Optional.of(text);
        return this;
    }

    /** Every read fails from now on, as an engine that gave up would. */
    public FakeTextReader failing() {
        this.text = Optional.empty();
        return this;
    }

    @Override
    public Optional<String> read(Capture image) {
        read.add(image);
        return text;
    }

    /** The images handed to the engine, in order. */
    public List<Capture> images() {
        return List.copyOf(read);
    }
}
