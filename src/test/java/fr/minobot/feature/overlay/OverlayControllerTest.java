package fr.minobot.feature.overlay;

import fr.minobot.app.Config;
import fr.minobot.app.Feature;
import fr.minobot.app.Settings;
import fr.minobot.app.TestConfigs;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.KeyboardMonitor;
import fr.minobot.core.SpellUsage;
import fr.minobot.core.WhisperLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Activity;
import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.Sex;
import fr.minobot.core.domain.Whisper;
import fr.minobot.core.input.FakeInput;
import fr.minobot.ui.CharacterEntry;
import fr.minobot.ui.FakeOverlayView;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Where the panel goes, what it shows, and what an edit through it changes. */
class OverlayControllerTest {

    private final FakeWindowApi api = new FakeWindowApi()
            .withWindow(1, "Alpha - Dofus Retro")
            .withWindow(2, "Bravo - Dofus Retro")
            .withWindow(3, "Charlie - Dofus Retro")
            .withWindow(9, "Untitled - Notepad");

    private final FakeOverlayView view = new FakeOverlayView();
    private final Settings settings = TestConfigs.settings(Map.of());
    private final ActivityLog activity = new ActivityLog();
    private final WhisperLog whispers = new WhisperLog();
    private final FightLog fightLog = new FightLog();
    private final SpellUsage spells = new SpellUsage(fightLog);

    private OverlayController controller() {
        return controller(new WindowManager(api, settings, activity));
    }

    private OverlayController controller(WindowManager windows) {
        return new OverlayController(api, windows, settings, new KeyboardMonitor(api),
                new FocusManager(api, new FakeInput(api::foregroundWindow)),
                activity, whispers, spells, _ -> view);
    }

    /** The names the panel was last handed to draw, in the order it drew them. */
    private List<String> shownNames() {
        return view.content().orElseThrow().characters().stream()
                .map(entry -> entry.character().name()).toList();
    }

    /** The row the panel last drew under a name, if it drew one — to read its connection state off. */
    private Optional<CharacterEntry> shownEntry(String name) {
        return view.content().orElseThrow().characters().stream()
                .filter(entry -> entry.character().name().equals(name))
                .findFirst();
    }

    /** The character the panel last drew under a name, if it drew one — to read its class and sex off. */
    private Optional<Character> shown(String name) {
        return shownEntry(name).map(CharacterEntry::character);
    }

    /** The character the live configuration holds under a name — what an edit is expected to have saved. */
    private Optional<Character> saved(String name) {
        return settings.get().characters().stream()
                .filter(character -> character.name().equals(name))
                .findFirst();
    }

    @Nested
    @DisplayName("where it goes")
    class Placing {

        @Test
        @DisplayName("it fills the game, and stops short of the title bar Windows draws above it")
        void coversTheClientAreaOnly() {
            // The fake models the frame as an 8px border and a 30px title bar.
            api.withForeground(2).withBounds(2, new Rect(-1920, 0, -1120, 600));

            controller().toggle();

            assertThat(view.isVisible()).isTrue();
            assertThat(view.bounds())
                    .as("the panel covers Bravo's game — not the minimize and close buttons above it — "
                            + "and it does so on a monitor left of the main one")
                    .contains(new Rect(-1912, 30, -1128, 592));
        }

        @Test
        @DisplayName("pressing the hotkey outside the game does nothing at all")
        void ignoresTheHotkeyOutsideTheGame() {
            api.withForeground(9); // the player is in their browser

            controller().toggle();

            assertThat(view.isVisible())
                    .as("there is no character for the panel to belong to: it must not appear anywhere")
                    .isFalse();
            assertThat(view.timesDrawn()).isZero();
        }

        @Test
        @DisplayName("with no window in the foreground at all, it stays away")
        void ignoresTheHotkeyWithNoForegroundWindow() {
            controller().toggle();

            assertThat(view.isVisible()).isFalse();
        }

        @Test
        @DisplayName("it opens on a login window the 30s sweep has not caught, so long as it is the game")
        void opensOnAGameWindowMissingFromTheTrackedList() {
            final var windows = new WindowManager(api, settings);
            windows.refresh(); // the sweep runs while only the already-connected characters are up

            // A fresh account is launched and sits at the login screen — its title carries no name yet,
            // and the sweep will not run again for 30s, so it is not in the tracked list.
            api.withWindow(10, "Dofus Retro v1.48.18")
                    .runningAs(10, "C:\\Users\\me\\AppData\\Local\\Ankama\\Retro\\Dofus Retro.exe")
                    .withForeground(10);

            controller(windows).toggle();

            assertThat(view.isVisible())
                    .as("the panel belongs to whatever game window the player is on, tracked or not")
                    .isTrue();
        }

