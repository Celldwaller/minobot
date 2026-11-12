package fr.minobot.ui;

import fr.minobot.win32.Rect;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The spell marks, without a screen: what was last drawn, and where. */
public final class FakeMarksView implements MarksView {

    private final List<MarksContent> drawn = new CopyOnWriteArrayList<>();
    private volatile Rect anchor;
    private volatile boolean visible;

    @Override
    public void show(MarksContent content, Rect anchor) {
        visible = true;
        drawn.add(content);
        this.anchor = anchor;
    }

    @Override
    public void hide() {
        visible = false;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    /** What is on screen now — empty while hidden. */
    public Optional<MarksContent> content() {
        return visible && !drawn.isEmpty() ? Optional.of(drawn.getLast()) : Optional.empty();
    }

    public Optional<Rect> anchor() {
        return Optional.ofNullable(anchor);
    }

    public int timesDrawn() {
        return drawn.size();
    }
}
