package fr.minobot.core.update;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A release's number, compared part by part — {@code 1.10.0} comes after {@code 1.9.1}, which comparing
 * the text gets wrong. A leading {@code v} and anything after a {@code -} are not part of it:
 * {@code 1.9.2-SNAPSHOT} is the {@code 1.9.2} being built, and is not behind the release of that name.
 * A missing part is a zero: {@code 1.9} and {@code 1.9.0} are the same release.
 */
public record Version(List<Integer> parts) implements Comparable<Version> {

    public Version {
        final var trimmed = new ArrayList<>(parts);
        while (!trimmed.isEmpty() && trimmed.getLast() == 0) {
            trimmed.removeLast();
        }
        parts = List.copyOf(trimmed);
    }

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        var number = text.strip();
        if (number.startsWith("v") || number.startsWith("V")) {
            number = number.substring(1);
        }
        final var dash = number.indexOf('-');
        if (dash >= 0) {
            number = number.substring(0, dash);
        }
        if (!number.matches("\\d{1,9}(\\.\\d{1,9})*")) {
            return Optional.empty();
        }
        return Optional.of(new Version(Arrays.stream(number.split("\\.")).map(Integer::valueOf).toList()));
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        for (var i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
            final int mine = i < parts.size() ? parts.get(i) : 0;
            final int theirs = i < other.parts.size() ? other.parts.get(i) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return parts.isEmpty() ? "0" : String.join(".", parts.stream().map(String::valueOf).toList());
    }
}