        @Test
        @DisplayName("a second press takes it away")
        void hidesItOnTheSecondPress() {
            api.withForeground(1);
            final var controller = controller();

            controller.toggle();
            controller.toggle();

            assertThat(view.isVisible()).isFalse();
        }

        @Test
        @DisplayName("hiding it works even from a browser: the panel is up, and the hotkey is the switch")
        void hidesItFromAnywhere() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            api.withForeground(9); // the player has clicked into their browser; the panel is still up
            controller.toggle();

            assertThat(view.isVisible())
                    .as("the rule about game windows guards showing it, not taking it away")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("following its character")
    class Following {

        /** The follower polls every 30 ms; this is room for several turns of it. */
        private void settle() throws InterruptedException {
            Thread.sleep(250);
        }

        @Test
        @DisplayName("the player drags their window, and the panel goes with it")
        void followsTheWindowWhenItMoves() throws InterruptedException {
            api.withForeground(1).withBounds(1, new Rect(0, 0, 800, 600));
            controller().toggle();

            api.moveTo(1, new Rect(500, 200, 1300, 800));
            settle();

            assertThat(view.bounds()).contains(new Rect(508, 230, 1292, 792));
        }

        @Test
        @DisplayName("and when they resize it")
        void followsTheWindowWhenItIsResized() throws InterruptedException {
            api.withForeground(1).withBounds(1, new Rect(0, 0, 800, 600));
            controller().toggle();

            api.moveTo(1, new Rect(0, 0, 1200, 900));
            settle();

            assertThat(view.bounds()).contains(new Rect(8, 30, 1192, 892));
        }

        @Test
        @DisplayName("a character who is minimized takes their panel with them")
        void takesThePanelDownWhenTheCharacterIsMinimized() throws InterruptedException {
            api.withForeground(1);
            controller().toggle();
            assertThat(view.isVisible()).isTrue();

            api.minimize(1);
            settle();

            assertThat(view.isVisible())
                    .as("a panel floating over a game nobody is playing is a panel nobody can dismiss")
                    .isFalse();
        }

        @Test
        @DisplayName("the follower stops with the panel, rather than polling forever")
        void stopsFollowingOnceThePanelIsDown() throws InterruptedException {
            api.withForeground(1).withBounds(1, new Rect(0, 0, 800, 600));
            final var controller = controller();
            controller.toggle();
            controller.toggle();

            api.moveTo(1, new Rect(500, 500, 1300, 1100));
            settle();

            assertThat(view.bounds())
                    .as("the window moved, but nothing is up to follow it")
                    .contains(new Rect(8, 30, 792, 592));
        }
    }

    @Nested
    @DisplayName("what it shows")
    class Listing {

        @Test
        @DisplayName("the characters, in the order they are cycled in")
        void listsTheCharactersInCycleOrder() {
            api.withForeground(1);
            settings.update(config -> config.withCharacterOrder(List.of("Charlie", "Alpha")));

            controller().toggle();

            assertThat(view.content()).isPresent();
            assertThat(shownNames()).containsExactly("Charlie", "Alpha", "Bravo");
        }

        @Test
        @DisplayName("a window with no character loaded yet is listed under a clean label, not its bare title")
        void labelsANotYetLoggedInWindow() {
            api.withForeground(1)
                    .withWindow(10, "Dofus Retro v1.48.18") // a login screen: the game's own title, no name
                    .runningAs(10, "C:\\Users\\me\\AppData\\Local\\Ankama\\Retro\\Dofus Retro.exe");

            controller().toggle();

            assertThat(shownNames())
                    .as("the login window is shown, but under a label rather than the raw game title")
                    .contains("(logging in…)")
                    .doesNotContain("Dofus Retro v1.48.18");
        }

