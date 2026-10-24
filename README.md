SORRY. ALL before this commit (ab06def) is fully vibecoded. I am tired from AI, very tired. And I have not motivation to do this mod more using AI to to ABSOLUTE EVERYTHING. After one small analysis, I understand now, what half of detailed-terrain code and logic is trash.  My java knowledge is very bad now. And I was scared before to do something big and hard just by my hands, so I used AI code agents.  But I am tired becaude AI can not understand us (people), that good for making really good LOD mod for minecraft. My hard solution to continue making this mod, but did not vibecoding (just use AI like a tool, not human replacer) . Yea, it can be slow, hard, but I will do it. You can see full source code at this page (all in that commit at 22 October 2026 and in commits before is fully vibe-coded and some parts of code are really trash, yea). i am open to PR's, issues, your feedback

# FarPlaneTwoContinuation

A Fabric mod for Minecraft **26.3** (Fabric Loader **0.19.5**, Fabric API) that draws simplified level-of-detail (LOD) terrain far
beyond the normal render distance, all the way to the edge of the world: **30 000 000 blocks**. It follows the idea of
[FarPlaneTwo](https://github.com/PorkStudios/FarPlaneTwo) by DaPorkchop_ (see [Credits](#credits)) and is written from scratch for the
26.x renderer.

Works on PC and on Android.

## Download

Latest release versions available on [Curseforge](https://www.curseforge.com/minecraft/mc-mods/far-plane-two-continuation).

## Installation

| Where you play | Where to install |
|---|---|
| Singleplayer (PC or phone) | client only |
| Server | both the server and every client |

Fabric API for 26.3 is required. If the server does not have the mod, the client simply does not draw LOD terrain and the game works
as usual.

## Dependencies

From version 1.2.114 Sodium is required. Optional - Mod menu

## Settings

**Options → Video Settings → "Far Plane (Distant Terrain)..."**. The button lives right in the menu, so no keyboard is needed on a phone.
With [Mod Menu](https://modrinth.com/mod/modmenu) installed the same settings open from the mod list.

The main page has:

- **LOD Terrain**: turns distant and detailed terrain on and off in all dimensions.
- **Loading While Moving**: how detailed terrain loads while you move. Auto (default): smooth and without input lag while walking,
  as fast as possible while flying fast, at the cost of FPS. Smooth: always smooth frames, terrain may not keep up with fast flight.
  Fast: always as fast as possible.
- **Distant Terrain...** and **Detailed Terrain...**: each opens a list of dimensions (Overworld, End), and every dimension has its
  own settings, described below.
- **Clear LOD Cache of This Dimension...**: same as F3 + O, asks for confirmation first.

**Distant Terrain** (per dimension):

- **LOD Distance**: 256 to 30 000 000 blocks. The maximum means the whole world: everything up to the world border is drawn, so the
  visible area is the square world itself. Tiles form a quadtree, so their number grows logarithmically with the distance and the
  whole world costs only a little more than 1 million blocks.
- **Start Cell Size**: size of the cells right after the detailed terrain (or after vanilla chunks when it is off): 1, 2, 4, 8 or
  16 blocks, default 4. Farther away the cells grow on their own. Smaller cells make a smoother transition; 1 and 2 put a heavy
  load on the CPU.
- **Sharpness**: Economy, Normal, Sharp or Maximum. How sharp the terrain looks far away; adapts to the screen resolution and FOV.
  Maximum is as sharp as the screen can show, for looking at the world from high above; it needs a lot of video memory and
  loads much longer.
- **GPU Memory**: video memory for distant terrain, up to 8 GB. More memory also makes the terrain sharper. Lower it on weak
  devices.
- **Fog Start**: part of the LOD distance after which terrain fades into fog.
- **No Fog**: removes all fog (render distance, weather, water, lava). Blindness and darkness still work. Off by default so it does
  not conflict with other fog mods.
- **Smoothing** and **Blur** (overworld, each on its own screen): separately for coasts, snow, forest edges, biomes and water of
  different temperatures. Smoothing (1 to 8) rounds the borders off and removes small patches, more for higher values. Blur (none
  to 5) is how wide one color turns into the other at the border, each step twice as wide.

**Detailed Terrain** (per dimension): shows real blocks (trees, buildings) around the normal render distance
instead of simplified terrain, from chunks that already exist in the world. It also stands in for vanilla chunks that are not
loaded yet.

- **Generate Unexplored Areas**: also shows real blocks where nobody has been yet; the mod generates those chunks itself, in memory only
  (nothing is added to the world), and saves them for next time. When off, simplified terrain is drawn there. Uses CPU.

- **Distance**: radius in chunks counted from the player, up to 256. Farther away the usual distant terrain is drawn. The area
  never grows past what the GPU memory setting can hold; beyond that the distant terrain takes over.
- **Blocks**: how the blocks look. Off: flat colors of the real blocks. Low: blurred textures, thin blocks (snow, carpets, slabs)
  and translucent water like vanilla's. Medium: vanilla textures. High: leaves with gaps, with a closed layer of leaves one block
  further in, so crowns look full instead of hollow. Ultra: grass and flowers too, up to 64 blocks past vanilla chunks. Every step
  uses a little more of the video card.
- **GPU Memory**: video memory for detailed terrain, up to 8 GB. More memory makes the area larger.

How fine the detailed terrain is gets chosen by itself from the screen resolution and FOV: finer cells than that would not be seen.

With Sodium installed, Sodium's video settings have an **Open** button that opens these settings.

Key bindings (can be changed in Controls; on MojoLauncher they can be put on a custom on-screen button):

- **F7** - toggle LOD terrain;
- **F8** - open Far Plane settings;
- **F3 + O** - clear the LOD cache of the dimension you are in and reload its distant and detailed terrain; other dimensions keep
  their cache. Listed under Debug in Controls; O can be changed, F3 stays. In singleplayer this deletes the cached tiles of this
  dimension and generates them again. On a server only the tiles on your client are reloaded; the server cache can only be cleared
  by an admin.

Commands. `/FPhelp` lists them all in chat and works for every player; the `/farplane` commands are for operators, or the player
in singleplayer with cheats allowed:

- `/farplane pregen detail start <radius>` - generate the detailed terrain within `<radius>` chunks (1 to 256) around you ahead of time,
  like Chunky does for chunks, but only the light copies used for distant terrain; the world itself is not changed. Chunks that
  already exist or were made before are skipped, and the work pauses while the server is busy.
- `/farplane pregen distant start <radius>` - generate the distant terrain within `<radius>` blocks around you ahead of time into the
  tile cache, with all free CPU; detailed terrain goes on slowly in the background meanwhile.
- `... start <radius> <x> <z>` - the same around the block column `<x> <z>`, for example `/farplane pregen detail start 128 0 0`.
- `/farplane pregen detail status`, `/farplane pregen distant status` - progress and time left.
- `/farplane pregen detail stop`, `/farplane pregen distant stop` - stop; what is done stays saved.

Config files: `config/farplanetwocontinuation-client.json` and `config/farplanetwocontinuation-server.json`. The server file lets the
admin limit the distance and detail, the detailed terrain radius, whether saved chunks are read for it, whether unexplored places
are generated for it, the number of generator threads, the request queue per player and the disk cache.

## How it works

1. Every tick the client walks a quadtree around the camera. Level `L` is a tile of 32×32 cells with a spacing of `2^L` blocks;
   level 19 covers ~16.7 million blocks. While a detailed tile is not ready yet, its coarser parent is drawn instead.
2. Missing tiles are requested from the server (the integrated one in singleplayer).
3. The server builds a tile straight from the world generator's noise. Chunks are **not generated or loaded**, which is what makes
   terrain millions of blocks away possible. LOD terrain has no structures or player builds: only terrain, water and biome
   colors. The surface block is guessed from the biome, the height and the slope (grass, sand, snow, stone, gravel, podzol,
   mycelium, terracotta bands in badlands, ice on frozen water, vanilla's icebergs on frozen oceans, where they really stand) and painted like vanilla's: the average color of its texture with
   the biome tint, slopes shaded like the sides of blocks, so the distant terrain matches the blocks next to it and follows
   resource packs. Coasts are drawn as sharp lines at one height: every sample keeps its land and its water color apart, water next
   to land is lowered a little, and the coast is put where the ground reaches the level of the water, along the shape of the ground,
   smoothed along a smooth line, with steps straightened and corners rounded off (high shores are cut back a little, sloping gently down to the water; narrow islands, peninsulas and rivers stay; far away, along a grid three times finer than the samples): the ground
   next to the coast is moved a little so that it still reaches the water exactly along the smoothed line. The snow line and the edges of
   forests are drawn the same way, sharp: snow at the height where it starts in the biome, forests where their tree tops end (a little soft; sparse trees of savannas and swamps stay blended), and borders between biomes of clearly different colors, and between waters of different temperatures are drawn as a soft line; borders on land and borders between waters each have a color of their own on the samples, so both run on across coasts, instead of blending land into water over a whole cell. In wooded biomes (forests, taiga, jungle, mangrove swamps, cherry groves, savannas and others) the surface is raised to
   where the tree tops would be, in patches as dense as the trees of the biome and thinning out on steep slopes, and painted in the color of their leaves. Ice spikes biomes get their spikes the same way, pointed and in the color of packed ice.
   Every tile shapes its borders together with a margin of its neighbours' samples, so two tiles always agree on their shared edge
   instead of showing a line there; a tile whose neighbour arrives later is built again once.
4. Near tiles find the surface exactly by sampling the noise density. Far tiles (32+ blocks between samples) use the generator's 2D
   surface estimate, which is checked against the exact surface on every tile and corrected by it near the sea level (where a few
   blocks decide between land and sea). They look for that surface in the generator's terrain without its caves, which is much
   cheaper to sample and the same where it can be seen from that far. They take 3×3 sub-samples per cell whose colors the
   client averages, so the world does not turn into noise made of random islands when seen from far away. The climate that picks
   the biomes is sampled for the whole tile at once, and everything sampled afterwards reuses it from the cache.
5. Detailed tiles are built from real blocks: chunks loaded on the server are read directly and chunks saved on disk are read from
   the region files without loading them into the world; only the sections near the surface are decoded. For every column up to
   three runs of blocks are kept: water, leaves and other blocks that can be seen through are runs of their own, so the ground under
   them is drawn too, and so is what lies on water (ice, lily pads), which floats instead of reaching down to the bottom. They are drawn as boxes with the block textures (or their colors), with grass, leaves and water tinted by the
   biome. Next to the player every cell is one block; cells get coarser with distance, up to one cell per 4 blocks. Region files are read a few chunks at a time, so the
   server's own chunk loading never waits on LOD work.
   When blocks change, the server notes the chunk (a few bytes, nothing extra is saved) and the detailed tiles over it are loaded
   again, so what players built or broke shows up as soon as vanilla stops drawing that place. Where vanilla already draws a chunk,
   the detailed blocks under it are left out.
6. Chunks that were never generated are made in memory with vanilla's own world generation: terrain, surface blocks, trees,
   plants, snow and structures, exactly where the real ones will be. Only what can be seen from above is made: a band around the
   surface is filled, surface rules run only for the top layers, and aquifers, caves, ores and underground features are skipped.
   Only chunks that do not exist yet are made, and the work pauses while the server is short of time. Nothing is lit, saved
   to the world or registered with it; the result is kept in `<world>/farplanetwocontinuation/virtual/`, so every region is made
   only once. Regions of 16×16 chunks are made at once, which costs about half as much per chunk as single tiles. Once a chunk is really generated, its real blocks are used instead. While a tile is not ready the rough terrain shows
   through.
7. Tiles are as fine as the screen needs: each cell takes about the same number of pixels at any distance. Right after the
   detailed terrain the cells have the start size and then double every quarter of the detailed radius (64 to 512 blocks) until
   the screen rule takes over, so there is no jump in detail at the edge. The last part of the detailed ring (an eighth of its
   radius, 16 to 256 blocks) blends smoothly into the distant terrain, its textures getting blurrier towards the edge, instead
   of ending in a line. Inside the detailed terrain and vanilla's chunks the tree tops of the distant terrain lie flat, so they never
   show through real blocks.
   The higher the camera, the finer the tiles: from above the terrain is seen head-on instead of at a grazing angle. When the camera
   goes past the far plane of the vanilla renderer, the ground below the player is drawn by LOD as well.
8. Finished tiles are cached in the world folder (`<world>/farplanetwocontinuation/`) and are never generated twice. When a new
   version of the mod stores them differently, the outdated cache is cleared on the first start and made again.
9. The client builds meshes on a background thread and draws them through Minecraft's own rendering API (renderpearl): a separate
   pass before vanilla terrain, with its own projection fitted to the visible tiles. Where two layers cover the same place, the
   more detailed one wins: vanilla over detailed tiles, detailed tiles over rough ones. This costs nothing on the CPU: less
   detailed layers are only moved a little away from the camera for the depth test. Detailed tiles are left out wherever vanilla
   (or Sodium) already draws a chunk. All LOD terrain is drawn from the front only: the distant surface faces up and the skirts that
   close the gaps between tiles of different detail face out of their tile and reach only as deep as needed, so from under the
   ground hardly anything of it shows, and walls of neighbouring tiles never flicker. Afterwards the depth is written again in
   vanilla's own depth range beyond vanilla's render distance, so clouds behind LOD mountains are hidden.
10. Finished meshes are uploaded to the GPU for a few milliseconds per frame at most; big ones are written in parts over several
    frames, so loading never stalls a frame. When frames run late, the background work of the mod pauses for a moment and leaves
    the CPU to the game. How much it holds back follows the player's speed (see Loading While Moving): walking, frames come first;
    flying fast, terrain loads at full speed in the direction of flight.
11. Only what can be seen is sent to the GPU. Tiles off screen are skipped, and of detailed tiles on the edge of the screen only the
    chunks on screen are drawn. Terrain hides what is behind it: walking from near to far, the ground raises a horizon around the
    camera, and rough tiles and chunks of detailed tiles that lie completely below it are not drawn at all (behind hills and
    mountains, in valleys and pits). This only depends on where the camera is, so turning around never shows anything missing, and
    it errs on the side of drawing too much. The faces of detailed tiles are stored by the way they face, and those turned away from
    the camera are not drawn either (like Sodium does). Tiles with nothing cut away are drawn with a shader that never drops a fragment, which
    lets the GPU skip hidden fragments early (on Adreno phones it is what enables their early depth test).

## Phones (MojoLauncher)

Rendering uses the same calls as vanilla, without direct OpenGL, so it runs on every backend that Minecraft 26.3 itself runs on:
OpenGL, LTW (GL on top of GLES 3), Zink/Freedreno, Vulkan. Limits of LTW were taken into account:

- `glPolygonOffset` and `glPolygonMode` are not used, they are stubs in LTW;
- draw calls with a base vertex are not used, LTW needs GLES 3.1 for them. Each tile has its own vertex buffer and `baseVertex = 0`;
- 16-bit indices and one shared index buffer;
- without `clip_control` (common on GLES) depth precision drops, so the LOD pass fits its near and far planes to the visible tiles;
- if the driver cannot compile the shader, LOD terrain turns off with a message in the log instead of crashing the game.

## Safety

- No native code, JNI, `Unsafe`, process launching or network connections other than Minecraft's own channel. Reflection is used
  only to call one public method of Sodium (whether a chunk section is built), and only when Sodium is installed, and to read once
  per biome whether its temperature changes from place to place (Minecraft keeps that private). On rooted devices the mod gets no extra permissions and touches nothing outside the game.
- Files are written in two places only: `config/` (two JSON files) and `<world folder>/farplanetwocontinuation/`. File names are built
  from numbers and a sanitized dimension name, and every path is checked to stay inside the cache root. Writes are atomic (through a
  temporary file).
- All input from the network and from disk is validated: packet and file size, number of requests per packet (≤ 64), palette size,
  string lengths, coordinates inside the world, allowed detail level, height range. Decompression is size-limited (zip bomb
  protection).
- The server limits every player: request queue (512 by default) and tiles generated at the same time (6). Distance and detail are
  set by the admin.
- Generation and mesh building run on a bounded number of background threads below the priority of the game's own threads; on
  phones fewer of them are used.
  The CPU goes to holes in the distant terrain first, then to detailed terrain, then to refining distant terrain. Memory use is
  bounded by the cache limits, which follow the size of the Java heap.

## License

FarPlaneTwoContinuation is released under the [MIT License](LICENSE.txt).

## Building

```
./gradlew build
```

The jar ends up in `build/libs/FarPlaneTwoContinuation-x.x.x.jar`.

`./gradlew build -Pdebug_build` makes a debug jar that writes detailed performance logs (frame times, uploads, background threads,
CPU frequencies, temperatures, the parts of the client tick and of tile generation) and every settings change to `latest.log`, for
finding the cause of stutters on a device. In it, parts of the distant terrain can be turned off or marked in color by creating
files named `farplane-debug-...` in `config/` while the game runs.

## Credits

This mod is a continuation of the idea of **FarPlaneTwo** by **DaPorkchop_**:
https://github.com/PorkStudios/FarPlaneTwo

FarPlaneTwo is Copyright (c) 2020-2026 DaPorkchop_ and is distributed under a license adapted from the MIT License, which asks every
user of the software to credit the original authors (DaPorkchop_) and to link to the original project. FarPlaneTwoContinuation does
not contain FarPlaneTwo source code: it is an independent implementation for Minecraft 26.3 and Fabric, inspired by the original
mod. Credit and the link are given here as the original author asked.
