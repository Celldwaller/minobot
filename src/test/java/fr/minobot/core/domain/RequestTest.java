package fr.minobot.core.domain;

import fr.minobot.core.domain.Request.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The game's wording of a request, as its box and its toast write it, and as the OCR gives it back. */
class RequestTest {

    @Test
    @DisplayName("an invitation names its asker")
    void readsAnInvitation() {
        assertThat(Request.in("Alpha t'invite à rejoindre son groupe.\nAcceptes-tu ?"))
                .contains(new Request(Kind.GROUP_INVITE, "Alpha"));
    }

    @Test
    @DisplayName("a trade offer names its asker")
    void readsATrade() {
        assertThat(Request.in("Bravo te propose de faire un échange. Acceptes-tu ?"))
                .contains(new Request(Kind.TRADE, "Bravo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Alpha t’invite à rejoindre son groupe.",
            "  Alpha  t'invite a rejoindre son groupe.",
            "Alpha t invite à rejoindre son groupe."})
    @DisplayName("an invitation still reads with the apostrophe, the accent or the spacing the OCR gave it")
    void toleratesTheOcr(String text) {
        assertThat(Request.in(text)).contains(new Request(Kind.GROUP_INVITE, "Alpha"));
    }

    @Test
    void toleratesALostAccentOnTheTrade() {
        assertThat(Request.in("Bravo te propose de faire un echange.")).contains(new Request(Kind.TRADE, "Bravo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vous êtes attaqué !", "C'est à \"Alpha\" de jouer", "Alpha défie Bravo", ""})
    @DisplayName("what asks nothing this application answers is no request")
    void readsNothingElse(String text) {
        assertThat(Request.in(text)).isEmpty();
    }
}
