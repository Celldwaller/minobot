package fr.minobot.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.Sex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The settings a player is expected to change: their characters, their hotkeys.
 *
 * <p>Everything else the application needs is a constant living next to the code that uses it — the
 * timings, the game's window-title format, the log plumbing. They are dictated by Windows or by the
 * game, not by the player, and exposing them only invited someone to break the features by tuning
 * them.
 *
 * <p>A blank hotkey turns its feature off. Any field absent from the user's {@code config.json} keeps
 * the value given in {@link #defaults()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Config(
        @JsonProperty("log_level") String logLevel,
        @JsonProperty("multiclick_hotkey") String multiclickHotkey,
        @JsonProperty("multiclick_exclude") List<String> multiclickExclude,
        @JsonProperty("reset_windows_hotkey") String resetWindowsHotkey,
        @JsonProperty("group_invite_hotkey") String groupInviteHotkey,

        /**
         * The characters the player has told the overlay about: the order they are cycled in, and the
         * class and sex pinned to each. One list, not three maps — a {@link Character} carries its own
         * name, class and sex, so a new thing a character owns is a field on it rather than another map
         * to keep in step. Persisted the way the cycle order always was (see {@code OverlayState}); a
         * character the player has not launched right now still keeps whatever they pinned to it.
         */
        @JsonProperty("characters") List<Character> characters,

        @JsonProperty("window_cycle_next_hotkey") String windowCycleNextHotkey,
        @JsonProperty("window_cycle_prev_hotkey") String windowCyclePrevHotkey,
        @JsonProperty("window_reorder_hotkey") String windowReorderHotkey,
        @JsonProperty("overlay_hotkey") String overlayHotkey,

        /**
         * The key that toggles the turn-passer. Unlike every other hotkey, a blank one does not disable
         * the feature — it only leaves it without a key, since the {@link #autoPassTurn} switch on the
         * overlay drives it too. So this is the one hotkey that is a <em>toggle</em> rather than an
         * enable-by-blank. Its combination is persisted like the others (see {@code OverlayState}); the
         * switch's on/off <em>state</em> is not.
         */
        @JsonProperty("auto_pass_turn_hotkey") String autoPassTurnHotkey,

        @JsonProperty("overlay_scale") double overlayScale,

        /**
         * Whether the turn-passer ends each character's turn on its own. Every other feature is turned
         * off by a blank hotkey; this one has no hotkey to blank — it is a switch on the overlay, not a
         * key — so it earns the one explicit flag in this record. The overlay flips it, and a restart
         * forgets it: unlike the order and the keybinds, this switch is not persisted, deliberately.
         */
        @JsonProperty("auto_pass_turn") boolean autoPassTurn,

        /**
         * Whether an exchange one of the player's own characters asks of another is accepted for them.
         * The same kind of switch as {@link #autoPassTurn}: no hotkey, flipped on the overlay, and not
         * persisted — forgotten on restart.
         */
        @JsonProperty("auto_accept_trade") boolean autoAcceptTrade,

        /**
         * Whether the spells each character cast this fight are crossed out on their spell bar — the help
         * for the <em>Économe</em> challenge, which forbids casting one twice. A switch like
         * {@link #autoPassTurn}, flipped on the overlay and forgotten on restart: it is for the fights
         * the player takes the challenge in.
         */
        @JsonProperty("econome") boolean econome,

        /**
         * Whether the slots of the spell bar are framed, known or not, so the player can teach Minobot
         * which spell each one holds. A switch like {@link #econome}, for the fight it takes to learn them.
         */
        @JsonProperty("spell_setup") boolean spellSetup
) {

    /**
     * The ends of the overlay's slider, and the bounds a hand-written {@code config.json} is held to.
     *
     * <p>The panel is drawn at this many times its natural size, so a zero would be a panel with
     * nothing in it — which is why the value is clamped rather than trusted.
     */
    public static final double MIN_OVERLAY_SCALE = 1.0;
    public static final double MAX_OVERLAY_SCALE = 2.0;

    /** The values written to disk when no {@code config.json} exists yet. */
    public static Config defaults() {
        return new Config(
                "INFO",
                "x1",
                List.of(),
                "shift+x1",
                "F8",
                // No character is known until a window is detected, and none has a class or a sex until
                // the player pins one on the overlay.
                List.of(),
                "x2",
                "shift+x2",
                "F9",
                "shift+space",
                // Shift + middle-click toggles the turn-passer. A default the player can rebind, and — unlike
                // the others — a key that toggles rather than one whose blank turns the feature off.
                "shift+middle",
                // Swing's natural sizes were laid out for a 96-DPI desktop, and the game is played on a
                // screen twice that. Unscaled, the panel is legible and nobody wants to read it.
                1.5,
                // Off by default: it plays the characters for the player, which is a thing they switch
                // on deliberately, never a thing they should find already running.
                false,
                // On by default: passing items between one's own accounts is the bread and butter of
                // multi-boxing, and it only ever fires on a trade the player's own character asked for.
                true,
                // Off: a challenge is taken fight by fight.
                false,
                false
        );
    }

    public Config {
        multiclickExclude = copyOrEmpty(multiclickExclude);
        characters = copyOrEmpty(characters);
        overlayScale = Math.clamp(overlayScale, MIN_OVERLAY_SCALE, MAX_OVERLAY_SCALE);
    }

    /**
     * The character names in cycle order — what {@code WindowManager} ranks the game windows by. The
     * order <em>is</em> the list's order, so this is a plain projection of it, read fresh at every use.
     */
    public List<String> characterOrder() {
        return characters.stream().map(Character::name).toList();
    }

    /**
     * The same configuration with one hotkey changed — how the overlay rebinds a key.
     *
     * <p>A blank combination turns the feature off, which is what makes this the toggle too.
     */
    public Config withHotkey(Feature feature, String combination) {
        return new Config(
                logLevel,
                hotkeyOf(Feature.MULTICLICK, feature, combination),
                multiclickExclude,
                hotkeyOf(Feature.RESET_WINDOWS, feature, combination),
                hotkeyOf(Feature.GROUP_INVITE, feature, combination),
                characters,
                hotkeyOf(Feature.WINDOW_CYCLE_NEXT, feature, combination),
                hotkeyOf(Feature.WINDOW_CYCLE_PREV, feature, combination),
                hotkeyOf(Feature.WINDOW_REORDER, feature, combination),
                hotkeyOf(Feature.OVERLAY, feature, combination),
                hotkeyOf(Feature.AUTO_PASS_TURN, feature, combination),
                overlayScale,
                autoPassTurn,
                autoAcceptTrade,
                econome,
                spellSetup);
    }

    /** The new combination for the feature being rebound, the current one for every other. */
    private String hotkeyOf(Feature slot, Feature rebound, String combination) {
        return slot == rebound ? combination : slot.hotkeyIn(this);
    }

    /**
     * The same configuration in a new character order — how the overlay's drag & drop lands.
     *
     * <p>It <strong>merges</strong> rather than replaces: the named characters take the order the player
     * dragged them into, each keeping the class and sex already pinned to it, and any character the player
     * cannot see right now — saved with a class or sex but not launched — is kept at the end. A reorder of
     * who is on screen must never drop what was pinned to who is not.
     */
    public Config withCharacterOrder(List<String> names) {
        final var byName = new LinkedHashMap<String, Character>();
        for (final var character : characters) {
            byName.put(character.name(), character);
        }

        final var reordered = new ArrayList<Character>();
        for (final var name : names) {
            final var known = byName.remove(name);
            reordered.add(known != null ? known : new Character(name));
        }
        // Whatever the player did not name is one of ours they are not looking at: keep it, and its class.
        reordered.addAll(byName.values());
        return withCharacters(reordered);
    }

    /** The same configuration with a different set of characters left out of the multi-click. */
    public Config withMulticlickExclude(List<String> excluded) {
        return new Config(
                logLevel, multiclickHotkey, excluded, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, autoPassTurn, autoAcceptTrade, econome, spellSetup);
    }

    /** The same configuration with the panel drawn bigger or smaller — how its slider lands. */
    public Config withOverlayScale(double scale) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, scale, autoPassTurn, autoAcceptTrade, econome, spellSetup);
    }

    /** The same configuration with the turn-passer switched on or off — how the overlay's toggle lands. */
    public Config withAutoPassTurn(boolean enabled) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, enabled, autoAcceptTrade, econome, spellSetup);
    }

    /** The same configuration with the trade-accepter switched on or off — the overlay's other toggle. */
    public Config withAutoAcceptTrade(boolean enabled) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, autoPassTurn, enabled, econome, spellSetup);
    }

    /** The same configuration with the spell marks switched on or off — the overlay's Économe switch. */
    public Config withEconome(boolean enabled) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, autoPassTurn, autoAcceptTrade, enabled, spellSetup);
    }

    /** The same configuration with the spell bar's setup switched on or off. */
    public Config withSpellSetup(boolean enabled) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, autoPassTurn, autoAcceptTrade, econome, enabled);
    }

    /**
     * The same configuration with one character pinned to a class — how the overlay's class picker lands.
     * Keyed by name: a character not yet in the list is added, keeping its place in the cycle order.
     */
    public Config withCharacterClass(String character, DofusClass clazz) {
        return withCharacters(upsert(character, existing -> existing.withClass(clazz)));
    }

    /** The same configuration with one character pinned to a sex — the picker's other half. */
    public Config withCharacterSex(String character, Sex sex) {
        return withCharacters(upsert(character, existing -> existing.withSex(sex)));
    }

    /**
     * The same configuration without a character — how the overlay's forget cross lands. Its class and
     * sex go with it: the whole {@link Character} leaves the list. A name that is not in the list leaves
     * the list unchanged.
     */
    public Config withoutCharacter(String name) {
        return withCharacters(characters.stream()
                .filter(character -> !character.name().equals(name))
                .toList());
    }

    /**
     * The character list with one character changed, found by name — or added, so the first thing a
     * player pins to a character not yet cycled still lands. Everyone else keeps their place and pins.
     */
    private List<Character> upsert(String name, UnaryOperator<Character> change) {
        final var updated = new ArrayList<Character>(characters.size() + 1);
        var found = false;
        for (final var character : characters) {
            if (character.name().equals(name)) {
                updated.add(change.apply(character));
                found = true;
            } else {
                updated.add(character);
            }
        }
        if (!found) {
            updated.add(change.apply(new Character(name)));
        }
        return updated;
    }

    private Config withCharacters(List<Character> characters) {
        return new Config(
                logLevel, multiclickHotkey, multiclickExclude, resetWindowsHotkey, groupInviteHotkey,
                characters, windowCycleNextHotkey, windowCyclePrevHotkey, windowReorderHotkey,
                overlayHotkey, autoPassTurnHotkey, overlayScale, autoPassTurn, autoAcceptTrade, econome, spellSetup);
    }

    private static <T> List<T> copyOrEmpty(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