        @Test
        @DisplayName("every feature's key, and a blank one for those the player has turned off")
        void listsTheHotkeys() {
            api.withForeground(1);
            settings.update(config -> config.withHotkey(Feature.WINDOW_REORDER, ""));

            controller().toggle();

            final var hotkeys = view.content().orElseThrow().hotkeys();
            assertThat(hotkeys.get(Feature.MULTICLICK)).isEqualTo("x1");
            assertThat(hotkeys.get(Feature.GROUP_INVITE)).isEqualTo("F8");
            assertThat(hotkeys.get(Feature.WINDOW_REORDER)).as("turned off").isEmpty();
        }
    }

    @Nested
    @DisplayName("keeping its list true")
    class Refreshing {

        /** The roster is polled every two seconds; this is room for a turn of it, and no more. */
        private void settle() throws InterruptedException {
            Thread.sleep(2_500);
        }

        @Test
        @DisplayName("opening the panel re-reads the desktop, rather than trusting the last sweep")
        void refreshesTheRosterWhenItOpens() {
            final var windows = new WindowManager(api, settings, activity);
            windows.refresh(); // the sweep runs while only the three characters are up

            // A fourth account is launched, and the sweep will not run again for 30 seconds.
            api.withForeground(1).withWindow(4, "Delta - Dofus Retro");

            controller(windows).toggle();

            assertThat(shownNames())
                    .as("the panel opens on the desktop as it is, not as the sweep left it")
                    .containsExactly("Alpha", "Bravo", "Charlie", "Delta");
        }

        @Test
        @DisplayName("a character who logs in while it is up lands on the list, with nothing pressed")
        void picksUpACharacterLoggedInWhileItIsOpen() throws InterruptedException {
            api.withForeground(1);
            controller().toggle();
            assertThat(shownNames()).containsExactly("Alpha", "Bravo", "Charlie");

            api.withWindow(4, "Delta - Dofus Retro");
            settle();

            assertThat(shownNames())
                    .as("this is what the panel has instead of a Reload button")
                    .containsExactly("Alpha", "Bravo", "Charlie", "Delta");
        }

        @Test
        @DisplayName("a poll that finds nothing new draws nothing: the panel is rebuilt, never patched")
        void doesNotRedrawWhenTheDesktopIsUnchanged() throws InterruptedException {
            api.withForeground(1);
            controller().toggle();
            final var drawn = view.timesDrawn();

            settle();

            assertThat(view.timesDrawn())
                    .as("a sheet rebuilt every two seconds would drop the row under the player's hand")
                    .isEqualTo(drawn);
        }

        @Test
        @DisplayName("the poll stops with the panel, rather than enumerating the desktop forever")
        void stopsPollingOnceThePanelIsDown() throws InterruptedException {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            controller.toggle();
            final var drawn = view.timesDrawn();

            api.withWindow(4, "Delta - Dofus Retro");
            settle();

            assertThat(view.timesDrawn())
                    .as("a character logged in, but nothing is up to notice")
                    .isEqualTo(drawn);
        }
    }

    @Nested
    @DisplayName("what an edit changes")
    class Editing {

        @Test
        @DisplayName("a drag & drop is in effect at once, and the panel redraws where it stands")
        void reordersTheCharacters() {
            api.withForeground(1).withBounds(1, new Rect(0, 0, 800, 600));
            final var controller = controller();
            controller.toggle();

            controller.reorder(List.of("Charlie", "Bravo", "Alpha"));

            assertThat(settings.get().characterOrder()).containsExactly("Charlie", "Bravo", "Alpha");
            assertThat(shownNames())
                    .as("the panel shows the new order without being asked again")
                    .containsExactly("Charlie", "Bravo", "Alpha");
            assertThat(view.bounds())
                    .as("and it has not moved off its character")
                    .contains(new Rect(8, 30, 792, 592));
        }

        @Test
        @DisplayName("the login label never lands in the saved order: it has no character to cycle by")
        void keepsTheLoginLabelOutOfTheOrder() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.reorder(List.of("Charlie", "(logging in…)", "Alpha", "Bravo"));

            assertThat(settings.get().characterOrder())
                    .as("the placeholder is dropped; the real characters keep the order the player set")
                    .containsExactly("Charlie", "Alpha", "Bravo");
        }

        @Test
        @DisplayName("a rebind lands in the live configuration")
        void rebindsAFeature() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.rebind(Feature.GROUP_INVITE, "shift+F7");

            assertThat(Feature.GROUP_INVITE.hotkeyIn(settings.get())).isEqualTo("shift+F7");
            assertThat(view.content().orElseThrow().hotkeys().get(Feature.GROUP_INVITE))
                    .isEqualTo("shift+F7");
        }

