package fr.minobot.core;

import fr.minobot.core.domain.DofusClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Layouts: whose is whose, what is learned, and what outlives the session. */
class SpellLayoutsTest {

    @TempDir
    Path folder;

    private Path file() {
        return folder.resolve("spell-layouts.json");
    }

    @Test
    @DisplayName("a character uses their class's layout, or their own when they have no class")
    void namesTheLayoutByClass() {
        final var layouts = new SpellLayouts(file());

        assertThat(layouts.layoutOf("Foxtrot", DofusClass.CRA)).isEqualTo("Crâ");
        assertThat(layouts.layoutOf("Foxtrot", null)).isEqualTo("Foxtrot");
    }

    @Test
    @DisplayName("a layout given by hand in the file wins")
    void aLayoutGivenByHandWins() throws IOException {
        Files.writeString(file(), """
                {"layouts": {}, "characters": {"Foxtrot": "Crâ terre"}}""");

        assertThat(new SpellLayouts(file()).layoutOf("foxtrot", DofusClass.CRA)).isEqualTo("Crâ terre");
    }

    @Test
    @DisplayName("a slot learned is known, by its spell's letters, and outlives the session")
    void learnsAndRemembers() {
        new SpellLayouts(file()).learn("Crâ", 3, "Flèche d'Expiation");

        final var reloaded = new SpellLayouts(file());
        assertThat(reloaded.spellAt("Crâ", 3)).contains("Flèche d'Expiation");
        assertThat(reloaded.slotOf("Crâ", "fleche d'expiation")).hasValue(3);
        assertThat(reloaded.slotOf("Iop", "Flèche d'Expiation")).isEmpty();
    }

    @Test
    @DisplayName("a spell learned in another slot leaves the one it was in, and takes the slot from what was there")
    void aSpellIsInOneSlot() {
        final var layouts = new SpellLayouts(file());
        layouts.learn("Crâ", 3, "Flèche");
        layouts.learn("Crâ", 4, "Pluie");

        layouts.learn("Crâ", 4, "Flèche");

        assertThat(layouts.slotOf("Crâ", "Flèche")).hasValue(4);
        assertThat(layouts.spellAt("Crâ", 3)).isEmpty();
        assertThat(layouts.slotOf("Crâ", "Pluie")).isEmpty();
    }

    @Test
    @DisplayName("a file that cannot be read is left as it is")
    void leavesABrokenFileAlone() throws IOException {
        Files.writeString(file(), "{ not json");

        new SpellLayouts(file()).learn("Crâ", 3, "Flèche");

        assertThat(Files.readString(file())).isEqualTo("{ not json");
    }
}
