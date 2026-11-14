package fr.minobot.i18n;

import fr.minobot.app.Feature;
import fr.minobot.core.domain.Sex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The phrases the player reads, and the files that hold them kept in step. */
class WordsTest {

    /** A string literal shaped like a key — {@code "team.title"}; a file name shares the shape, hence the filter. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z][a-z_]*(?:\\.[a-z][a-z_]*)+)\"");

    /** {@code {0}}, {@code {1}}… */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private static final Words FRENCH = Words.in(Language.FR);
    private static final Words ENGLISH = Words.in(Language.EN);

    @Test
    @DisplayName("every language says every phrase: a key missing from one is a hole in the panel")
    void languagesShareTheirKeys() {
        assertThat(ENGLISH.keys()).containsExactlyInAnyOrderElementsOf(FRENCH.keys());
    }

    @Test
    @DisplayName("a phrase has the same placeholders in every language, or an argument goes missing")
    void languagesShareTheirPlaceholders() {
        for (final var key : FRENCH.keys()) {
            assertThat(placeholders(ENGLISH.get(key))).as(key).isEqualTo(placeholders(FRENCH.get(key)));
        }
    }

    @Test
    @DisplayName("every key the code names has a phrase")
    void everyKeyInTheCodeIsPhrased() throws IOException {
        final var namespaces = FRENCH.keys().stream()
                .map(key -> key.substring(0, key.indexOf('.')))
                .collect(Collectors.toSet());

        final var named = new TreeSet<String>();
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (final var source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                final var matcher = KEY_LITERAL.matcher(Files.readString(source));
                while (matcher.find()) {
                    final var key = matcher.group(1);
                    if (namespaces.contains(key.substring(0, key.indexOf('.')))) {
                        named.add(key);
                    }
                }
            }
        }

        assertThat(named).isNotEmpty().allSatisfy(key -> assertThat(FRENCH.keys()).contains(key));
    }

    @ParameterizedTest
    @EnumSource(Feature.class)
    @DisplayName("every feature is named in the keybinds drawer")
    void namesEveryFeature(Feature feature) {
        assertThat(FRENCH.keys()).contains(feature.key());
    }

    @ParameterizedTest
    @EnumSource(Sex.class)
    @DisplayName("every sex is named on the class picker's toggle")
    void namesEverySex(Sex sex) {
        assertThat(FRENCH.keys()).contains(sex.key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"left", "right", "middle", "x1", "x2"})
    @DisplayName("every mouse button is named in words: x1 is Windows' name for it, and nobody else's")
    void namesEveryMouseButton(String button) {
        assertThat(FRENCH.find("key." + button)).isPresent();
    }

    @Test
    @DisplayName("a key that is its own word has no phrase, so it is drawn as written")
    void leavesOrdinaryKeysAlone() {
        assertThat(FRENCH.find("key.f8")).isEmpty();
        assertThat(FRENCH.find("key.x1")).contains("Bouton latéral 1");
    }

    @Test
    @DisplayName("placeholders are filled as they stand, apostrophes and all")
    void fillsPlaceholders() {
        assertThat(ENGLISH.get("activity.turn_passed", "Bravo")).isEqualTo("Passed Bravo's turn");
        assertThat(FRENCH.get("activity.cast_twice", "Foxtrot", "Flèche d'Expiation"))
                .isEqualTo("Foxtrot a lancé Flèche d'Expiation deux fois");
    }

    @Test
    @DisplayName("an activity is phrased in the language it is read in, not the one it was written in")
    void phrasesATextInEitherLanguage() {
        final var switched = Text.of("activity.switched", "Bravo");

        assertThat(FRENCH.get(switched)).isEqualTo("Bascule sur Bravo");
        assertThat(ENGLISH.get(switched)).isEqualTo("Switched to Bravo");
    }

    @Test
    @DisplayName("a text among the arguments is phrased too, and the game's own words are passed on as they came")
    void phrasesNestedAndVerbatimTexts() {
        assertThat(ENGLISH.get("activity.from", Text.of("activity.new_window"))).isEqualTo("from new window");
        assertThat(ENGLISH.get(Text.verbatim("Bravo est attaqué !"))).isEqualTo("Bravo est attaqué !");
    }

    @Test
    @DisplayName("a key with no phrase is drawn as itself, never as a blank that reads as broken")
    void drawsAMissingKeyAsItself() {
        assertThat(FRENCH.get("no.such.key")).isEqualTo("no.such.key");
    }

    private static Set<String> placeholders(String phrase) {
        return PLACEHOLDER.matcher(phrase).results()
                .map(match -> match.group())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the selector offers every language there is a file for")
    void everyLanguageLoads() {
        assertThat(Arrays.stream(Language.values()).map(Words::in)).allSatisfy(words ->
                assertThat(words.keys()).isNotEmpty());
    }
}
