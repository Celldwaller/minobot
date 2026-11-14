package fr.minobot.core;

import fr.minobot.core.domain.Activity;
import fr.minobot.i18n.Text;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;

/**
 * What Minobot has just been doing, kept so the panel can show it.
 *
 * <p>Every feature here acts <strong>while the player is looking at something else</strong> — that is the
 * whole point of them. A turn ends in a window the player is not on, a trade is accepted in a blink, a
 * relay walks eight characters. Left with nothing but the result, the player is asked to trust that the
 * right thing happened; a short list of what happened is the difference between a tool and a black box.
 * The log file says all of this already, but a log file is not something a player reads mid-fight.
 *
 * <p>It is deliberately <strong>small and forgetful</strong>: {@link #CAPACITY} entries, oldest dropped,
 * nothing on disk. It answers "what did that just do?" and nothing else — the day it is asked to answer
 * "what happened this session?", it is the log file that should be opened, not this made bigger.
 *
 * <p>Written from every hotkey's and every notification's virtual thread, and read from the panel's:
 * {@link #record} and {@link #recent()} are therefore synchronized on the deque itself. The cost is a
 * lock held for an add and a copy, on a path that already went through the window manager.
 */
public final class ActivityLog {

    /** As many entries as a card can show without scrolling, and a few more for the scroll. */
    public static final int CAPACITY = 30;

    /** Newest last, so an add is a tail push and the eviction is a head poll. */
    private final ArrayDeque<Activity> entries = new ArrayDeque<>(CAPACITY);

    private final Clock clock;

    public ActivityLog() {
        this(Clock.systemDefaultZone());
    }

    /** @param clock what an entry is stamped with — a fixed one in the tests, the wall clock in life */
    public ActivityLog(Clock clock) {
        this.clock = clock;
    }

    /**
     * Notes one act, not yet in words: the panel phrases it in the language the player reads it in.
     *
     * @param what   the act, told of characters — {@code Text.of("activity.turn_passed", "Bravo")}
     * @param detail the circumstance behind it, or blank when the act speaks for itself
     */
    public void record(Text what, Text detail) {
        final var entry = new Activity(clock.instant(), what, detail);
        synchronized (entries) {
            if (entries.size() == CAPACITY) {
                entries.pollFirst();
            }
            entries.addLast(entry);
        }
    }

    /** The same, for an act whose circumstance would only repeat it. */
    public void record(Text what) {
        record(what, Text.NONE);
    }

    /**
     * What has happened, <strong>newest first</strong> — the order the panel reads it in, because the
     * entry the player opened the panel to check is the last one to have been written.
     */
    public List<Activity> recent() {
        synchronized (entries) {
            return entries.reversed().stream().toList();
        }
    }

    /** Drops everything. The player asking for a clean slate, and how the tests get one. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
