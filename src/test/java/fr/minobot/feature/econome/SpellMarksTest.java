package fr.minobot.feature.econome;

import fr.minobot.app.Settings;
import fr.minobot.app.TestConfigs;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.SpellBars;
import fr.minobot.core.SpellLayouts;
import fr.minobot.core.SpellUsage;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Activity;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.vision.SpellBar;
import fr.minobot.ui.FakeMarksView;
import fr.minobot.ui.MarksContent;
import fr.minobot.ui.MarksContent.Kind;
import fr.minobot.ui.MarksContent.Mark;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.Point;
import fr.minobot.win32.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is drawn over the game of the character the player is on, from what the fight log and the screen
 * said — one tick of the follower at a time, with no thread and no screen.
 */
class SpellMarksTest {

    private static final int LEFT_BUTTON = 0x01;

    @TempDir
    Path folder;

    private final FakeWindowApi api = new FakeWindowApi()
            .withWindow(1, "Foxtrot - Dofus Retro v1.48")
            .withWindow(2, "Golf - Dofus Retro v1.48")
            .withForeground(1);
    private final GameWindow foxtrot = new GameWindow(1, "Foxtrot - Dofus Retro v1.48");
    private final Rect area = api.clientArea(1).orElseThrow();

    private final FightTurns turns = new FightTurns(Runnable::run, System::nanoTime);
    private final FightLog fightLog = new FightLog();
    private final SpellUsage usage = new SpellUsage(fightLog, turns);
    private final SpellBars bars = new SpellBars();
    private final ActivityLog activity = new ActivityLog();
    private final AtomicBoolean panelUp = new AtomicBoolean();
    private final FakeMarksView view = new FakeMarksView();

    private Settings settings;
    private SpellLayouts layouts;
    private SpellMarks marks;

    private void start(Map<String, Object> switches) {
        settings = TestConfigs.settings(switches);
        layouts = new SpellLayouts(folder.resolve("spell-layouts.json"));
        marks = new SpellMarks(api, new WindowManager(api, settings), settings, turns, fightLog, usage, bars,
                layouts, activity, panelUp::get, () -> view, System::nanoTime, _ -> {
        });
        fightLog.report(List.of("before the fight"));
    }

    private void cast(String line) {
        fightLog.report(List.of(line));
        fightLog.report(List.of("after " + line));
    }

    private void barUp() {
        bars.report(foxtrot, true, System.nanoTime());
    }

    private Rect slot(int slot) {
        return SpellBar.slot(slot, area.width(), area.height());
    }

    /** A press and a release over the slot, each seen by a tick. */
    private void click(int slot) {
        final var center = slot(slot).center();
        api.withCursor(new Point(area.left() + center.x(), area.top() + center.y()));
        api.press(LEFT_BUTTON);
        marks.tick();
        api.release(LEFT_BUTTON);
        marks.tick();
    }

    private List<Mark> drawn(Kind kind) {
        return view.content().map(MarksContent::marks).orElse(List.of()).stream()
                .filter(mark -> mark.kind() == kind).toList();
    }

    @Test
    @DisplayName("a weapon swung is crossed out on the weapon's slot, over the game")
    void crossesOutTheWeapon() {
        start(Map.of("econome", true));
        turns.report("Foxtrot");
        barUp();

        cast("Foxtrot attaque avec Sceptre du Minotot.");
        marks.tick();

        assertThat(drawn(Kind.USED)).extracting(Mark::slot).containsExactly(slot(SpellBar.WEAPON));
        assertThat(view.anchor()).contains(area);
    }

    @Test
    @DisplayName("a spell whose slot is known is crossed out there; one whose slot is not is named")
    void crossesOutKnownSpells() {
        start(Map.of("econome", true));
        layouts.learn("Foxtrot", 3, "Flèche d'Expiation");
        turns.report("Foxtrot");
        barUp();

        cast("Foxtrot lance Flèche d'Expiation.");
        cast("Foxtrot lance Flèche Explosive.");
        cast("Golf lance Oeil de Taupe.");
        marks.tick();

        assertThat(drawn(Kind.USED)).extracting(Mark::slot).containsExactly(slot(3));
        assertThat(view.content().orElseThrow().hint()).isEqualTo(SpellMarks.UNPLACED_HINT.formatted("Flèche Explosive"));
    }

