package fr.minobot.core;

import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Request;
import fr.minobot.win32.Win32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * What is asked of the player's characters — a group invitation, an exchange — one announcement per
 * request, whoever saw it.
 *
 * <p>The same as {@link FightTurns}, for requests: the box stays on the asked character's screen until it
 * is answered and is read there again at every look. A feature that answers a request presses a key, and
 * a key pressed for a request already answered lands in the game — so every sighting is reported here,
 * and the features listen here.
 *
 * <p>A request seen again on the same character is the same request as long as it keeps being seen
 * within {@link #SAME_REQUEST}.
 */
public final class Requests {

    private static final Logger log = LoggerFactory.getLogger(Requests.class);

    /**
     * Longer than the round the screen watcher makes of every window, so a box left unanswered is not
     * asked again at each of its looks.
     */
    static final Duration SAME_REQUEST = Duration.ofSeconds(5);

    private final List<BiConsumer<GameWindow, Request>> listeners = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private final LongSupplier nanos;
    private final Sightings<Long, Request> sightings;

    /** The window a request is awaited on, and until when; {@code null} when none is. */
    private volatile Awaited awaited;

    public Requests() {
        this(Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime);
    }

    /**
     * @param dispatcher where each listener runs; a listener focuses and types, so it must not run on
     *                   the thread of the source that reported the request
     * @param nanos      a {@link System#nanoTime()}-like clock
     */
    public Requests(Executor dispatcher, LongSupplier nanos) {
        this.dispatcher = dispatcher;
        this.nanos = nanos;
        this.sightings = new Sightings<>(SAME_REQUEST, nanos);
    }

    /** Hears of every request as it is made: the character asked, and what they were asked. */
    public void onRequest(BiConsumer<GameWindow, Request> listener) {
        listeners.add(listener);
    }

    /**
     * A request is about to be made of {@code receiver} — the invitation relay has just sent it. Until it
     * is seen there, or {@code within} runs out, the screen watcher looks at that window and no other: left
     * to its round, it reaches the window a whole round late — two seconds with eight characters, at
     * every step of the relay.
     */
    public void expect(GameWindow receiver, Duration within) {
        awaited = new Awaited(receiver.hwnd(), nanos.getAsLong() + within.toNanos());
    }

    /** The window a request is awaited on, or {@link Win32#NULL_HANDLE} when none is. */
    public long awaited() {
        final var current = awaited;
        return current == null || nanos.getAsLong() - current.until() > 0 ? Win32.NULL_HANDLE : current.hwnd();
    }

    /** A source saw {@code request} made of {@code receiver} — or still saw it waiting there. */
    public void report(GameWindow receiver, Request request) {
        report(receiver, request, nanos.getAsLong());
    }

    /** The same, from a source that knows when it saw the request — {@code seenAt} on the {@code nanos} clock. */
    public void report(GameWindow receiver, Request request, long seenAt) {
        final var seen = new Request(request.kind(), request.asker().toLowerCase(Locale.ROOT));
        final var current = awaited;
        if (current != null && current.hwnd() == receiver.hwnd()) {
            awaited = null;
        }
        if (sightings.isNew(receiver.hwnd(), seen)) {
            log.debug("'{}' asks '{}': {}.", request.asker(), receiver.name(), request.kind());
            listeners.forEach(listener -> dispatcher.execute(() -> tell(listener, receiver, request, seenAt)));
        }
    }

    /** The delay is logged as in {@link FightTurns}: from the sighting to the listener's return. */
    private void tell(BiConsumer<GameWindow, Request> listener, GameWindow receiver, Request request, long seenAt) {
        try {
            listener.accept(receiver, request);
            log.debug("A listener of '{}'s {} returned {} ms after it was seen.",
                    request.asker(), request.kind(), (nanos.getAsLong() - seenAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.error("Error in a request listener.", e);
        }
    }

    private record Awaited(long hwnd, long until) {
    }
}
