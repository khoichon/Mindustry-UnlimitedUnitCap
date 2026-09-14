# Unlimited Unit Cap

A small, player-facing quality-of-life mod for [Mindustry](https://mindustrygame.github.io/).

It adds a toggle — visible in the top-left corner of the screen whenever you're in a
level, on both desktop and mobile — that turns Mindustry's built-in unit cap on or off
for the map you're currently playing. Your choice is remembered **per map** and restored
automatically the next time you load that map.

This is not a map-editor feature and does not require the map's creator to configure
anything. It's purely a client-side convenience toggle for whoever is playing.

## How it works

The mod does not implement its own unit-cap system. It only flips Mindustry's existing
rule:

```java
Vars.state.rules.disableUnitCap = true; // or false
```

which is the same flag Mindustry's own map-rules editor exposes as "Disable unit cap".

```
detect map
    ↓
load per-map preference (Core.settings)
    ↓
set Rules.disableUnitCap
    ↓
show a toggle in the HUD
    ↓
player taps it
    ↓
update Rules.disableUnitCap immediately
    ↓
save preference under that map's key
```

### Where the toggle lives

The toggle is a small button in the top-left corner of the screen, visible whenever
you're actually in a level. It reads:

```
Unlimited Units: OFF
```

or

```
Unlimited Units: ON
```

and updates immediately when tapped/clicked.

This mod originally put the toggle inside Mindustry's pause menu instead, and that code
path is still attempted as a bonus (see `addPauseMenuToggle()`). It isn't relied on,
though: several popular community client mods (e.g. "Mindustry Client") heavily rebuild
or reskin that exact dialog, and there's no reliable way for one mod to guard against
another mod doing that to a shared dialog. The HUD toggle is a widget this mod owns
outright, added to Mindustry's normal always-present HUD layer, so it shows up
regardless of what other installed mods do to the menus.

### Per-map identification

Preferences are stored as `unlimited-unit-cap.<mapId>` via `Core.settings`
(Mindustry's normal cross-platform settings/preferences API).

`<mapId>` is built from the map's backing file path (`Map#file`), not its display name.
This is deliberate: two different maps can share the exact same display name (a common
map name reused by different authors, a redownloaded/re-exported copy, etc.), but they
live at different file locations, so their preferences never collide. It also means:

- Restarting a level, or leaving and returning to it, resolves to the same file and
  therefore the same saved preference.
- Built-in campaign maps, custom maps, and downloaded/workshop maps are all covered,
  since they all load from *some* file path.

If a `Map` somehow has no backing file at all (not expected in normal play), the mod
falls back to a composite key of the map's name, author, and dimensions, and logs a
warning that persistence may not be reliable for it. If the map can't be identified at
all, the toggle still works for the current session — it just won't be remembered next
time, and the game is never crashed over it.

### Multiplayer

`Rules` are part of the shared game state that whichever machine is simulating the world
(the host) uses. This mod intentionally just flips that one real rule rather than
inventing a separate per-player bypass. In a networked game, the value that actually
governs spawning/building limits is the one on the host; a non-host client toggling this
updates their own local rules object (and its UI), but only the host's copy is
authoritative for gameplay. If you want the cap disabled for everyone in a hosted match,
toggle it as the host.

### Defaults & edge cases

- A map that has never been configured defaults to **OFF** (vanilla unit cap behavior).
- No map loaded / not currently in a game: the HUD toggle hides itself automatically
  (it only shows while `state.isGame()` is true).
- Any failure to identify the current map is caught and logged; it never crashes the
  game or blocks level loading.

## Project structure

```
UnlimitedUnitCap/
├── mod.hjson                                   # Mod metadata
├── build.gradle                                # Gradle build config (desktop + Android)
├── settings.gradle
├── src/
│   └── unlimitedunitcap/
│       └── UnlimitedUnitCapMod.java            # All mod logic (single class)
├── .github/
│   └── workflows/
│       └── build.yml                           # CI: builds the mod and uploads it as an artifact
└── README.md
```

## Building

Requirements: JDK 17+ installed. As of Mindustry v159, the game itself targets Java 17
bytecode, and this mod's `build.gradle` matches that.

There are two build targets:

- **`./gradlew jar`** — fast, desktop-only. Produces `build/libs/UnlimitedUnitCapDesktop.jar`.
  Good for quickly testing changes on your computer, but **this jar will not load on
  Android** — regular JVM class files aren't something Android's ART runtime can run
  directly, no matter how the code itself is written.
- **`./gradlew deploy`** — produces the real, cross-platform `build/libs/UnlimitedUnitCap.jar`
  that works on *both* desktop and Android. This is the one to actually install and
  distribute. It additionally dexes the compiled classes with the Android SDK's `d8` tool
  and bundles that alongside the normal classes in the same jar, which requires a local
  Android SDK — see "Building for Android" below for one-time setup.

From the project root, for a **desktop-only** test build:

```bash
# If you don't already have a local Gradle installation, generate the wrapper once:
gradle wrapper --gradle-version 8.5

./gradlew jar
```

(On Windows, use `gradlew.bat` instead of `./gradlew`.)

### Building for Android

This needs a local Android SDK (just the command-line tools — Android Studio itself
isn't required), one-time setup:

1. Download the **command line tools only** package from
   [developer.android.com](https://developer.android.com/studio#command-line-tools-only)
   and unzip it somewhere, e.g. `~/android-sdk`.
2. Set the `ANDROID_HOME` environment variable to that path (`ANDROID_SDK_ROOT` also works).
3. Use its bundled `sdkmanager` to install a platform and build-tools, e.g.:
   ```bash
   sdkmanager "platforms;android-30" "build-tools;30.0.3"
   ```
   (Any reasonably recent platform/build-tools version works — this mod uses no
   Android-specific APIs, `android.jar` is only needed so `d8` can resolve references
   while dexing.)
4. Add that build-tools directory (e.g. `$ANDROID_HOME/build-tools/30.0.3`) to your
   `PATH`, so the `d8` (or `d8.bat` on Windows) command is runnable directly.

Then, from the project root:

```bash
./gradlew deploy
```

The finished, cross-platform mod will be at:

```
build/libs/UnlimitedUnitCap.jar
```

This is the file to actually install — on both desktop and Android.

### Building via GitHub Actions (no local Android SDK needed)

If you don't want to set up an Android SDK locally, push this project to a GitHub
repository — `.github/workflows/build.yml` builds it for you on every push (and can also
be triggered manually via the **Actions** tab → **Build mod** → **Run workflow**).

It runs `gradle deploy` on a GitHub-hosted runner (which already has an Android SDK
available), then uploads the result as a workflow artifact:

1. Go to your repository's **Actions** tab.
2. Open the latest run of **Build mod**.
3. Scroll to **Artifacts** at the bottom of the run summary.
4. Download **UnlimitedUnitCap** — that's a zip containing `UnlimitedUnitCap.jar`, the
   same cross-platform jar `./gradlew deploy` produces locally. (There's also a
   **UnlimitedUnitCap-desktop-only** artifact, if you just want a quick desktop-only
   build without thinking about Android at all.)
5. Unzip it and install `UnlimitedUnitCap.jar` in Mindustry as described below.

This doesn't require you to have Java, Gradle, or the Android SDK installed on your own
machine at all — GitHub's runners already have everything the workflow needs.

### Troubleshooting: "Could not find com.github.Anuken.Arc:arc-core:\<hash\>"

This is a known JitPack quirk, not a problem with this mod's code. Mindustry's published
`core` artifact for some versions references its Arc dependency by an internal build commit
hash instead of a release tag, and JitPack hasn't built that exact hash. `build.gradle`
already works around this by forcing all `com.github.Anuken.Arc` submodules to resolve
against the same release tag as Mindustry itself (see the `resolutionStrategy` block). If
you still hit this after pulling the latest `build.gradle`:

- Make sure `mindustryVersion` in `build.gradle` is set to an actual Mindustry release tag
  (e.g. `v146`), not a commit hash.
- Try bumping `mindustryVersion` to the latest stable release tag — very recent or very
  obscure tags are less likely to have a cached JitPack build.
- Run with `--refresh-dependencies` once to bypass a stale/broken cached resolution:
  `./gradlew jar --refresh-dependencies`.

### Troubleshooting: "cannot find symbol: disableUnitCap"

This means `mindustryVersion` in `build.gradle` points at a Mindustry release that
predates `Rules#disableUnitCap` (this happened with `v146`, from 2023 — too old). The
fix is to bump `mindustryVersion` to a current stable tag; `v159.5` is confirmed to have
the field. Check [Mindustry's releases page](https://github.com/Anuken/Mindustry/releases)
for whatever the current stable tag is if `v159.5` is no longer the latest by the time
you build this, and use that instead.

### Troubleshooting: "No matching variant of com.github.Anuken.Mindustry:core:... was found" / "compatible with Java 17"

This means `sourceCompatibility`/`targetCompatibility` in `build.gradle` are set lower
than 17. Starting with Mindustry v159, the game's own published artifacts require a
Java 17+ compile target, and Gradle enforces that via module metadata rather than a
plain compiler error. The bundled `build.gradle` already sets both to `JavaVersion.VERSION_17`;
if you've lowered them (e.g. back to 8, for an older Mindustry target), raise them back
to 17, or to whatever level matches the Mindustry version you're actually compiling
against.

### Troubleshooting: the toggle doesn't appear anywhere

If the top-left HUD toggle is missing, it's almost always another mod interfering with
the HUD or client. Check Mindustry's log (Settings → Misc → Open log file, or the
console output if you launched from a terminal) for a line starting with
`[UnlimitedUnitCap]` — an error there means `addHudToggle()` threw, most likely because
a future Mindustry update changed `Vars.ui.hudGroup`. If you don't see any such line but
still don't see the button, try disabling other UI-altering mods one at a time to find
the conflict, since a mod that rebuilds the HUD layer itself could still remove widgets
other mods added to it (this is a much less common pattern for the HUD than for dialogs,
which is why the HUD was chosen over the pause menu, but it's not impossible). The
pause-menu bonus toggle (see `addPauseMenuToggle()`) not appearing is expected and
harmless if you have a mod that customizes that dialog (e.g. Mindustry Client) — that's
exactly the scenario the HUD toggle exists to be immune to.

### Troubleshooting: "No valid Android SDK found" / "No android.jar found"

`./gradlew deploy` needs `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) set to a real Android SDK
directory that has at least one platform installed. See "Building for Android" above —
`sdkmanager "platforms;android-30"` is the missing step if you get the second error
specifically.

### Troubleshooting: "d8 failed" / "'d8' is not recognized" / "command not found: d8"

`d8` (from the Android SDK's build-tools) needs to be runnable directly from your PATH.
Find the build-tools directory you installed (e.g. `$ANDROID_HOME/build-tools/30.0.3`)
and add it to your PATH, then open a new terminal so the change takes effect before
running `./gradlew deploy` again.

### Troubleshooting: installed the jar on Android, but it doesn't load / mod is missing

This almost always means `./gradlew jar` was used instead of `./gradlew deploy`. The
plain `jar` output only contains ordinary JVM class files, which Android cannot load at
all — there's no code fix for this, it's an inherent difference in how desktop and
Android load compiled Java code. Rebuild with `./gradlew deploy` and install that jar
instead.

## Installing

1. Build the cross-platform jar with `./gradlew deploy` (see "Building for Android"
   above) — the plain `./gradlew jar` output is desktop-only.
2. Open Mindustry (desktop or Android).
3. Go to **Mods**.
4. Click **Import mod**, and select the built `UnlimitedUnitCap.jar`
   (from `build/libs/`).
5. Restart Mindustry if prompted.
6. Load into any level and look at the top-left corner of the screen. You'll see:

   ```
   Unlimited Units: OFF
   ```

   Tap/click it to toggle. The label updates immediately, the change applies
   immediately (no reload needed), and it's saved for that specific map.

Works identically with mouse/keyboard on desktop and with touch on Android — the button
is added to Mindustry's own HUD layer, so it uses the game's normal UI and input handling
rather than a custom overlay.
