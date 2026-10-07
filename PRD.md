# Cyanotype: Product Requirements

Name chosen 2026-10-04 (mod id `cyanotype`, package `io.github.profetgit.cyanotype`, slug `cyanotype` was free on Modrinth). Status: draft v0.2. Author: Profet.

## 1. Summary

Cyanotype is a client-side Fabric mod for Minecraft Java (26.3) that does what Litematica does (load a schematic, show it as a ghost in the world, build it, verify it, save your own) but is learnable in one minute. There are no keybind lists and no config walls. One held-style tool, a pop-up tool wheel, handles dragged in the world, and a material panel that tells you what to do next. Auto-placing is included, off by default on servers, behind a clear disclaimer.

One line: **Litematica's power, Create's feel.**

Headline promise: **you never need a YouTube tutorial.** Everything the mod can do teaches itself inside the game.

## 2. Problem

- Litematica is the standard tool, but it has dozens of hotkeys, several nested config screens and unclear modes. New users give up or never find half the features.
- Builders spend most of their time counting materials, finding their place in a layer, and spotting mistakes. Litematica has the data but presents it as lists and colour overlays you must interpret.
- Nothing free combines the feature set with a pleasant, tactile interface.

## 2a. Competition (checked on Modrinth, 2026-10-04)

- **BuildPrint** (~4k downloads, 26.2/26.3): client-only Litematica alternative; opens `.litematic`, `.schem`, `.schematic`, `.nbt`, Axiom `.bp`; easy place and printer.
- **Archetect** (~500 downloads): Schematica-style ghost with layers and a printer; own format that saves entities.
- Litematica itself, plus Axiom (paid).

Both newcomers already have auto-placing and wide format support, so neither is our edge. The edge is **guidance and feel**: tool wheel, next-block hint, material panel, tactile UI. Consequence: reading `.schem` and `.nbt` should be in v1, not later, to avoid losing users at the door.

## 3. Goals

1. A first-time user loads a schematic and places it correctly in under 60 seconds with no documentation.
2. Full core Litematica workflow: load, place, move, rotate, mirror, verify, material list, layer view, save selection.
3. Every interaction has feedback: sound, small animation, clear state. The UI should be satisfying to use, not just functional.
4. Reads and writes `.litematic`, so existing schematic libraries work.
5. Stays smooth (no frame drop at 60 fps on a mid-range machine) with schematics up to 500k blocks.
6. Auto-placing exists and is honest about its risks.
7. **Zero outside help:** a new user can use every feature without a video, wiki or README. Verified by the no-tutorial test in section 12.

## 4. Non-goals (v1)