        @Test
        @DisplayName("a blank combination turns the feature off — there is no enabled flag to clear")
        void turnsAFeatureOff() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.rebind(Feature.WINDOW_REORDER, "");

            assertThat(Feature.WINDOW_REORDER.hotkeyIn(settings.get())).isEmpty();
        }

        @Test
        @DisplayName("the slider lands in the live configuration, and the panel redraws at its new size")
        void rescalesThePanel() {
            api.withForeground(1).withBounds(1, new Rect(0, 0, 800, 600));
            final var controller = controller();
            controller.toggle();

            controller.rescale(2.0);

            assertThat(settings.get().overlayScale()).isEqualTo(2.0);
            assertThat(view.content().orElseThrow().scale())
                    .as("the view is told at once: nothing else would redraw it")
                    .isEqualTo(2.0);
            assertThat(view.bounds())
                    .as("a bigger panel still covers the game exactly, and nothing more")
                    .contains(new Rect(8, 30, 792, 592));
        }

        @Test
        @DisplayName("a scale beyond what the slider offers is brought back inside it")
        void clampsAnImpossibleScale() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.rescale(0.0); // what a hand-written config.json, or a bug, could ask for

            assertThat(settings.get().overlayScale())
                    .as("a panel drawn at zero is a panel with nothing in it")
                    .isEqualTo(Config.MIN_OVERLAY_SCALE);
        }

        @Test
        @DisplayName("the auto-pass switch lands in the live configuration, and the panel shows it")
        void togglesAutoPassTurn() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            assertThat(view.content().orElseThrow().autoPassTurn()).as("off by default").isFalse();

            controller.toggleAutoPassTurn(true);

            assertThat(settings.get().autoPassTurn()).isTrue();
            assertThat(view.content().orElseThrow().autoPassTurn())
                    .as("the view is told at once: nothing else would redraw it")
                    .isTrue();
        }

        @Test
        @DisplayName("the hotkey flips auto-pass from whatever it is — the switch's mirror")
        void flipsAutoPassTurn() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            assertThat(settings.get().autoPassTurn()).as("off by default").isFalse();

            controller.flipAutoPassTurn();
            assertThat(settings.get().autoPassTurn()).as("the hotkey turned it on").isTrue();
            assertThat(view.content().orElseThrow().autoPassTurn())
                    .as("and the open panel shows the new state")
                    .isTrue();

            controller.flipAutoPassTurn();
            assertThat(settings.get().autoPassTurn()).as("and off again").isFalse();
        }

        @Test
        @DisplayName("the auto-accept switch lands in the live configuration, and the panel shows it")
        void togglesAutoAcceptTrade() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            assertThat(view.content().orElseThrow().autoAcceptTrade()).as("on by default").isTrue();

            controller.toggleAutoAcceptTrade(false);

            assertThat(settings.get().autoAcceptTrade()).isFalse();
            assertThat(view.content().orElseThrow().autoAcceptTrade())
                    .as("the view is told at once")
                    .isFalse();
        }

        @Test
        @DisplayName("nothing is drawn while the panel is down")
        void doesNotRedrawAHiddenPanel() {
            controller().reorder(List.of("Bravo"));

            assertThat(settings.get().characterOrder()).containsExactly("Bravo");
            assertThat(view.timesDrawn()).isZero();
        }
    }

    @Nested
    @DisplayName("classes")
    class Classes {

        @Test
        @DisplayName("a class pinned to a character lands in the live configuration and on the panel")
        void assignsAClassToACharacter() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            assertThat(view.content().orElseThrow().characters())
                    .as("none to begin with").allMatch(entry -> entry.character().clazz() == null);

            controller.assignClass("Bravo", DofusClass.IOP);

            assertThat(saved("Bravo").orElseThrow().clazz()).isEqualTo(DofusClass.IOP);
            assertThat(shown("Bravo").orElseThrow().clazz())
                    .as("the view is told at once: nothing else would redraw it")
                    .isEqualTo(DofusClass.IOP);
        }

        @Test
        @DisplayName("picking again overwrites the class, rather than keeping both")
        void reassignsAClass() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.assignClass("Bravo", DofusClass.IOP);
            controller.assignClass("Bravo", DofusClass.CRA);

            assertThat(saved("Bravo").orElseThrow().clazz()).isEqualTo(DofusClass.CRA);
        }

        @Test
        @DisplayName("a character pinned a class but not launched is still shown, disconnected, with it")
        void keepsPinnedCharactersWhileDisconnected() {
            api.withForeground(1);
            settings.update(config -> config
                    .withCharacterClass("Bravo", DofusClass.SRAM)
                    // Delta is configured but not on screen: kept and greyed-out, rather than dropped.
                    .withCharacterClass("Delta", DofusClass.XELOR));

            controller().toggle();

            assertThat(shownEntry("Bravo").orElseThrow().connected()).isTrue();
            assertThat(shown("Bravo").orElseThrow().clazz()).isEqualTo(DofusClass.SRAM);
            assertThat(shownEntry("Delta").orElseThrow().connected())
                    .as("pinned but not launched: shown, and marked disconnected")
                    .isFalse();
            assertThat(shown("Delta").orElseThrow().clazz()).isEqualTo(DofusClass.XELOR);
        }

        @Test
        @DisplayName("the login placeholder is never given a class: it has no character to key one by")
        void refusesToClassifyTheLoginPlaceholder() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.assignClass("(logging in…)", DofusClass.FECA);

            assertThat(saved("(logging in…)")).isEmpty();
        }

        @Test
        @DisplayName("a sex pinned to a character lands in the live configuration and on the panel")
        void assignsASexToACharacter() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();
            assertThat(view.content().orElseThrow().characters())
                    .as("none to begin with").allMatch(entry -> entry.character().sex() == null);

            controller.assignSex("Bravo", Sex.FEMALE);

            assertThat(saved("Bravo").orElseThrow().sex()).isEqualTo(Sex.FEMALE);
            assertThat(shown("Bravo").orElseThrow().sex()).isEqualTo(Sex.FEMALE);
        }

        @Test
        @DisplayName("class and sex are kept apart: setting one leaves the other untouched")
        void classAndSexAreIndependent() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.assignClass("Bravo", DofusClass.SRAM);
            controller.assignSex("Bravo", Sex.FEMALE);

            assertThat(saved("Bravo").orElseThrow().clazz()).isEqualTo(DofusClass.SRAM);
            assertThat(saved("Bravo").orElseThrow().sex()).isEqualTo(Sex.FEMALE);
        }

        @Test
        @DisplayName("the login placeholder is never given a sex either")
        void refusesToSexTheLoginPlaceholder() {
            api.withForeground(1);
            final var controller = controller();
            controller.toggle();

            controller.assignSex("(logging in…)", Sex.FEMALE);

            assertThat(saved("(logging in…)")).isEmpty();
        }
    }

    @Nested
    @DisplayName("connected and disconnected")
    class Presence {

        @Test
        @DisplayName("a character on screen is shown connected")
        void marksOnScreenCharactersConnected() {
            api.withForeground(1);

            controller().toggle();

            assertThat(shownEntry("Alpha").orElseThrow().connected()).isTrue();
        }

        @Test
        @DisplayName("a character with nothing pinned vanishes when its window closes")
        void dropsUnpinnedCharactersOnDisconnect() {
            api.withForeground(1);
            // Delta rode into the saved order via a reorder, but the player pinned nothing to it — no
            // class, no sex — so there is nothing to keep it once its window is gone.
            settings.update(config ->
                    config.withCharacterOrder(List.of("Alpha", "Bravo", "Charlie", "Delta")));

            controller().toggle();

            assertThat(shownNames())
                    .as("a bare name with no window is not kept")
                    .containsExactly("Alpha", "Bravo", "Charlie");
        }

        @Test
        @DisplayName("a disconnected character keeps its place in the cycle order, not shoved to the end")
        void keepsCyclePositionWhileDisconnected() {
            api.withForeground(1);
            settings.update(config -> config
                    .withCharacterOrder(List.of("Alpha", "Zulu", "Bravo"))
                    .withCharacterClass("Zulu", DofusClass.IOP)); // pinned, but never launched

            controller().toggle();

            assertThat(shownNames())
                    .as("Zulu is pinned and disconnected: it holds its second-place slot")
                    .containsExactly("Alpha", "Zulu", "Bravo", "Charlie");
            assertThat(shownEntry("Zulu").orElseThrow().connected()).isFalse();
        }

        @Test
        @DisplayName("forgetting a character drops it from the roster and off the panel")
        void forgetsACharacter() {
            api.withForeground(1);
            settings.update(config -> config.withCharacterClass("Delta", DofusClass.IOP)); // pinned, offline
            final var controller = controller();
            controller.toggle();
            assertThat(shownNames()).contains("Delta");

            controller.forget("Delta");

            assertThat(saved("Delta")).as("gone from the configuration").isEmpty();
            assertThat(shownNames()).as("and off the panel").doesNotContain("Delta");
        }

        @Test
        @DisplayName("forgetting the login placeholder does nothing: it was never a saved character")
        void forgetIgnoresTheLoginPlaceholder() {
            api.withForeground(1)
                    .withWindow(10, "Dofus Retro v1.48.18")
                    .runningAs(10, "C:\\Users\\me\\AppData\\Local\\Ankama\\Retro\\Dofus Retro.exe");
            final var controller = controller();
            controller.toggle();

            controller.forget("(logging in…)");

            assertThat(saved("(logging in…)")).isEmpty();
            assertThat(shownNames()).contains("(logging in…)");
        }
    }

    @Nested
    @DisplayName("what it has been doing")
    class Console {

        @Test
        @DisplayName("the panel shows what the features noted, newest first")
        void showsTheActivity() {
            api.withForeground(1);
            activity.record("Taskbar reordered", "3 characters");
            activity.record("Switched to Bravo", "next");

            controller().toggle();

            assertThat(view.content().orElseThrow().activity())
                    .extracting(Activity::what)
                    .containsExactly("Switched to Bravo", "Taskbar reordered");
        }

        @Test
        @DisplayName("the panel shows the whispers, after the cards that carried them are long gone")
        void showsTheWhispers() {
            api.withForeground(1);
            whispers.add("Bravo", "laurealy", "tu passes sur Bonta ?");

            controller().toggle();

            assertThat(view.content().orElseThrow().whispers())
                    .extracting(Whisper::sender, Whisper::message)
                    .containsExactly(tuple("laurealy", "tu passes sur Bonta ?"));
        }

        @Test
        @DisplayName("clearing empties the list and redraws the panel without it")
        void clearsTheWhispers() {
            api.withForeground(1);
            whispers.add("Bravo", "laurealy", "tu passes sur Bonta ?");
            final var controller = controller();
            controller.toggle();

            controller.clearWhispers();

            assertThat(view.content().orElseThrow().whispers()).isEmpty();
        }

        @Test
        @DisplayName("resetting the Easyconome forgets what was cast, and says so")
        void resetsTheEconome() {
            api.withForeground(1);
            fightLog.report(List.of("before"));
            fightLog.report(List.of("Alpha lance Flèche."));
            final var controller = controller();
            controller.toggle();
            assertThat(spells.of("Alpha")).isNotEmpty();

            controller.resetEconome();

            assertThat(spells.of("Alpha")).isEmpty();
            assertThat(view.content().orElseThrow().activity())
                    .extracting(Activity::what)
                    .first().isEqualTo("Easyconome reset");
        }

        @Test
        @DisplayName("clicking a whisper takes the panel down, so it is not covering the answer")
        void openingAWhisperTakesThePanelDown() {
            api.withForeground(1);
            final var whisper = whispers.add("Bravo", "laurealy", "tu passes sur Bonta ?");
            final var controller = controller();
            controller.toggle();
            assertThat(view.isVisible()).isTrue();

            controller.openWhisper(whisper.id());

            assertThat(view.isVisible())
                    .as("the player asked to go there; the panel covers the whole client area")
                    .isFalse();
        }

        @Test
        @DisplayName("clicking a whisper that was cleared under the pointer does nothing")
        void openingAClearedWhisperIsANoOp() {
            api.withForeground(1);
            final var whisper = whispers.add("Bravo", "laurealy", "tu passes sur Bonta ?");
            final var controller = controller();
            controller.toggle();
            whispers.clear();

            controller.openWhisper(whisper.id());

            assertThat(view.isVisible()).as("nothing to go to, so nothing happens").isTrue();
        }

        @Test
        @DisplayName("the team heading can count who is actually on screen")
        void countsWhoIsOnline() {
            api.withForeground(1);
            settings.update(config -> config.withCharacterClass("Delta", DofusClass.IOP)); // pinned, offline

            controller().toggle();

            final var content = view.content().orElseThrow();
            assertThat(content.characters()).hasSize(4);
            assertThat(content.online()).as("Alpha, Bravo and Charlie are up; Delta is not").isEqualTo(3);
        }
    }
}
