package fr.minobot.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Everything the player reads, in one language: looked up by key in {@code /i18n/<code>.properties}, so a
 * phrase is changed in a file and a language is added as one.
 *
 * <p>Placeholders are {@code {0}}, {@code {1}}… replaced as they stand — deliberately not
 * {@link java.text.MessageFormat}, whose quoting rules would turn every French apostrophe into a bug.
 *
 * <p>A key missing from a file is drawn as the key itself, never as nothing: a blank on the panel reads as
 * broken, a key reads as a typo to report. {@code WordsTest} keeps the files in step so it does not ship.
 */
public final class Words {

    private static final Logger log = LoggerFactory.getLogger(Words.class);

    /** Read once, at the first lookup: the files ride inside the jar and never change under it. */
    private static final Map<Language, Words> LOADED = loadAll();

    private final Language language;
    private final Properties phrases;

    private Words(Language language, Properties phrases) {
        this.language = language;
        this.phrases = phrases;
    }

    public static Words in(Language language) {
        return LOADED.get(language);
    }

    public Language language() {
        return language;
    }

    /** @param args filling {@code {0}}, {@code {1}}… — a {@link Text} among them is phrased too */
    public String get(String key, Object... args) {
        final var phrase = phrases.getProperty(key);
        if (phrase == null) {
            log.warn("No '{}' phrase for the key '{}'.", language.code(), key);
            return key;
        }

        var filled = phrase;
        for (var index = 0; index < args.length; index++) {
            final var value = args[index] instanceof Text text ? get(text) : String.valueOf(args[index]);
            filled = filled.replace("{" + index + "}", value);
        }
        return filled;
    }

    /** The phrase for a key that may have none — a key name, most of which are their own word. */
    public Optional<String> find(String key) {
        return Optional.ofNullable(phrases.getProperty(key));
    }

    public String get(Text text) {
        return switch (text) {
            case Text.Phrase phrase -> get(phrase.key(), phrase.args().toArray());
            case Text.Verbatim verbatim -> verbatim.text();
        };
    }

    /** Every key this language has a phrase for — how the tests hold the files to one another. */
    Set<String> keys() {
        return phrases.stringPropertyNames();
    }

    private static Map<Language, Words> loadAll() {
        final var all = new EnumMap<Language, Words>(Language.class);
        for (final var language : Language.values()) {
            all.put(language, new Words(language, load(language)));
        }
        return all;
    }

    /** A missing file is a broken build, not a player's problem to tolerate: it fails loudly. */
    private static Properties load(Language language) {
        final var resource = "/i18n/" + language.code() + ".properties";
        final var stream = Words.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("No " + resource + " on the classpath.");
        }

        // A reader and not the stream: Properties.load(InputStream) reads ISO-8859-1, and French is not.
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            final var phrases = new Properties();
            phrases.load(reader);
            return phrases;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }
}
