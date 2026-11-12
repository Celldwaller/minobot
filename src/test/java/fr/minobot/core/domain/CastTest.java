package fr.minobot.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The fight log's lines, as the OCR read them off the captures. */
class CastTest {

    @Test
    void readsASpell() {
        assertThat(Cast.in("Foxtrot lance Flèche d'Expiation."))
                .contains(new Cast("Foxtrot", "Flèche d'Expiation", false));
    }

    @Test
    void readsAWeapon() {
        assertThat(Cast.in("Foxtrot attaque avec Sceptre du Minotot."))
                .contains(new Cast("Foxtrot", "Sceptre du Minotot", true));
    }

    @Test
    @DisplayName("a monster's name has spaces in it")
    void readsAMonster() {
        assertThat(Cast.in("Bouftou Royal lance Morsure du Bouftou."))
                .contains(new Cast("Bouftou Royal", "Morsure du Bouftou", false));
    }

    @Test
    @DisplayName("everything else the log says is no cast")
    void readsNothingElse() {
        assertThat(Cast.in("India perd 412 PDV.")).isEmpty();
        assertThat(Cast.in("(Échec critique)")).isEmpty();
        assertThat(Cast.in("India entre dans l'état Pesanteur")).isEmpty();
        assertThat(Cast.in("")).isEmpty();
    }

    @Test
    @DisplayName("an action is known by its letters, whatever the OCR made of its accents and case")
    void keysIgnoreAccentsAndCase() {
        assertThat(Cast.key("Flèche  d'Expiation")).isEqualTo(Cast.key("fleche d'expiation"));
        assertThat(Cast.key("Échec")).isEqualTo("echec");
    }

    @Test
    void namesAreComparedCaseAside() {
        assertThat(new Cast("foxtrot", "Flèche", false).by("Foxtrot")).isTrue();
        assertThat(new Cast("Foxtrot", "Flèche", false).by("Golf")).isFalse();
    }
}
