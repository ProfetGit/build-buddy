# Changelog

## 0.0.81

- Smart Pick is now on **U** by default (it was K, which Iris uses to toggle shaders, so one press did both). If you already changed the key in Controls, yours stays.

## 0.0.80

- Chat commands for scripted recordings: `/cyanotype samples` (four sample builds into the Library), `/cyanotype library` (opens it), `/cyanotype paste` (pastes the active build).

## 0.0.79

- `/cyanotype auto assist|sweep|off` turns auto-placing on or off from chat, the same as the N key and the Build panel.

## 0.0.78

- An op paste on a server no longer fills your chat with "Changed the block at ..." lines: the answers to the paste's own commands are hidden while it runs and for a moment after. Other chat is untouched.

## 0.0.77

- Paste on a server, for operators: in creative, an op can paste a placed build on a multiplayer server (P or the Build panel). The build goes in as /fill and /setblock commands (runs of the same block are joined into boxes, signs keep their text), paced so the server keeps up, bottom up, supports first. Ctrl+Z puts back what was there, except items inside chests (the client cannot see them; the message says so). Players who are not op get "Pasting on a server needs operator permission."

## 0.0.76

- A ghost that is partly under water now shows its underwater part from the air, through the water like real blocks, with and without shader packs. Before, it only appeared once you were in the water.
- The box around a placement (corner brackets, edit outline, layer frame) shows at every camera angle with shader packs. Corners behind the build are drawn fainter.

## 0.0.75

- No more flicker while auto-placing with one layer shown: the top and bottom faces of the shown layer used to vanish and come back after every placed block. They now stay until their new picture is ready.
- Shorter key names in Controls ("Auto-place on / off", "Layer up", "Smart pick", "How it works"...).

## 0.0.74