    @Test
    @DisplayName("no cross while a fighter's sheet covers the bar, nor out of a fight, nor under the panel")
    void hidesWhenThereIsNothingToMark() {
        start(Map.of("econome", true));
        turns.report("Foxtrot");
        cast("Foxtrot attaque avec Sceptre du Minotot.");

        marks.tick();
        assertThat(view.isVisible()).as("no bar seen").isFalse();

        barUp();
        marks.tick();
        assertThat(view.isVisible()).isTrue();

        panelUp.set(true);
        marks.tick();
        assertThat(view.isVisible()).as("the panel is up").isFalse();
    }

    @Test
    @DisplayName("the setup frames every slot, the weapon's known, and names the one under the pointer")
    void framesTheSlotsInSetup() {
        start(Map.of("spell_setup", true));
        layouts.learn("Foxtrot", 3, "Flèche d'Expiation");
        barUp();
        final var center = slot(3).center();
        api.withCursor(new Point(area.left() + center.x(), area.top() + center.y()));

        marks.tick();

        final var content = view.content().orElseThrow();
        assertThat(content.marks()).hasSize(SpellBar.SLOTS);
        assertThat(drawn(Kind.KNOWN)).extracting(Mark::slot).containsExactly(slot(SpellBar.WEAPON), slot(3));
        assertThat(content.marks()).filteredOn(mark -> !mark.label().isEmpty())
                .singleElement().satisfies(mark -> assertThat(mark.label()).isEqualTo("Flèche d'Expiation"));
        assertThat(content.hint()).isEqualTo(SpellMarks.SETUP_HINT.formatted("Foxtrot", "Foxtrot"));
    }

    @Test
    @DisplayName("a slot clicked, then the spell cast, is learned")
    void learnsAClickedSlot() throws InterruptedException {
        start(Map.of("spell_setup", true));
        barUp();

        click(5);
        cast("Foxtrot lance Flèche Explosive.");

        awaitLearned("Flèche Explosive");
        assertThat(layouts.slotOf("Foxtrot", "Flèche Explosive")).hasValue(5);
    }

    @Test
    @DisplayName("another character's cast teaches nothing about the slot clicked")
    void learnsOnlyFromTheClicker() throws InterruptedException {
        start(Map.of("spell_setup", true));

        click(5);
        cast("Golf lance Oeil de Taupe.");
        Thread.sleep(100);

        assertThat(layouts.spellAt("Foxtrot", 5)).isEmpty();
    }

    @Test
    @DisplayName("out of the setup, a spell already known is not moved by a click that may not have cast it")
    void keepsKnownSpellsOutOfSetup() throws InterruptedException {
        start(Map.of("econome", true));
        layouts.learn("Foxtrot", 3, "Flèche d'Expiation");

        click(5);
        cast("Foxtrot lance Flèche d'Expiation.");
        Thread.sleep(100);

        assertThat(layouts.slotOf("Foxtrot", "Flèche d'Expiation")).hasValue(3);
    }

    @Test
    @DisplayName("a character of the player's casting a spell again is written down")
    void writesDownARepeat() {
        start(Map.of("econome", true));

        cast("Foxtrot lance Flèche d'Expiation.");
        cast("Foxtrot lance Flèche d'Expiation.");
        cast("Bouftou lance Morsure.");
        cast("Bouftou lance Morsure.");

        assertThat(activity.recent()).extracting(Activity::detail)
                .containsExactly("Foxtrot cast Flèche d'Expiation twice");
    }

    /** Until the lesson is written: the file is moved into place last, and the folder is deleted after the test. */
    private void awaitLearned(String spell) throws InterruptedException {
        final var file = folder.resolve("spell-layouts.json");
        for (var tries = 0; tries < 200 && (layouts.slotOf("Foxtrot", spell).isEmpty() || !Files.exists(file)); tries++) {
            Thread.sleep(10);
        }
    }
}
