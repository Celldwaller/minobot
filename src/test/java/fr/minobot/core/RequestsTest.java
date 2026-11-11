package fr.minobot.core;

import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Request;
import fr.minobot.core.domain.Request.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** How sightings of requests become requests: every source reports, every listener hears each once. */
class RequestsTest {

    private static final GameWindow ALPHA = new GameWindow(1, "Alpha - Dofus Retro");
    private static final GameWindow BRAVO = new GameWindow(2, "Bravo - Dofus Retro");
    private static final Request TRADE = new Request(Kind.TRADE, "Zoro");

    private final AtomicLong now = new AtomicLong();
    private final Requests requests = new Requests(Runnable::run, now::get);
    private final List<String> heard = new CopyOnWriteArrayList<>();

    RequestsTest() {
        requests.onRequest((receiver, request) -> heard.add(receiver.name() + " " + request.kind()));
    }

    private void elapse(long millis) {
        now.addAndGet(millis * 1_000_000);
    }

    @Test
    @DisplayName("a box seen at every round of the watcher, and its toast, make one request")
    void hearsARequestOnce() {
        requests.report(BRAVO, TRADE);
        elapse(1000);
        requests.report(BRAVO, new Request(Kind.TRADE, "zoro"));
        for (var round = 0; round < 10; round++) {
            elapse(1000);
            requests.report(BRAVO, TRADE);
        }

        assertThat(heard).containsExactly("Bravo TRADE");
    }

    @Test
    @DisplayName("the same request made of two characters is two requests")
    void tellsCharactersApart() {
        requests.report(ALPHA, TRADE);
        requests.report(BRAVO, TRADE);

        assertThat(heard).containsExactly("Alpha TRADE", "Bravo TRADE");
    }

    @Test
    @DisplayName("another request made of the same character is a new one")
    void tellsRequestsApart() {
        requests.report(BRAVO, TRADE);
        requests.report(BRAVO, new Request(Kind.GROUP_INVITE, "Zoro"));

        assertThat(heard).containsExactly("Bravo TRADE", "Bravo GROUP_INVITE");
    }

    @Test
    @DisplayName("asked again once the first has long gone, it is asked again")
    void aQuietSpellEndsTheRequest() {
        requests.report(BRAVO, TRADE);
        elapse(Requests.SAME_REQUEST.toMillis());
        requests.report(BRAVO, TRADE);

        assertThat(heard).containsExactly("Bravo TRADE", "Bravo TRADE");
    }
}