- Not a world editor (no WorldEdit-style fill, copy-paste into the world, brush tools).
- No server-side component. It works as a client-only mod on any server.
- No multi-version support beyond Fabric 26.3 at first (26.2 and backports are a later question).
- No marketplace or uploading inside the mod. Decided 2026-10-05 (the author's call, replacing "no online sharing"): the Library has a read-only Community tab that browses and downloads builds from the separate community website (`~/Projects/cyanotype-community`, API v1, see 7.1b). Sharing a build (accounts, upload, moderation) stays on the website; the mod never sends the player's files or any account data.
- No features that hide the player from server rules: no spoofing of the client brand, no attempts to defeat anti-cheat, no placing through walls or beyond normal reach.

## 5. Users

| Who | Need |
|---|---|
| Survival builder | Build big structures by hand without losing track; know what to gather. |
| Creative/redstone builder | Place and align precisely; verify against the original. |
| Technical player | Import community schematics (farms, mega builds) and build them in survival. |
| New player | Use any of the above without learning a tool. |

Primary: survival builder and the new player. Technical power users get depth through the same UI, not through a separate expert mode.

## 6. Principles

- **No tutorial needed (the top principle).** If a feature can't be understood from inside the game, it is not finished. Every tool explains itself (cursor chips, `?` Ponder, empty states, error messages that say what to do). A feature that needs outside documentation is a bug.

- **One tool, no modes you can't see.** The current mode is always shown next to the cursor.
- **Direct manipulation.** Drag the thing in the world, don't type numbers (numbers available on hover for precision).
- **Tell the player what's next.** The build always knows the next useful action (next missing block, what to fetch).
- **Tactile.** Spring animations, click sounds, and snap feedback. Nothing pops in without easing.
- **Safe by default.** Nothing destructive, no auto-placing on servers until the player opts in.

## 6a. Visual language

Style **B "Cyanotype"** (chosen 2026-10-04): navy blueprint-paper panels with a faint grid, thin white and cyan lines, dashed slots, hatched progress bars, corner brackets. Assets in `dev/ui/out/B_final/` (45 sprites, atlas with 9-slice data). Text uses the vanilla font. The in-world ghost uses the same palette: cyan tint for "to place", red for wrong, nothing for done.

## 6b. Motion and feedback

Premium feel comes from timing, easing and sound, not from baked frames. Rule: **state changes are animated in code between the static sprites, and always interruptible.** A hover that is left halfway reverses from where it is, never snaps or finishes first.

| Interaction | Behaviour |
|---|---|
| Hover in/out | Ease over ~100 ms; colour fades between the normal and hover sprites; corner tick marks sweep in. |
| Press | ~60 ms down: 1 px shift and the pressed sprite. |
| Release | ~140 ms spring with a 1 px overshoot, then settle. |
| Selection | Marching dashes on the selected slot or card (looping `.mcmeta` texture). |
| Progress bar | Hatch scrolls slowly inside the fill (looping texture); fill value eases to the new value. |
| Panel open | Blueprint draw-on: the border grows from the corners like a pen, then the grid fades in (~250 ms), done in code on the existing 9-slice sprites. Closing reverses it faster (~150 ms). |
| Step complete | Small sparkle plus a stamp-style "done" mark. |
| Click | Corner-bracket ripple that flashes outward from the click point. |
| Tool wheel | Wedge slides to the hovered segment with a spring; the chosen segment pulses once on release. |

- **Pixel crisp:** all movement snaps to whole pixels of the current GUI scale; no sub-pixel smearing, no non-integer scaling of pixel art.
- **Sound:** every hover, press, release and completion has a soft tick or click (own sounds, vanilla-like volume, never louder than vanilla UI). A sound plays on the state change, not on the animation end.
- **Textures only where they loop or are one-offs:** looping effects use animated GUI textures (`.mcmeta`); one-off sprites (ripple, sparkle, stamp) are drawn in Aseprite in `dev/ui/` and packed into the atlas.
- **Budget:** animations must cost under 0.2 ms per frame in total and never allocate per frame.
- **Setting "Reduce motion":** removes draw-on, springs and sparkles; hover and press become instant colour changes. Sound stays.

## 7. Feature specification

### 7.1a Formats (decided 2026-10-04)

| Format | Read | Write | Release |
|---|---|---|---|
| `.litematic` | yes | yes (default save format) | v1 |
| `.schem` (Sponge v2/v3) | yes | export | v1 |
| `.nbt` (vanilla structure) | yes | export | v1 |
| Axiom `.bp` | yes | no | **v1.1** |
| Legacy `.schematic` (MCEdit, numeric IDs) | maybe | no | v1.1 or never |

- **No custom format.** Schematics are shared as `.litematic`/`.schem`; a new format would be unopenable elsewhere. Our extras (tags, thumbnail, notes) live in a sidecar `name.cyanotype.json` next to the file, so nothing breaks if only the schematic is shared. Whether Litematica tolerates extra keys in its own metadata is untested, so no extra keys go inside the file.
- **Old schematics:** each file carries a data version; load runs it through the game's data fixer so renamed blocks update instead of failing.
- **Modded/unknown blocks:** load as marked placeholders, listed in the material panel as "unknown block". A schematic never fails to open because of them.
- **Entities:** not in v1; all three main formats can carry them, so no format change is needed later.

### 7.1 Blueprint library
- Reads `.litematic` files from `config/cyanotype/blueprints/` (and, optionally, the Litematica folder if present).
- Opens as a grid of cards: name, author, size (X×Y×Z), block count, rotating mini 3D preview, tags (user-defined).
- Search and sort (recent, name, size).
- Importing: drag a file onto the game window copies it into the folder.
- Formats: see 7.1a.

### 7.1b Community tab (decided 2026-10-05)
- A second tab in the Library, "Community": builds that other players shared on the community website, read through its public read-only API v1 (no account, no key). Search, category chips, sort (newest, most downloaded), pages, cards with the server-drawn preview, title, author, size label and downloads.
- A card opens a detail view: preview, facts, description, materials, and a Download button. Download saves the file into `config/cyanotype/blueprints` after checking size, checksum and that the reader can open it; it never replaces a file (same name rule as Save area: "name 2"); the file then appears in the normal Library, tagged `community`, with a tags file that remembers the site and build id. Only `.litematic` can be downloaded until the mod reads `.schem`/`.nbt`; other builds say so and link to their page.
- First use asks once before anything is sent ("This tab contacts <site>: your searches and your internet address are visible to it"). Settings > Community switches it off or on; off sends nothing. The address is a setting (`communityUrl`; default is a constant that stays empty until the site has a public host, https only except for localhost).
- Network work never runs on the render thread; the tab has loading, empty, offline and error states; requests stay under the site's limits (120 API calls and 30 downloads a minute), answers are cached for the site's max-age.

### 7.2 Placement
- Selecting a blueprint attaches a ghost to the crosshair; the ghost's origin snaps to the block grid.
- Scroll rotates in 90° steps; shift+scroll moves vertically; a toggle mirrors on X or Z.
- Click locks the placement (spring settle + sound). A locked placement shows in-world handles: arrows for each axis, a rotate ring, a mirror toggle. Dragging a handle moves/rotates with live snapping and a distance readout.
- Multiple placements can exist at once; each has a name, visibility toggle and colour accent. One is "active".
- Placements persist per world and server (saved in `config/cyanotype/placements/`).

### 7.3 Ghost rendering
- Draws the real block model translucent, with a cyan tint for missing blocks.
- States per block: missing (ghost), wrong (red outline + ghost of the correct block), correct (hidden).
- Layer focus: a slider (and scroll in layer mode) limits the ghost to one Y level, or a range. The active layer's border is drawn in-world.
- Distance fade and an adjustable opacity; optional "x-ray" for hidden interior blocks.

### 7.4 Verifier
- Continuously compares the world against the active placement within render range.
- Reports: correct %, missing count, wrong count, and extra blocks (optional).
- Chunk-incremental: re-checks only changed chunks (block update hooks), so cost is proportional to edits, not schematic size.
- Progress bar and a "jump to next error" action.

### 7.5 Material panel
- Lists required blocks (per block type): needed, have (inventory), nearby (chests/barrels/shulkers within a configurable radius that the client has loaded), missing.
- Stack formatting ("3 stacks + 12"), sorted by shortage; click a row to highlight where it goes in the world.
- "Shopping list" export (copy to clipboard / text file).
- Per-layer filter ("what do I need for this layer").
- Dye/wood variant grouping toggle.

### 7.6 Build assist
- **Next block hint:** an animated marker on the next sensible block (bottom-up, nearest first, supported before dependent). Works without any auto-placing.
- **Easy place:** while holding a block, the ghost under the crosshair snaps the placement face and rotation/state (stairs, slabs, logs, observers, etc.) to what the blueprint wants.
- **Auto-placing:** see 7.8.

### 7.7 Save your own
- Selection tool: click two corners (or drag a box with handles); the box shows its size.
- Name, optional author and tags; saved as `.litematic`.
- Option to include/exclude air, entities (v1: no entities), and block entities (chests contents excluded by default).

### 7.8 Auto-placing and server disclaimer

Auto-placing places blocks for the player from their inventory.

**Behaviour (when enabled)**
- Player-equivalent: it performs normal block-place interactions, only at blocks within normal reach, from items actually in the inventory, never faster than a configurable rate (default 4 blocks/second, up to 20). It does not place through walls or outside reach.
- It builds in a safe order (bottom to top, supported blocks first) and stops when materials run out, the player moves out of reach, takes damage, opens a screen, or presses Esc.
- Modes: **Assist** (places the block you aim at when you press and hold the use key), **Sweep** (builds everything within reach while you walk), and **Off**.
- Visible activity: placed blocks pop in with the normal place animation and sound.

**Disclaimer and defaults**
- In singleplayer and in LAN worlds you host: enabled, no prompt.
- On any multiplayer server: **off by default**. The first time you try to enable it on a server, a modal shows:
  > Auto-placing may break this server's rules and can get you banned. Cyanotype can't know what this server allows. Check the rules or ask the staff first. You're responsible for how you use it.
  with buttons "Keep it off" (default focus), "Enable on this server" (requires a 3 second wait before it activates), and a "Don't ask again for this server" checkbox.
- The choice is stored per server address. A small badge ("AUTO: ON") stays visible in the HUD whenever it is enabled on a server.
- A config list of server addresses where auto-placing is forcibly disabled ("blocked servers"), plus an in-game way to add the current server.
- The mod makes no attempt to hide itself: it doesn't alter the client brand, and rate and reach limits are not removable beyond the documented maximum.

### 7.9 Tool wheel and interaction model
- Hold a single key (default `B`, rebindable) to open the wheel: Move, Rotate, Mirror, Layers, Select/Save, Materials, Library, Settings. Release on a segment to choose it.
- The active tool's actions appear as small labelled chips next to the cursor ("Click: lock", "Scroll: rotate"). They disappear for experienced users via a setting.
- The only keys the mod binds by default: the wheel key, a quick toggle "show/hide ghost" and "undo last placement move". Everything else is mouse.
- Full list of rebindable keys exists in vanilla's controls screen for those who want them.

### 7.11 Smart Pick: click a build, save it
- Aim at any part of a build and click. The whole build is highlighted; a second click opens Save.
- Classification (client-only, loaded chunks, on a worker thread over a chunk snapshot):
  - Clearly built: any block that is not in the natural set (planks, bricks, glass, wool, concrete, stairs, slabs, doors, lanterns, ...), leaves with `persistent=true`, paths.
  - Natural set from vanilla block tags (base stone, dirt, sand, gravel, ores, non-persistent leaves, logs touching natural leaves, water, lava).
  - Ambiguous blocks (stone, dirt, cobblestone...) join only when enclosed by or touching built blocks.
  - Gaps of 1-2 blocks are bridged (windows, floating lanterns, chains). Thin ground-level links (paths) are cut so a village does not merge into one build.
  - The selection stops at the natural ground line; "include ground under it" is a toggle.
- Refinement is the real feature: scroll grows/shrinks the tolerance, shift-click adds a part, ctrl-click removes a part (alt-click is taken by KDE's window manager, decided 2026-10-05), a confidence tint shows blocks the picker was unsure about.
- Output feeds 7.7 (Save your own) with the tight bounding box and interior air handled.
- Limits: loaded chunks only, configurable block cap, heuristic (will mis-pick sometimes; manual refinement is the fallback).

### 7.12 Tool Ponders and built-in help: in-game manual
- **Layers of help**, from lightest to deepest: (1) cursor chips showing the current click/scroll actions; (2) first-use Ponder for each tool; (3) empty states that say the next step ("No blueprints yet: drop a .litematic file onto the window"); (4) errors that say what to do ("Out of reach: move closer or place a scaffold block here"); (5) a searchable Help screen that lists every tool's Ponder; (6) a first-run guided tour of the wheel, using a bundled sample blueprint so there is something to try.
- Each tool has a short looping lesson (10-20 s, 3-5 captioned steps) in a small 3D viewport with a fake cursor, highlights and captions, in the Cyanotype style. Lessons are called **Ponders** in the milestone name only; the player sees "How it works".
- **When they show:** the first time a tool is used, the lesson plays before the tool starts (setting "Show a lesson the first time I use a tool", on by default, with "Show all lessons again"); a `?` reopens it: right click on a segment of the open wheel, a `?` on the Build and Save choices, the Library footer (opens the Help list), and the Help key (default J; in a tool it opens that tool's lesson, otherwise the list). Buttons: **Try it** starts the tool (disabled with the reason when it cannot be used yet) and **Skip**/**Close**; on first use Skip also starts the tool (they asked for it), on a replay Close does not. Esc = Skip/Close.
- **Lessons (first set, adapted 2026-10-07 to the six-tool wheel):** `place` (Library: ghost follows the crosshair, scroll turns, Shift+scroll lifts, M mirrors, click locks), `edit` (arrows, ring, carry, scroll push, Ctrl+Z), `layers`, `build` (red = wrong, nothing = done, next-block marker, progress bar), `auto` (Assist, Sweep, the server warning), `materials`, `pick` (Smart Pick), `box` (Select a box), `paste` (creative P). A lesson is reachable from the Help list whether or not its tool is available.

#### 7.12a Ponder design (decided 2026-10-07)
- **Renderer: software, off-thread, the Save preview's raster.** A lesson is drawn by `ponder/StageRaster` (orthographic, depth buffer, textured faces from `BlockLook`, so the player's resource pack shows) into a texture on a worker, with the raster's overlays (lines, arrows, rings, boxes, markers) drawn into the same buffer so they depth-test and anti-alias with 2x2 supersampling; text, chips, the cursor and mock panels are drawn by the GUI on top at full resolution. Why not the game's renderer: it would run through the player's shader pack, touch the world renderer, and could not be rendered headless for tests and review stills. Costs: no ambient occlusion; a worker pays about 4-8 ms a frame for a lesson stage (to be measured, budget in 10).
- **A lesson is a deterministic function of time.** `Scene.at(t)` returns an immutable `Snapshot` (camera, every group's cells and transform, overlays, cursor, caption, chips, panels); nothing is simulated, so scrubbing, stepping, looping, stills and the recorder round trip all use the same call, and a lint can compare the end state with the start state (the loop seam).
- **Schema (JSON, `assets/cyanotype/ponders/<id>.json`, `format: 1`):** header (id, title, summary, tags, `action` for Try it, duration), `stage` (size, focus), `palette` (short key -> block state string), `groups` (a grid of palette keys by layer and row, a mode `solid` or `ghost`, `start` full or empty, optional `match` = another group: ghost cells are hidden where that group has the same block, red and 1.2 % larger where it has a different one, as the mod's verifier does), and keyframed tracks with eases (`linear`, `in`, `out`, `inOut`, `spring`, `step`): `camera` (yaw, pitch, zoom, focus), `group.<id>` (pos, turn in quarter turns, mirror, alpha, visible, layer window), `ops` (reveal/clear cells at a time with `drop`/`pop`/`fade`/`none`), `cursor` (world point or screen point, down, shape), `captions` (the steps), `chips` (intervals of key/action rows, same words and pictures as the in-game chips through `ChipIcons`), `overlays` (box, arrow, ring, marker, tint, label, dim, avatar; each with its own lifetime and keyframes) and `panels` (mock Library, Save, list, warning, bar). The loader reports errors with a path ("groups[1].rows[2]: unknown key 'x'") and a broken lesson never breaks the others.
- **Authoring: Python generates the JSON** (`dev/ponder/pondlib.py` helpers, `scenes.py` the nine lessons, `make.py` writes and validates `assets/cyanotype/ponders/` and the index); a `.json` is also hand-editable. The generated files are checked in, the generator is the source.
- **Recorder (dev only, `-Dcyanotype.demo` or `/cyanotype ponder record <id> [x y z sx sy sz]` / `mark <name>` / `stop`):** samples the real client at 20 Hz into the same schema so a lesson shows what the mod really does (which way scroll turns, how a lift reads, which block the verifier calls wrong), not what an author remembers: the stage box's blocks at the start (a group) and its changes (ops), every placement as a ghost group with its transform keyframes (blueprint embedded), the block under the crosshair (cursor), mouse buttons, scroll notches and the mod's keys with modifiers (chip pulses), the verifier's progress, the selection box, and named marks. Camera, captions, overlays and panels are authored on top by `scenes.py` (anchored to marks so a re-recording keeps them aligned). A Director "take" scene performs the story through the real input seams; the output goes to `dev/ponder/rec/<id>.rec.json`.
- **Accessibility and motion:** with Reduce motion a lesson opens paused on step 1 as a still, steps with Next/Previous, and drops, pops, springs and camera moves become cuts; Play still works. Keyboard: Space play/pause, Left/Right step, R restart, Enter Try it, Esc leave.
- **Lints (JUnit over every shipped lesson):** the file parses, every block state parses, every chip key is a known picture (no raw text keys), times are inside the duration, the end state equals the start state, every action is known, every lesson is in the index, and captions fit in two lines at the narrowest layout.
- **Polish rules (0.0.56):** a lesson's stage is never cut by the picture (the camera keeps the ground slab and the groups inside it, or inside the lesson's `stage.frame`); the mock panels copy what the HUD really draws (badge, DONE stamp) rather than approximate it; the little player is the game's own player model and skin; a lesson may play quiet interface sounds (`sounds` cues: click, scroll tick, lock, chime) at the player's Sounds volume, only while it plays.
- Not a build guide: we do not try to name parts of arbitrary builds (a duck has no roof).

### 7.10 Settings
- One screen, six groups: Appearance, Ghost, Materials, Auto-place, Servers, Advanced. Each setting has a one-line description. Defaults work.

## 8. Technical approach

- **Platform:** Fabric Loader 0.19.5, Minecraft 26.3, Java 25, plain Loom, Fabric API is a declared dependency (its resource loader is what serves the mod's art, sounds and language; plain Fabric Loader serves no mod assets). Mod Menu optional for the settings entry. Client-only (`environment: client`).
- **Format:** `.litematic` is gzip NBT: `Metadata`, `Regions` each with `BlockStatePalette`, bit-packed `BlockStates` (long array, variable bits per palette size), `Size`, `Position`, optional `TileEntities`. Parser reads into a dense per-region `BlockState` array plus a combined palette; writer mirrors it. Unknown palette entries (missing mods) become barrier-coloured placeholders and are reported.
- **Core data model:** `Blueprint` (immutable, regions), `Placement` (blueprint ref, origin, rotation, mirror, visible, name), `PlacementWorld` (a lazily evaluated view that answers "what block should be at world position P"), `Verifier` (diff state per chunk section).
- **Rendering:**
  - Draw in the level renderer's entity pass, as Having a Blast's debris renderer does (works with Sodium and Iris/Complementary, shaders shade it).
  - Not one draw call per block: bake each section of the placement into a translucent mesh (greedy-culled faces against neighbours that are also ghost or real blocks), rebuilt on edit. Frustum and distance culling per section. Budget: under 1 ms per frame on average on the render thread for a 500k-block placement in view.
  - Outlines and handles via line rendering; HUD/panels via the GUI renderer using the atlas in `dev/ui/out/B_final/` (9-slice for panels, buttons, tabs).
- **World observation:** hook block updates and chunk loads on the client level to mark sections dirty for the verifier; no per-tick full scan.
- **Inventory/material data:** client inventory plus nearby containers the client has already opened or whose contents are known; chests not opened are shown as "unknown", never guessed. (An optional "scan nearby containers" is only possible by opening them, and is a manual action.)
- **Auto-placing:** uses the vanilla client interaction path (`MultiPlayerGameMode.useItemOn`) with a rotation toward the target, so server-side checks see ordinary player behaviour. Rate-limited on the client tick; the rate and reach caps are enforced in code.
- **Persistence:** placements in JSON; blueprints are files; settings in `config/cyanotype.json`.
- **Compatibility:** coexists with Litematica's file format only (not its code). Sodium, Iris, EntityCulling, Mod Menu, Punchy, Fresh Animations are on the test list. Shader packs: ghost must stay readable.

## 9. Quality, testing

- Unit tests (headless): `.litematic` read/write round trip against sample files, palette bit-packing edge cases (palette of 1, 2, 17, 65 entries; arrays crossing long boundaries), rotation/mirror transforms of block states (stairs, doors, beds, banners), material counting.
- ModTest runner (`ModTest/mt.py`) scenes: load and place, rotate/mirror, verify correct and incorrect builds, layer focus, auto-placing in singleplayer, disclaimer flow on a dedicated test server, and a mixin audit.
- Performance lab: frame time with 10k, 100k and 500k-block placements, with and without Sodium/Iris. Recorded in a polish log.
- Real client capture for visual review of ghost, handles and panels, as with other mods.

## 10. Milestones

| # | Milestone | Exit criteria |
|---|---|---|
| M0 | Project scaffold | Gradle project builds a jar, loads in 26.3, tests harness wired into ModTest. |
| M1 | Load and ghost | `.litematic` parses (round-trip tested); a placement draws as a translucent ghost at a chosen position in the real client; frame time measured. **Go/no-go on rendering approach here.** |
| M2 | Placement tools | Rotate, mirror, move handles, lock, multiple placements, persistence. |
| M3 | Verifier and layers | Correct/wrong/missing states, incremental updates, layer focus, progress bar, "next error". |
| M4 | UI pass | Tool wheel, library, material panel, tooltips and chips using kit B, with the motion and sounds from 6b (reduce-motion setting included). |
| M5 | Save area | Selection box tool, write `.litematic`, reload test. |
| M5b | Smart Pick | Click-to-select picks a test set of builds (house, duck, village house, cave cabin) with correct bounds; refinement tools work; no frame hitch while it runs. |
| M5c | Community tab | Library tab against the community site's API v1: first-run notice and off switch (nothing sent before the answer), list with search/chips/sort/paging, detail with materials, Download into `config/cyanotype/blueprints` (checksum-checked, never overwrites), file shows up in Mine; loading/empty/offline states; JUnit for parsing, paging, file-name safety and no-overwrite; real-client scene against a local site. |
| M6 | Auto-placing | Assist and Sweep modes, limits, server disclaimer and per-server memory, blocked-server list, HUD badge. |
| M6b | Tool Ponders | The engine (schema v1, deterministic `Scene.at(t)`, ease/keyframe tracks, JUnit incl. every shipped lesson), the software viewport (textured, ghost, overlays; measured cost), the lesson screen (steps, scrub, keyboard, reduce motion, Try it) and the Help list with search, first-use flow + `?` entry points + settings, the dev recorder (real take -> JSON, round-trip tested, used for at least three lessons), nine lessons reviewed as stills, real-client scene `ponder` (opens each lesson, advances, steps, Try it arms the tool, first use once only) plus all older scenes still green. See 7.12a. |
| M7 | Polish and release | Sodium/Iris matrix, performance pass, docs, icon/banner, Modrinth page (with the standard AI disclosure), repo. |

M1 is deliberately the first risk burn-down: if translucent ghosting at 500k blocks can't hold the budget, the design changes (LOD, outline-only far away) before any UI work.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Ghost rendering cost or incompatibility with Sodium/Iris | High | M1 spike; section meshes; fall back to outlines at distance. |
| Server bans over auto-placing | Reputation | Off by default on servers, explicit disclaimer, rate/reach caps, blocked list, no stealth features. Say so in the description. |
| Block-state rotation/mirroring bugs (stairs, doors, redstone) | Medium | Comprehensive transform tests against vanilla's `rotate`/`mirror`. |
| Inventory/container knowledge is partial | Medium | Show "unknown" instead of guessing; manual scan action. |
| Name clash or too-close resemblance to Litematica | Low | Own name and own art; only the file format is shared. |
| Smart Pick mis-classifies lookalike natural/built blocks | Medium | Manual refinement, confidence tint, test set of hard cases. |
| 26.x internals change between snapshots | Medium | Keep rendering and interaction behind thin adapter classes. |

## 12. Success metrics

- First-use test: 5 people who have never used a schematic mod load and place a blueprint without help in under 60 s (target 5/5).
- Frame time with a 500k-block placement in view stays within budget on the user's machine and a mid-range reference.
- No crash or mixin error across the ModTest matrix.
- **No-tutorial test:** 5 people who have never seen the mod complete a fixed task list (load a blueprint, place and rotate it, find the next missing block, check the material list, save a build with Smart Pick) with no video, wiki or helper. Target 5/5 on every task; any task someone fails is fixed before release. Observers may not hint.
- Post-release: download growth and issue reports that are about features, not "how do I use it".

## 13. Open questions

1. Name is settled (Cyanotype). Still to confirm: slug and any trademark clash before the first upload.
2. Should Sweep mode exist in v1, or only Assist, to keep the server-rules surface smaller?
3. Settled: `.schem` and `.nbt` in v1, Axiom `.bp` in v1.1 (see 7.1a).
4. Entities (armor stands, item frames) in blueprints: v1.1?
5. Default wheel key: `B` is free in vanilla but not guaranteed free in the user's profile; check against installed mods.
6. Do we publish a Forge/NeoForge build later (Stonecutter) or stay Fabric-only like the prototypes?
