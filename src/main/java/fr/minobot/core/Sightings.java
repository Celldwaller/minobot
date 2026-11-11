package fr.minobot.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tells a new thing on screen from the same thing seen again.
 *
 * <p>What the game shows is seen many times — a banner over the second it stands, a box until it is
 * answered. Per subject (the fight, a character's window), a sighting
 * is the same as the last one while it shows the same thing and the sightings keep coming within
 * {@code window} of each other; anything else is new.
 */
final class Sightings<K, V> {

    private final Duration window;
    private final LongSupplier nanos;
    private final Map<K, Seen<V>> last = new HashMap<>();

    Sightings(Duration window, LongSupplier nanos) {
        this.window = window;
        this.nanos = nanos;
    }

    /** Whether {@code value}, seen now on {@code subject}, is new there. Every sighting counts as one. */
    synchronized boolean isNew(K subject, V value) {
        final var now = nanos.getAsLong();
        final var previous = last.put(subject, new Seen<>(value, now));
        return previous == null || !previous.value().equals(value) || now - previous.nanos() >= window.toNanos();
    }

    private record Seen<V>(V value, long nanos) {
    }
}
