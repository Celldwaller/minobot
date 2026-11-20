package fr.minobot.core.update;

import java.util.Optional;

/**
 * Where Minobot's releases are published — the one way out to the network, so the check that reads it
 * stays testable with no connection at all.
 */
@FunctionalInterface
public interface ReleaseFeed {

    /**
     * The tag of the latest release, as published — {@code "1.9.1"}. Empty when it could not be read: no
     * network, the site down or refusing, an answer that makes no sense. Callers tolerate it.
     */
    Optional<String> latest();
}
