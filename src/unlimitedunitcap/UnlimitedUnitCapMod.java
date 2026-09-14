package unlimitedunitcap;

import arc.Core;
import arc.Events;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.game.EventType;
import mindustry.maps.Map;
import mindustry.mod.Mod;

import static mindustry.Vars.*;

/**
 * Unlimited Unit Cap
 * --------------------
 * A small, player-facing quality-of-life mod. It does not implement any custom
 * unit-cap logic of its own; it only flips Mindustry's own
 * {@code Rules#disableUnitCap} flag on or off, and remembers the player's choice
 * separately for every map they play.
 *
 * Flow (see the individual methods below for details):
 *
 *   world loads -> look up saved preference for this map -> apply it to the rules
 *   player taps the toggle -> flip the rule -> save the new preference
 *
 * Everything is driven off of two hooks:
 *  - {@link EventType.WorldLoadEvent}, to apply the right value whenever a level starts
 *    (new game, restart, loading a different map, returning to a previously played map).
 *  - A small toggle button added to the in-game HUD, for the player-facing control itself.
 *
 * On the HUD vs. the pause menu: this mod originally added its toggle directly to
 * Mindustry's pause dialog (Vars.ui.paused). That's still attempted below as a bonus, but
 * it is NOT relied on, because that dialog can be replaced or heavily rebuilt by other
 * installed mods/clients (several popular community client mods reskin the entire pause
 * menu), and there's no way for a mod to guard against another mod doing that. The HUD
 * toggle added by {@link #addHudToggle()} is the guaranteed, primary control: it's a plain
 * widget this mod owns outright, added to Mindustry's normal always-present HUD layer, so
 * its presence doesn't depend on what any other mod does to the menus.
 */
public class UnlimitedUnitCapMod extends Mod{

    /** Prefix for the per-map Core.settings keys, e.g. "unlimited-unit-cap.<mapId>". */
    private static final String PREF_PREFIX = "unlimited-unit-cap.";

    /** Reference to the HUD toggle button, kept around only so we can refresh its label. */
    private TextButton hudToggleButton;

    public UnlimitedUnitCapMod(){
        // --- Core behavior: detect map load, then load + apply the saved preference. ---
        // WorldLoadEvent fires whenever the world/tiles finish loading: a brand new game,
        // restarting the current level, or loading a different one (including returning to
        // a map that was played earlier in the session). It fires the same way in singleplayer
        // and multiplayer, and on both desktop and mobile, so this one hook covers every case
        // asked for.
        Events.on(EventType.WorldLoadEvent.class, event -> applyPreferenceForCurrentMap());

        // --- Player-facing UI ---
        // Neither the HUD nor the pause dialog exist yet at mod-construction time, and
        // there's no UI at all on headless servers, so we wait for the client to finish
        // loading before touching either of them.
        if(!headless){
            Events.on(EventType.ClientLoadEvent.class, event -> Core.app.post(() -> {
                addHudToggle();
                addPauseMenuToggle();
            }));
        }
    }

    /**
     * Called on every world load. Figures out which map is active, loads that map's saved
     * preference (defaulting to OFF if it has never been configured), and applies it to the
     * live ruleset.
     *
     * Defensive by design: if anything about map identification goes wrong, this falls back
     * to leaving the unit cap enabled (OFF) rather than crashing or guessing.
     */
    private void applyPreferenceForCurrentMap(){
        // Edge case: no game actually running (shouldn't normally fire, but cheap to check).
        if(!state.isGame() || state.rules == null) return;

        String id = mapId(state.map);
        boolean enabled = id != null && Core.settings.getBool(PREF_PREFIX + id, false);

        state.rules.disableUnitCap = enabled;
    }

    /**
     * Flips the current game's unit cap rule and immediately persists the new value under the
     * current map's key. Called from whichever toggle control the player used.
     *
     * In multiplayer, Rules are part of the shared game state that the simulation (hosted by
     * the server) uses; this intentionally just flips that same rule rather than inventing a
     * separate per-client bypass, per the mod's design goal of using Mindustry's real unit-cap
     * system. Note that in a netplay session, the value that actually matters for gameplay is
     * the one on the machine simulating the world (the host/server) - a client toggling this
     * only affects what their own UI displays until/unless they are also the host.
     */
    private void toggleUnitCap(){
        if(!state.isGame() || state.rules == null) return;

        boolean newValue = !state.rules.disableUnitCap;
        state.rules.disableUnitCap = newValue;

        String id = mapId(state.map);
        if(id != null){
            Core.settings.put(PREF_PREFIX + id, newValue);
        }else{
            // We couldn't build a stable id for this map (see mapId()) - the toggle still
            // works for the current session, it just won't be remembered next time.
            Log.warn("[UnlimitedUnitCap] Could not identify the current map; the unit-cap toggle will not persist for it.");
        }
    }

