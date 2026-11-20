package fr.minobot.core.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Release numbers: read as published, and put in order part by part. */
class VersionTest {

    private static Version version(String text) {
        return Version.parse(text).orElseThrow();
    }

    @Test
    @DisplayName("parts are compared as numbers, not as text")
    void comparesPartsAsNumbers() {
        assertThat(version("1.10.0").isNewerThan(version("1.9.1"))).isTrue();
        assertThat(version("1.9.1").isNewerThan(version("1.9.0"))).isTrue();
        assertThat(version("1.9.0").isNewerThan(version("1.9.1"))).isFalse();
    }

    @Test
    @DisplayName("a missing part is a zero")
    void aMissingPartIsZero() {
        assertThat(version("1.9")).isEqualTo(version("1.9.0"));
        assertThat(version("2").isNewerThan(version("1.9.9"))).isTrue();
    }

    @Test
    @DisplayName("a leading v and a suffix are not part of the number")
    void ignoresThePrefixAndTheSuffix() {
        assertThat(version("v1.9.2")).isEqualTo(version("1.9.2"));
        assertThat(version("1.9.2-SNAPSHOT")).isEqualTo(version("1.9.2"));
        assertThat(version("1.9.2")).hasToString("1.9.2");
    }

    @Test
    @DisplayName("what is not a release number is not read as one")
    void refusesWhatIsNotANumber() {
        assertThat(Version.parse(null)).isEmpty();
        assertThat(Version.parse("")).isEmpty();
        assertThat(Version.parse("latest")).isEmpty();
        assertThat(Version.parse("1..2")).isEmpty();
    }
}
