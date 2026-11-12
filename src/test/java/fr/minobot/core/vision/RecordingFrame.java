package fr.minobot.core.vision;

import fr.minobot.win32.Capture;
import fr.minobot.win32.Frame;
import fr.minobot.win32.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A frame that says which regions were read off it — each of which, off the compositor's frame, is a
 * copy off the GPU — and that can refuse them all, as a frame whose window moved on does.
 */
final class RecordingFrame implements Frame {

    private final Capture shown;
    private final boolean readable;
    final List<Rect> regions = new ArrayList<>();

    private RecordingFrame(Capture shown, boolean readable) {
        this.shown = shown;
        this.readable = readable;
    }

    static RecordingFrame of(Capture shown) {
        return new RecordingFrame(shown, true);
    }

    static RecordingFrame unreadable(Capture shown) {
        return new RecordingFrame(shown, false);
    }

    @Override
    public int width() {
        return shown.width();
    }

    @Override
    public int height() {
        return shown.height();
    }

    @Override
    public Optional<Capture> region(int left, int top, int right, int bottom) {
        regions.add(new Rect(left, top, right, bottom));
        return readable ? shown.region(left, top, right, bottom) : Optional.empty();
    }
}
