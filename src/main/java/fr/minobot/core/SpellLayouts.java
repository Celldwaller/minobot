package fr.minobot.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.minobot.core.domain.Cast;
import fr.minobot.core.domain.DofusClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Which spell sits in which slot of a spell bar — a <em>layout</em>, named, shared by the characters who
 * lay their bar out the same way — kept in {@code spell-layouts.json}.
 *
 * <p>A character uses the layout they are given in the file's {@code characters}; failing that, the one
 * named after their class, since the characters of one class tend to lay out their spells alike; failing
 * that, one named after themselves. A layout is learned, not typed: the player clicks a slot and casts
 * what it holds ({@link #learn}). The weapon's slot is never learned — whatever is swung, it is there.
 *
 * <p>The file is the player's too: a layout may be renamed, or a character given another, by hand. A file
 * that cannot be read is left alone, and nothing is learned over it.
 */
public final class SpellLayouts {

    private static final Logger log = LoggerFactory.getLogger(SpellLayouts.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;

    /**
     * Held across a lesson and its write, so two lessons at once reach the file in the order they were
     * learned; the layouts' own lock is not, so a read never waits on the disk.
     */
    private final Object writeLock = new Object();

    /** Guarded by {@code this}; replaced on every change, so a save writes one state whole. */
    private Layouts layouts;

    /** Whether the file was read, or there was none: a file that could not be read is never overwritten. */
    private final boolean writable;

    public SpellLayouts(Path path) {
        this.path = path;
        Layouts loaded = new Layouts(Map.of(), Map.of());
        var readable = true;
        if (Files.exists(path)) {
            try {
                loaded = MAPPER.readValue(path.toFile(), Layouts.class);
            } catch (IOException e) {
                log.error("Could not read the spell layouts in {}; none will be learned: {}", path, e.getMessage());
                readable = false;
            }
        }
        this.layouts = loaded.copy();
        this.writable = readable;
    }

    /** The layout a character uses. @param clazz their class, {@code null} when the player has not said */
    public synchronized String layoutOf(String character, DofusClass clazz) {
        final var given = layouts.characters().get(character);
        if (given != null) {
            return given;
        }
        return clazz != null ? clazz.label() : character;
    }

    /** The slot a layout holds an action in. */
    public synchronized OptionalInt slotOf(String layout, String action) {
        final var key = Cast.key(action);
        return layouts.layouts().getOrDefault(layout, Map.of()).entrySet().stream()
                .filter(entry -> Cast.key(entry.getValue()).equals(key))
                .mapToInt(Map.Entry::getKey)
                .findFirst();
    }

    /** The spell a layout holds in a slot. */
    public synchronized Optional<String> spellAt(String layout, int slot) {
        return Optional.ofNullable(layouts.layouts().getOrDefault(layout, Map.of()).get(slot));
    }

    /**
     * Learns that a slot of a layout holds a spell — and no other slot does, nor this slot anything else:
     * a spell moved on the bar is learned again where it went.
     */
    public void learn(String layout, int slot, String spell) {
        synchronized (writeLock) {
            final Layouts saved;
            synchronized (this) {
                if (!writable || spell.equals(spellAt(layout, slot).orElse(null))) {
                    return;
                }
                final var all = new TreeMap<String, Map<Integer, String>>(layouts.layouts());
                final var updated = new TreeMap<Integer, String>(all.getOrDefault(layout, Map.of()));
                final var key = Cast.key(spell);
                updated.values().removeIf(held -> Cast.key(held).equals(key));
                updated.put(slot, spell);
                all.put(layout, updated);
                layouts = new Layouts(all, layouts.characters()).copy();
                saved = layouts;
            }
            log.info("Layout '{}': slot {} holds '{}'.", layout, slot, spell);
            save(saved);
        }
    }

    /** Whether the character uses a layout of their own rather than the one of their class. */
    public synchronized boolean hasOwn(String character) {
        return layouts.characters().containsKey(character);
    }

    /**
     * Gives a character a layout of their own — for one who lays out their spells unlike the rest of their
     * class — or gives them back their class's. Their own starts as a copy of what they used, so only what
     * differs is learned again; and it is kept when they go back, ready for the next time.
     *
     * @param clazz their class, {@code null} when the player has not said
     */
    public void toggleOwn(String character, DofusClass clazz) {
        synchronized (writeLock) {
            final Layouts saved;
            synchronized (this) {
                if (!writable) {
                    return;
                }
                final var given = new TreeMap<String, String>(layouts.characters());
                final var all = new TreeMap<String, Map<Integer, String>>(layouts.layouts());
                if (given.remove(character) == null) {
                    all.putIfAbsent(character, all.getOrDefault(layoutOf(character, clazz), Map.of()));
                    given.put(character, character);
                }
                layouts = new Layouts(all, given).copy();
                saved = layouts;
            }
            log.info("'{}' now uses the layout '{}'.", character, layoutOf(character, clazz));
            save(saved);
        }
    }

    /** Through a temp file and an atomic move, so the next start never reads half a file. */
    private void save(Layouts saved) {
        try {
            final var parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            final var tmp = Files.createTempFile(parent, "spell-layouts", ".json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), saved);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not save the spell layouts: {}", e.getMessage());
        }
    }

    /**
     * The file: the layouts by name — slot → spell — and the characters given a layout by hand. Names are
     * kept as written; only what is cast is compared loosely ({@link Cast#key}).
     */
    record Layouts(@JsonProperty("layouts") Map<String, Map<Integer, String>> layouts,
                   @JsonProperty("characters") Map<String, String> characters) {

        Layouts copy() {
            final var all = new TreeMap<String, Map<Integer, String>>(String.CASE_INSENSITIVE_ORDER);
            if (layouts != null) {
                layouts.forEach((name, slots) -> all.put(name, new TreeMap<>(slots == null ? Map.of() : slots)));
            }
            final var given = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            if (characters != null) {
                given.putAll(characters);
            }
            return new Layouts(all, given);
        }
    }
}