    /**
     * Builds a stable, collision-resistant identifier for a map.
     *
     * Mindustry's {@link Map#file} is the on-disk (or in-mod-jar) file the map was loaded
     * from, which is the most reliable identifier available through the public API:
     *  - Two custom maps that happen to share a display name still live at different paths.
     *  - Downloaded/workshop maps each get their own file location.
     *  - Built-in campaign maps are loaded from fixed, stable internal paths.
     *  - Restarting or replaying the same map resolves to the same file, so its saved
     *    preference is found again correctly.
     *
     * Fallback: on the rare chance a Map has no backing file (should not normally happen for
     * anything actually playable), we fall back to a composite of its name/author/dimensions.
     * This is documented as a fallback, not the primary strategy, because two distinct maps
     * could theoretically share all of those values - it's the best that's available without
     * a file to key off of.
     *
     * Returns null (rather than throwing) if the map can't be identified at all, so that
     * callers can safely skip persistence instead of crashing.
     */
    private static String mapId(Map map){
        try{
            if(map == null) return null;

            if(map.file != null){
                // Normalize slashes so the same map produces the same key on every platform
                // (Windows/desktop vs. Android's file paths).
                return "file:" + map.file.path().replace('\\', '/');
            }

            return "tagged:" + map.plainName() + ":" + map.plainAuthor() + ":" + map.width + "x" + map.height;
        }catch(Throwable t){
            // Never let a failure to identify the map crash the game or block level loading.
            Log.err("[UnlimitedUnitCap] Failed to identify current map", t);
            return null;
        }
    }

    /**
     * Adds a small, always-on-screen toggle to the HUD (Vars.ui.hudGroup), in the top-left
     * corner, visible only while a level is actually active. This is the primary, guaranteed
     * toggle control - see the class comment for why it doesn't rely on the pause menu.
     *
     * hudGroup is a plain widget container Mindustry itself uses for on-screen gameplay
     * overlays; adding a widget to it once, at load time, is enough for that widget to persist
     * and render correctly for the rest of the session, with normal mouse/touch input handling
     * on both desktop and mobile.
     */
    private void addHudToggle(){
        try{
            Table root = new Table();
            root.setFillParent(true);
            root.top().left();
            root.margin(8f);

            hudToggleButton = root.button("", this::toggleUnitCap)
                .pad(6f).height(44f).minWidth(190f).get();
            // Refresh the label/visibility from the live rule every frame - keeps the button
            // correct even if something else changes the rule, and hides it outside of a level.
            hudToggleButton.update(this::refreshHudToggle);

            ui.hudGroup.addChild(root);
        }catch(Throwable t){
            // If Mindustry's HUD internals ever change in a future version, fail safe: log it
            // instead of crashing the client. The per-map preference logic above still works
            // regardless; the player just wouldn't see a toggle until this is updated.
            Log.err("[UnlimitedUnitCap] Failed to add HUD toggle", t);
        }
    }

    private void refreshHudToggle(){
        if(hudToggleButton == null) return;

        boolean inGame = state.isGame();
        hudToggleButton.visible = inGame;
        if(!inGame) return;

        boolean enabled = state.rules != null && state.rules.disableUnitCap;
        hudToggleButton.setText("Unlimited Units: " + (enabled ? "ON" : "OFF"));
    }

    /**
     * Best-effort bonus: also try to add a matching row to Mindustry's own pause dialog
     * (Vars.ui.paused.cont), for a more "native menu" feel on setups where nothing else is
     * rebuilding that dialog. Failure here is expected and harmless on setups where another
     * mod owns the pause menu - the HUD toggle above always works regardless.
     */
    private void addPauseMenuToggle(){
        try{
            Table cont = ui.paused.cont;

            cont.row();
            TextButton button = cont.button("", this::toggleUnitCap).growX().pad(4f).height(54f).get();
            button.update(() -> {
                boolean enabled = state.isGame() && state.rules != null && state.rules.disableUnitCap;
                button.setText("Unlimited Units: " + (enabled ? "ON" : "OFF"));
                button.setDisabled(!state.isGame());
            });
            cont.row();
        }catch(Throwable t){
            Log.warn("[UnlimitedUnitCap] Could not add a pause-menu toggle (likely due to another " +
                "installed mod/client customizing that dialog) - the HUD toggle is unaffected.", t);
        }
    }
}
