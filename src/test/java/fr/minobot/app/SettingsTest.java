package fr.minobot.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The live configuration: what the overlay changes, and who hears about it. */
class SettingsTest {

    @Nested
    class Changing {

        @Test
        @DisplayName("a change is visible to everyone reading through the settings")
        void swapsTheConfiguration() {
            final var settings = TestConfigs.settings(Map.of("characters", TestConfigs.characters("Bravo")));

            settings.update(config -> config.withCharacterOrder(List.of("Alpha", "Bravo")));

            assertThat(settings.get().characterOrder()).containsExactly("Alpha", "Bravo");
        }

        @Test
        @DisplayName("the listeners are told, and are handed the configuration that took effect")
        void notifiesTheListeners() {
            final var settings = TestConfigs.settings(Map.of());
            final var seen = new ArrayList<String>();
            settings.onChange(config -> seen.add(Feature.GROUP_INVITE.hotkeyIn(config)));

            settings.update(config -> config.withHotkey(Feature.GROUP_INVITE, "F7"));

            assertThat(seen).containsExactly("F7");
        }

        @Test
        @DisplayName("a change swaps in a new Config and never mutates the old one")
        void keepsTheChangeInMemory() {
            final var initial = TestConfigs.with(Map.of("characters", TestConfigs.characters("Bravo")));
            final var settings = new Settings(initial);

            settings.update(config -> config.withCharacterOrder(List.of("Alpha")));

            assertThat(initial.characterOrder())
                    .as("the Config that was loaded is untouched — Settings swaps, never edits in place")
                    .containsExactly("Bravo");
        }
    }

    @Nested
    class Editing {

        @Test
        @DisplayName("rebinding one feature leaves every other key where it was")
        void changesOneHotkeyOnly() {
            final var config = TestConfigs.with(Map.of()).withHotkey(Feature.GROUP_INVITE, "F7");

            assertThat(Feature.GROUP_INVITE.hotkeyIn(config)).isEqualTo("F7");
            assertThat(Feature.MULTICLICK.hotkeyIn(config)).isEqualTo("x1");
            assertThat(Feature.WINDOW_CYCLE_NEXT.hotkeyIn(config)).isEqualTo("x2");
            assertThat(Feature.WINDOW_CYCLE_PREV.hotkeyIn(config)).isEqualTo("shift+x2");
            assertThat(Feature.WINDOW_REORDER.hotkeyIn(config)).isEqualTo("F9");
            assertThat(Feature.RESET_WINDOWS.hotkeyIn(config)).isEqualTo("shift+x1");
        }

        @Test
        @DisplayName("a blank hotkey is how a feature is turned off — there is no enabled flag")
        void clearingAHotkeyDisablesTheFeature() {
            final var config = TestConfigs.with(Map.of()).withHotkey(Feature.WINDOW_REORDER, "");

            assertThat(Feature.WINDOW_REORDER.hotkeyIn(config)).isEmpty();
        }

        @Test
        @DisplayName("a hotkey set to null in config.json reads as off, and does not blow up")
        void readsANullHotkeyAsBlank() {
            final var config = new Config("INFO", "x1", List.of(), "shift+x1", null,
                    List.of(), "x2", "shift+x2", "F9", "F10", "shift+middle", 1.5, false, false, false, false, null);

            assertThat(Feature.GROUP_INVITE.hotkeyIn(config)).isEmpty();
        }
    }
}