- Slabs auto-place properly: double (full block) slabs go down in two steps, top slabs come out on top, and on servers a block can be built on a slab or stair (the click now lands on the block's real shape).
- With auto-placing on, a right click never uses anything: barrels, chests, doors, item frames and villagers are left alone, so a stray click no longer stops auto-placing.
- Signs placed by auto-placing get the blueprint's text and no editor pops up. Signs you place yourself still open the editor.
- Building one layer works: with the layer window on, auto-placing only looks at the shown layer, so the middle of a floor gets placed too (Assist used to aim at the hidden layers above it).
- New keys (rebindable): N switches auto-placing on and off (back in the mode you used last), Page Up / Page Down move the layer window one layer (the first press starts at the lowest unfinished layer; Page Up past the top shows all layers). The hints and the Build panel mention them.

## 0.0.73

- A finished build's ghost now goes away by itself, two seconds after the DONE moment. Ctrl+Z brings it back. A ghost you lock over a build that is already complete stays.
- "Place what I look at" only ever places ghost blocks now. With it on, a use press puts the ghost block under your crosshair (always the ghost's own block, whatever you hold) and nothing anywhere else, in every tool and with either hand. Hold use and sweep the crosshair over the ghost to place it all. Before, a block in hand could still be placed anywhere while you were editing, with the off hand, or with no ghost put down.
- New key K (rebindable): Smart pick, to save a build quickly. Press it again to cancel. The Save panel shows the key.

## 0.0.72

- In a world you host (singleplayer or your LAN world), auto-placing now places a ghost block whenever it is in reach: blocks with nothing to lean on go straight into the air, stairs, doors and other blocks with a facing come out right without your view turning, and there is no line-of-sight rule. In creative it takes the blocks it needs from the creative inventory, so you do not have to carry them. On other servers the rules are unchanged: only what a player could place from where they stand and the way they look.
- Assist no longer draws a box around the aimed block.
- A block auto-placing has just put down is never placed again while the game is still confirming it.

## 0.0.71

- Auto-placing no longer puts a wrong block down right after taking an item out of your bag: it waits until the game has the new item in your hand. When the hotbar is full it always uses the same slot, so the rest of your hotbar stays as it was.
- In Assist mode a box now glides to the ghost block a press would place, and every block auto-placing puts down gets a small pop.
- The AUTO badge grows to fit its words instead of cutting them off, shows the block it is working on, and no longer shows a speed in Assist (Assist always places at once). When auto-placing stops by itself, the badge stays for a moment and says why.
- Opening the chat only pauses auto-placing; other menus still stop it.

## 0.0.70

- The Save box highlight now shows with shader packs. Under Iris the lit blocks were drawn behind the world's depth and never appeared; they are now drawn on top, and only the faces that look toward you are drawn, so nothing lights through a wall.
- Ctrl+Z and Ctrl+Y now undo and redo changes to the Save box (a drag of a side, or a run of scroll notches), while you are adjusting it. The hints list them.

## 0.0.69

- Moving a side of the Save box now lights only what the move changes: blocks the box takes in are cyan, blocks it lets go of are red. The rest of your build is no longer tinted while you drag, and it is much cheaper on big boxes. Works for dragging an arrow and for scrolling.

## 0.0.68

- The block highlight while you move a side of the Save box is rebuilt. Full blocks are lit as a skin (only the faces you can see, joined into big rectangles), blocks like slabs, stairs and fences by their own shape. Before, a big box lit only every n-th block, so the highlight looked broken and patchy; now nothing is skipped, big boxes (up to 400,000 cells) look like small ones, and it needs far fewer shapes to draw.

## 0.0.67

- The trash button in the Placed list removes a placement at once, with no question. Undo (Ctrl+Z or the Undo button) brings it back.
- Undoing a paste now drains water that had flowed out of the pasted build. Before, the pasted water sources went away but the water that had run out of them stayed behind for good.

## 0.0.66

- Calmer key hints in Edit mode: three rows to begin with (drag, turn, done) and a faint "More help" row. After about six quiet seconds the rest of the keys come in as fainter rows, and they tuck away again when you grab, drag or scroll.

## 0.0.65

- The turn ring has its original look back (flat band, crisp edges, the four clockwise chevrons). It still pops in and drifts.

## 0.0.64

- The pointed arrow shape is back (square shaft, pyramid head), now chunkier and better looking: every face is shaded softly as if lit from above, in pastel colour, with a thin light edge that keeps it clear over any background. The soft turn ring stays.

## 0.0.63

- Round, chunky arrows: a ball at the base, a fat shaft, a blunt head with a rounded tip, shaded softly as if lit from above, with no hard outlines. The turn ring is a softer, wider band with four rounded arrowheads. Distant arrows use fewer sides.

## 0.0.62

- Friendlier arrows and turn ring: soft pastel colours (pink, mint, sky), and they move. When you start editing they pop out one after another with a little overshoot, they breathe gently along their axis, hover swells them with a jelly spring, grabbing squeezes them, and the ring's arrows drift slowly round. Reduce motion turns it all off.

## 0.0.61

- First-run hello: the first time you stand in a world, a small card says "Hold V for the tool wheel, press J for lessons". It leaves by itself, or as soon as you press either key.
- An empty Library now has an "Add a sample house" button, so there is something to try straight away.
- The Community tab and its settings page stay hidden until the community website has a public address (they led nowhere).
- "Nothing to edit yet" tells you to open the Library instead of naming chat commands.
- The tool key is named "Tool wheel (hold) / start or stop editing (tap)" in Controls. The Materials "All layers" tab has room for its text.

## 0.0.60

- The lessons' cottage is back to oak walls and logs, with more detail: a mossy cobblestone base, glass panes in the windows, a wall-block chimney top. The Materials lesson lists the new blocks.

## 0.0.59

- The lessons' sample cottage looks better: light birch walls, dark oak corner posts and door, brick roof, stone base.

## 0.0.58

- The sample cottage in the lessons has its roof stairs the right way round (they sloped the wrong way).

## 0.0.57

- Redrawn icons: the check mark (even arms, one glint), the help question mark (bold, on a white disc) and the restart arrow (a clean clockwise arrow). They are drawn like the rest of the icons: flat colours and hand-placed pixels.

## 0.0.56

- Lessons polished. The little player is now the game's own player model in the default skin, with a walking swing and a soft shadow (it was a faceless block figure); the next-block marker is a gold cube with a bobbing arrow; footprints are boot prints; the turn ring has crisp edges.
- Every lesson now keeps its ground slab whole inside the picture (it was cut off at the bottom in all nine), and the paper grid fades out with a soft shadow under the slab. The materials lesson keeps its stage clear of the list.
- The mock panels look like the real ones: the AUTO: ON badge shows Assist and then Sweep with the real wording, the DONE stamp lands turned with sparks as in the game, a Saved badge with a check, and a cottage glyph for blueprints in the Library and Save screens.
- The sample cottage has a red brick roof so it reads clearly against the walls.
- Lessons make quiet interface sounds: clicks, the scroll ticks, the lock, the done chime. They follow the Sounds setting and only play while a lesson is playing.
- The place lesson shows the Library for longer before the ghost appears.

## 0.0.55

- New: **How it works.** Every tool has a short looping lesson in a small 3D picture with the cursor, captions and the same key hints as in the game. The first time you use a tool its lesson plays before the tool starts (Skip starts the tool anyway; "Don't show lessons again" and the setting turn it off).
- Nine lessons: place, move and turn, layers, build and check, auto-placing (with the server warning), materials, pick a build, select a box, paste.
- Open one again whenever you like: right click a tool on the wheel, the **?** on the Build and Save choices, the **?** in the Library, or the **J** key (the lesson of the tool you are in, or the list). The middle of the wheel and the J key open the list of lessons, with a search.
- Lessons use your resource pack's textures, follow Reduce motion (they open paused on a still, with step buttons), and can be played, paused, stepped, scrubbed and slowed.
- Settings: "Lesson on first use", "Open the lessons", "Show all again".

## 0.0.31

- Added the mod's icon and its description art. No change to how the mod works.
